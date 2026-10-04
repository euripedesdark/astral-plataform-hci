package com.astral.fabric.firewall;

import com.astral.fabric.firewall.service.IptablesService;
import com.astral.fabric.reconciliation.Commit;
import com.astral.fabric.reconciliation.Diff;
import com.astral.fabric.reconciliation.Intent;
import com.astral.fabric.reconciliation.Progresso;
import com.astral.fabric.reconciliation.ReconcilableModule;
import com.astral.fabric.reconciliation.ReconcileModule;
import com.astral.fabric.reconciliation.Validacao;
import com.astral.fabric.support.RollbackStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reconciliacao do firewall via iptables.
 *
 * <p>O modulo de firewall ja' tem o ciclo de snapshot/restore no proprio
 * {@link IptablesService} ({@code saveSnapshot} / {@code restoreDump}) -- ele
 * nasceu com modo panico, que e' rollback por definicao. O que este adaptador
 * acrescenta e' o encaixe no ciclo declarativo: validar antes, difar antes,
 * capturar o snapshot ANTES de aplicar e devolver como {@code token}.
 *
 * <p>Payload aceito:
 * <pre>
 * {
 *   "tipo": "panic" | "revert" | "aplicar",
 *   "confirm": true
 * }
 * </pre>
 */
@Component
public class FirewallReconcileModule implements ReconcilableModule {

    private static final Logger log = LoggerFactory.getLogger(FirewallReconcileModule.class);

    private final IptablesService iptables;
    private final RollbackStore store;
    private final ObjectMapper json;

    public FirewallReconcileModule(IptablesService iptables, RollbackStore store, ObjectMapper json) {
        this.iptables = iptables;
        this.store = store;
        this.json = json;
    }

    @Override
    public ReconcileModule module() {
        return ReconcileModule.FIREWALL;
    }

    @Override
    public Validacao validar(Intent intent) {
        JsonNode no;
        try {
            no = json.readTree(intent.getPayload());
        } catch (Exception e) {
            return Validacao.falha("payload invalido: " + e.getMessage());
        }
        String tipo = no.path("tipo").asText("");
        switch (tipo) {
            case "panic" -> {
                if (!no.path("confirm").asBoolean(false)) {
                    return Validacao.falha("panic exige confirm=true -- e' uma decisao humana, nao um acidente");
                }
            }
            case "revert", "aplicar" -> { /* sem precondicao extra */ }
            default -> {
                return Validacao.falha("tipo desconhecido: '" + tipo + "' (esperado panic | revert | aplicar)");
            }
        }
        // Ferramenta precisa existir: sem iptables nao ha para onde reconciliar.
        if (iptables.sh("command -v iptables >/dev/null && echo ok").isBlank()) {
            return Validacao.falha("iptables nao esta instalado neste host");
        }
        return Validacao.ok();
    }

    @Override
    public Diff diff(Intent intent) {
        try {
            JsonNode no = json.readTree(intent.getPayload());
            String tipo = no.path("tipo").asText();
            Map<String, Object> antes = new LinkedHashMap<>();
            antes.put("panicActive", iptables.panicActive());
            antes.put("regras", reconta());
            Map<String, Object> depois = new LinkedHashMap<>(antes);
            switch (tipo) {
                case "panic" -> depois.put("panicActive", true);
                case "revert" -> depois.put("panicActive", false);
                default -> depois.put("regras", "re-sincronizadas a partir do banco");
            }
            boolean mudanca = !antes.equals(depois);
            return mudanca ? Diff.de(antes, depois, "iptables: " + tipo)
                    : Diff.semMudanca(antes);
        } catch (IOException e) {
            throw new IllegalStateException("diff do firewall falhou: " + e.getMessage(), e);
        }
    }

    @Override
    public Commit commit(Intent intent, Diff diff, Progresso progresso) {
        String tipo;
        try {
            tipo = json.readTree(intent.getPayload()).path("tipo").asText();
        } catch (IOException e) {
            throw new IllegalStateException("payload ilegivel no commit: " + e.getMessage(), e);
        }
        List<String> comandos = new ArrayList<>();
        try {
            String token;
            switch (tipo) {
                case "panic" -> {
                    // saveSnapshot e' ANTES de mexer: e' o snapshot de rollback.
                    var snap = iptables.saveSnapshot("pre-reconcile-" + intent.getId());
                    token = store.grava("firewall", Map.of("snapshotId", String.valueOf(snap.id)));
                    comandos.add("iptables-save (snapshot id=" + snap.id + ")");
                    progresso.comandoAplicado(comandos.get(comandos.size() - 1));
                    iptables.panic(intent.getRequestedBy() == null ? "reconciliation" : intent.getRequestedBy());
                    comandos.add("iptables -P INPUT DROP + reabertura de portas essenciais");
                    progresso.comandoAplicado(comandos.get(comandos.size() - 1));
                }
                case "revert" -> {
                    var snap = iptables.saveSnapshot("pre-revert-" + intent.getId());
                    token = store.grava("firewall", Map.of("snapshotId", String.valueOf(snap.id)));
                    comandos.add("iptables-save (snapshot id=" + snap.id + ")");
                    progresso.comandoAplicado(comandos.get(0));
                    iptables.revert(intent.getRequestedBy() == null ? "reconciliation" : intent.getRequestedBy());
                    comandos.add("iptables-restore do ultimo snapshot pre-");
                    progresso.comandoAplicado(comandos.get(comandos.size() - 1));
                }
                default -> {
                    token = store.grava("firewall", Map.of("acao", "aplicar"));
                    comandos.add("sync a partir do banco");
                    progresso.comandoAplicado(comandos.get(0));
                    iptables.applyFromDb(intent.getRequestedBy() == null ? "reconciliation" : intent.getRequestedBy());
                }
            }
            log.info("firewall: commit {} tipo={} snapshot={}", intent.getId(), tipo, token);
            return Commit.de(token, comandos);
        } catch (IOException e) {
            // O token de rollback nao foi gravado: recusar o commit AQUI e'
            // deliberado. Aplicar mudanca sem como desfaze-la e' exatamente o
            // estado que o rollback automatico existe para evitar.
            throw new IllegalStateException("token de rollback nao gravado: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw e;
        }
    }

    @Override
    public void rollback(Intent intent, Commit commit) {
        if (commit == null || commit.token() == null) {
            log.warn("firewall: rollback de {} sem snapshot", intent.getId());
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> snap = store.le(commit.token(), Map.class);
            Object id = snap.get("snapshotId");
            if (id != null) {
                iptables.restoreSnapshotById(Long.parseLong(String.valueOf(id)));
            } else {
                // sem snapshot anterior: reverte para o ultimo pre- conhecido
                iptables.revert(intent.getRequestedBy() == null ? "reconciliation" : intent.getRequestedBy());
            }
            log.warn("firewall: rollback de {} executado", intent.getId());
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("rollback do firewall impossivel: " + e.getMessage(), e);
        } finally {
            store.remove(commit.token());
        }
    }

    private long reconta() {
        try {
            return Long.parseLong(iptables.sh("sudo iptables-save 2>/dev/null | grep -c '^-A ' || echo 0")
                    .replace("\n", "").trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
