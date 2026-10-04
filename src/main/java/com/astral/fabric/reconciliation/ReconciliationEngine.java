package com.astral.fabric.reconciliation;

import com.astral.fabric.proxy.audit.GraylogAuditPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Motor de reconciliacao declarativa.
 *
 * <p>Consome a fila do RabbitMQ e executa o ciclo imutavel da Astral:
 *
 * <pre>
 *   Intenção → Validação → Reconciliação (Diff) → Commit → Rollback Obrigatório Automático
 * </pre>
 *
 * <p>Decisoes de projeto que valem ser nomeadas:
 *
 * <ul>
 *   <li><b>O motor e' generico; os modulos sao especificos.</b> Ele nao sabe o
 *       que e' um nmcli. Isso e' o que permite acrescentar DNS, identidade e
 *       proxy sem mexer no consumo da fila.</li>
 *   <li><b>Rollback e' obrigatoria e automática.</b> Falha no commit dispara
 *       {@link ReconcilableModule#rollback} SEM passar por humano. Autoridade
 *       humana, aqui, e' decidir o que fazer DEPOIS do rollback -- nao decidir
 *       se o rollback acontece.</li>
 *   <li><b>Diff e' sempre calculado, mesmo quando da na hora.</b> Sem diff nao
 *       ha o que auditar depois: "aplicou" sem "de quanto para quanto" nao e'
 *       rastreabilidade.</li>
 *   <li><b>Idempotencia por id da intencao.</b> Reentrega de fila e' normal em
 *       broker; aplicar duas vezes nao e'.</li>
 * </ul>
 *
 * <p>O processamento e' serial por intencao e assincrono em relacao a quem
 * publicou: quem mandou na fila nao espera a reconciliacao, e' o motivo de a
 * fila existir.
 */
@Component
public class ReconciliationEngine {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationEngine.class);

    private final Map<ReconcileModule, ReconcilableModule> modulos = new LinkedHashMap<>();
    private final IntentRepo repo;
    private final GraylogAuditPublisher auditoria;
    private final ObjectMapper json;

    public ReconciliationEngine(List<ReconcilableModule> beans,
                                IntentRepo repo,
                                GraylogAuditPublisher auditoria,
                                ObjectMapper json) {
        this.repo = repo;
        this.auditoria = auditoria;
        this.json = json;
        for (ReconcilableModule m : beans) {
            ReconcileModule chave = m.module();
            if (modulos.put(chave, m) != null) {
                throw new IllegalStateException("Modulo duplicado na reconciliacao: " + chave);
            }
        }
        log.info("reconciliacao: modulos registrados {}", modulos.keySet());
    }

    /**
     * Ponto de entrada da fila. {@code String} e' proposital: serializar como
     * texto mantem a mensagem legivel no RabbitMQ Management, que e' onde
     * alguem vai olhar quando algo travar as 3 da manha.
     */
    // SEM @Transactional em cima: a intencao precisa ser gravada a cada
    // transicao e commitada na hora, senao um kill no meio deixa o registro
    // dizendo "RECEIVED" quando ja' tinha aplicado. Cada repo.save() e' uma
    // transacao propria, e e' exatamente isso que queremos aqui.
    // O default tem que ser o MESMO nome do bean de Queue em RabbitConfig.
    // Eram "intents" aqui e "astral.intents" la': o consumidor procurava uma
    // fila que o broker nunca teve e o log enchia de 404 NOT_FOUND sem que a
    // aplicacao caisse -- intencao publicada, reconciliacao nunca acontecia.
    @RabbitListener(queues = "${astral.reconciliation.queue:" + RabbitConfig.QUEUE_PADRAO + "}")
    public void consome(String mensagem) {
        Intent intent;
        try {
            Intent recebida = json.readValue(mensagem, Intent.class);
            intent = repo.findById(recebida.getId()).orElse(recebida);
        } catch (Exception e) {
            log.error("reconciliacao: mensagem ilegivel, ignorada: {}", e.getMessage());
            auditoria.audit("sistema", "RECONCILIATION", "RECEIVE", "mensagem", "INVALIDA",
                    Map.of("erro", String.valueOf(e.getMessage())));
            return;
        }
        processa(intent);
    }

    /** Executa o ciclo para uma intencao ja' persistida. Visivel para teste e para a API. */
    public Intent processa(Intent intent) {
        if (intent.isApplied() || terminal(intent.getStatus())) {
            log.info("reconciliacao: {} ja' em estado terminal ({}), nada a fazer",
                    intent.getId(), intent.getStatus());
            return intent;
        }
        ReconcilableModule modulo = modulos.get(intent.getModule());
        if (modulo == null) {
            return falha(intent, "Sem modulo registrado para " + intent.getModule());
        }

        // --- 1. VALIDAÇÃO -------------------------------------------------
        intent.transicao(IntentStatus.VALIDATING);
        salva(intent);
        Validacao validacao;
        try {
            validacao = modulo.validar(intent);
        } catch (Exception e) {
            return falha(intent, "Validacao lancou excecao: " + e.getMessage());
        }
        if (!validacao.aprovada()) {
            return falha(intent, "Validacao reprovada: " + String.join("; ", validacao.erros()));
        }
        intent.transicao(IntentStatus.VALIDATED);
        salva(intent);

        // --- 2. DIFF ------------------------------------------------------
        intent.transicao(IntentStatus.DIFFING);
        salva(intent);
        Diff diff;
        try {
            diff = modulo.diff(intent);
        } catch (Exception e) {
            return falha(intent, "Diff falhou: " + e.getMessage());
        }
        intent.setDiffText(descreve(diff));
        intent.transicao(diff.mudanca() ? IntentStatus.DIFF_READY : IntentStatus.NO_OP);
        intent.setApplied(!diff.mudanca());
        salva(intent);
        publica(intent, "DIFF", intent.getDiffText());
        if (!diff.mudanca()) return intent;

        // --- 3. COMMIT ----------------------------------------------------
        Commit commit = Commit.nada();
        intent.transicao(IntentStatus.COMMITTING);
        salva(intent);
        List<String> aplicados = new ArrayList<>();
        try {
            commit = modulo.commit(intent, diff, comando -> {
                aplicados.add(comando);
                intent.setDiffText(anexa(intent.getDiffText(), "aplicado: " + comando));
                salva(intent);
            });
            intent.setApplied(true);
            intent.transicao(IntentStatus.COMMITTED);
            salva(intent);
            publica(intent, "COMMIT", "token=" + commit.token() + " comandos=" + commit.comandosAplicados().size());
            return intent;
        } catch (Throwable t) {
            // `commit` so' tem o snapshot se modulo.commit() chegou a devolver
            // um. Se a excecao veio de dentro dele, o que temos de evidencia e'
            // a lista que o Progresso acumulou -- e' ela que o rollback usa.
            Commit parcial = commit.aplicou() ? commit : Commit.de(null, aplicados);
            String motivo = (t instanceof Exception e)
                    ? "Commit falhou: " + e.getMessage()
                    : "Commit abortado: " + t;
            return falhaComRollback(intent, modulo, parcial, motivo);
        }
    }

    // ---------------------------------------------------------------------

    private Intent falhaComRollback(Intent intent, ReconcilableModule modulo,
                                    Commit commit, String motivo) {
        intent.setError(motivo);
        intent.transicao(IntentStatus.ROLLING_BACK);
        salva(intent);
        publica(intent, "ROLLBACK_INICIADO", motivo);
        try {
            modulo.rollback(intent, commit);
            intent.transicao(IntentStatus.ROLLED_BACK);
            salva(intent);
            publica(intent, "ROLLBACK", "executado com sucesso; sistema em estado conhecido");
            log.warn("reconciliacao {}: rollback executado apos {}", intent.getId(), motivo);
        } catch (Exception e) {
            // O pior caso da plataforma: nao aplicou e nao desfez.
            intent.setError(motivo + " | ROLLBACK FALHOU: " + e.getMessage());
            intent.transicao(IntentStatus.FAILED);
            salva(intent);
            log.error("reconciliacao {}: ROLLBACK FALHOU -- exige intervencao humana. {}",
                    intent.getId(), e.getMessage());
            publica(intent, "ROLLBACK_FALHOU", String.valueOf(e.getMessage()));
        }
        intent.setApplied(false);
        salva(intent);
        return intent;
    }

    private Intent falha(Intent intent, String motivo) {
        intent.setError(motivo);
        intent.transicao(IntentStatus.FAILED);
        salva(intent);
        log.error("reconciliacao {}: {}", intent.getId(), motivo);
        publica(intent, "FALHOU", motivo);
        return intent;
    }

    private void salva(Intent intent) {
        intent.setUpdatedAt(java.time.Instant.now());
        repo.save(intent);
    }

    private void publica(Intent intent, String acao, String detalhe) {
        auditoria.audit(intent.getRequestedBy() == null ? "sistema" : intent.getRequestedBy(),
                "RECONCILIATION", acao, intent.getModule() + "/" + intent.getType(),
                intent.getStatus().name(),
                Map.of("intentId", intent.getId(), "detalhe", detalhe == null ? "" : detalhe));
    }

    /**
     * Diff textual unico para todos os modulos. Justaposicao por chave: sobe o
     * que mudou, marca o que era igual. Mesmo formato em rede, firewall, dns,
     * identidade e proxy -- e' isso que torna a auditoria comparavel.
     */
    private String descreve(Diff diff) {
        if (!diff.mudanca()) return "sem mudanca: " + diff.observado();
        StringBuilder sb = new StringBuilder();
        sb.append("OBSERVADO: ").append(diff.observado()).append('\n');
        Map<String, Object> antes = diff.antes() == null ? Map.of() : diff.antes();
        Map<String, Object> depois = diff.depois() == null ? Map.of() : diff.depois();
        for (String chave : ordena(antes, depois)) {
            Object a = antes.get(chave);
            Object d = depois.get(chave);
            boolean igual = a == null ? d == null : a.equals(d);
            sb.append(igual ? "  = " : "  ~ ").append(chave)
              .append(" | antes=").append(resumo(a))
              .append(" | depois=").append(resumo(d)).append('\n');
        }
        return sb.toString();
    }

    private List<String> ordena(Map<String, Object> a, Map<String, Object> b) {
        java.util.TreeSet<String> chaves = new java.util.TreeSet<>(a.keySet());
        chaves.addAll(b.keySet());
        return new ArrayList<>(chaves);
    }

    private String resumo(Object o) {
        if (o == null) return "<nulo>";
        String s = String.valueOf(o);
        return s.length() > 160 ? s.substring(0, 157) + "..." : s;
    }

    private String anexa(String base, String linha) {
        String cabecalho = base == null ? "" : base;
        // preserva o cabecalho do diff e acrescenta a trilha de aplicacao
        int i = cabecalho.indexOf("aplicado: ");
        String antes = i < 0 ? cabecalho : cabecalho.substring(0, i);
        return antes + "aplicado: " + linha + "\n" + (i < 0 ? "" : cabecalho.substring(i));
    }

    private static boolean terminal(IntentStatus s) {
        return s == IntentStatus.COMMITTED || s == IntentStatus.ROLLED_BACK
                || s == IntentStatus.FAILED || s == IntentStatus.NO_OP;
    }
}
