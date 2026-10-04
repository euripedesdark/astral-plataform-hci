package com.astral.fabric.network;

import com.astral.fabric.reconciliation.Commit;
import com.astral.fabric.reconciliation.Diff;
import com.astral.fabric.reconciliation.Intent;
import com.astral.fabric.reconciliation.Progresso;
import com.astral.fabric.reconciliation.ReconcilableModule;
import com.astral.fabric.reconciliation.ReconcileModule;
import com.astral.fabric.reconciliation.Validacao;
import com.astral.fabric.support.RollbackStore;
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
 * Reconciliacao do enderecamento de rede.
 *
 * <p>Costura o contrato declarativo ({@link EnderecamentoRede}) ao ciclo do
 * {@code ReconciliationEngine}. Toda a logica de nmcli vive em
 * {@link NetworkAddressingService}; aqui mora apenas a traducao para os quatro
 * passos do motor -- validar, difar, aplicar, desfazer.
 *
 * <p>O snapshot de rollback vai para disco ({@link RollbackStore}), nao para
 * memoria: cair entre commit e rollback e' justamente o caso em que o snapshot
 * precisa continuar la'.
 */
@Component
public class NetworkReconcileModule implements ReconcilableModule {

    private static final Logger log = LoggerFactory.getLogger(NetworkReconcileModule.class);

    private final NetworkAddressingService servico;
    private final RollbackStore store;
    private final ObjectMapper json;

    public NetworkReconcileModule(NetworkAddressingService servico,
                                  RollbackStore store,
                                  ObjectMapper json) {
        this.servico = servico;
        this.store = store;
        this.json = json;
    }

    @Override
    public ReconcileModule module() {
        return ReconcileModule.NETWORK;
    }

    @Override
    public Validacao validar(Intent intent) {
        EnderecamentoRede desejo;
        try {
            desejo = json.readValue(intent.getPayload(), EnderecamentoRede.class);
        } catch (Exception e) {
            return Validacao.falha("payload nao e' um EnderecamentoRede: " + e.getMessage());
        }
        List<String> erros = new ArrayList<>(desejo.validar());
        // Precondicao de ambiente: sem NetworkManager nao ha para onde aplicar.
        if (servico.interfacesDoKernel().isEmpty()) {
            erros.add("nenhuma interface de rede visivel no kernel");
        }
        return erros.isEmpty() ? Validacao.ok() : new Validacao(erros);
    }

    @Override
    public Diff diff(Intent intent) {
        try {
            EnderecamentoRede desejo = json.readValue(intent.getPayload(), EnderecamentoRede.class);
            Map<String, Object> antes = servico.observado();
            List<String> plano = servico.plano(desejo);
            Map<String, Object> depois = new LinkedHashMap<>(antes);
            if (!plano.isEmpty()) {
                Map<String, Object> desejado = new LinkedHashMap<>();
                for (InterfaceRede i : desejo.interfaces()) {
                    desejado.put(i.nome(), Map.of(
                            "endereco", i.enderecoCidr(),
                            "gateway", i.gateway() == null ? "" : i.gateway(),
                            "papel", i.papel()));
                }
                desejado.put("dns", desejo.dns());
                depois.put("desejado", desejado);
                depois.put("comandos", plano);
            }
            return plano.isEmpty()
                    ? Diff.semMudanca(antes)
                    : Diff.de(antes, depois, plano.size() + " comando(s) de nmcli pendente(s)");
        } catch (IOException e) {
            // diff e' obrigatorio a partir de payload ja' validado; se falhou aqui,
            // algo mudou entre validar e difar. Reprova em vez de assumir.
            throw new IllegalStateException("diff de enderecamento falhou: " + e.getMessage(), e);
        }
    }

    @Override
    public Commit commit(Intent intent, Diff diff, Progresso progresso) {
        try {
            EnderecamentoRede desejo = json.readValue(intent.getPayload(), EnderecamentoRede.class);
            NetworkAddressingService.Snapshot snapshot = servico.aplica(desejo);
            String token = store.grava("network", snapshot);
            for (String c : snapshot.comandos()) progresso.comandoAplicado(c);
            log.info("network: commit {} com snapshot {}", intent.getId(), token);
            return Commit.de(token, snapshot.comandos());
        } catch (IOException e) {
            throw new IllegalStateException("commit de enderecamento: " + e.getMessage(), e);
        }
    }

    @Override
    public void rollback(Intent intent, Commit commit) {
        if (commit == null || (commit.token() == null && !commit.aplicou())) {
            // nada chegou a ser aplicado e nada foi capturado: nao ha o que desfazer
            log.warn("network: rollback de {} sem snapshot (nada aplicado)", intent.getId());
            return;
        }
        try {
            NetworkAddressingService.Snapshot snapshot =
                    store.le(commit.token(), NetworkAddressingService.Snapshot.class);
            servico.desfaz(snapshot);
            log.warn("network: rollback de {} executado a partir de {}", intent.getId(), commit.token());
        } catch (IOException e) {
            throw new IllegalStateException("rollback de enderecamento impossivel: " + e.getMessage(), e);
        } finally {
            store.remove(commit.token());
        }
    }
}
