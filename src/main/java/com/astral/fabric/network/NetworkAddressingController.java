package com.astral.fabric.network;

import com.astral.fabric.reconciliation.Intent;
import com.astral.fabric.reconciliation.IntentPublisher;
import com.astral.fabric.reconciliation.ReconcileModule;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API do formulario de Configuracao de Enderecamento de Rede.
 *
 * <p>Tres endpoints com semantica diferente, de proposito:
 *
 * <ul>
 *   <li>{@code GET}  -- estado observado (somente leitura, qualquer usuario autenticado).</li>
 *   <li>{@code POST /validar} -- validacao + diff, NADA aplicado. A tela usa para
 *       mostrar o plano antes de confirmar.</li>
 *   <li>{@code POST} -- publica a intencao na fila. Exige admin, porque e' aqui
 *       que a mudanca deixa de ser proposta e vira obrigacao de reconciliacao.</li>
 * </ul>
 *
 * <p>A UI e' so' cliente destes endpoints -- CLI-First e API-Driven.
 */
@RestController
@RequestMapping("/api/v1/network/addressing")
public class NetworkAddressingController {

    private final NetworkAddressingService servico;
    private final IntentPublisher publisher;
    private final ObjectMapper json;

    public NetworkAddressingController(NetworkAddressingService servico,
                                       IntentPublisher publisher,
                                       ObjectMapper json) {
        this.servico = servico;
        this.publisher = publisher;
        this.json = json;
    }

    @GetMapping
    public Map<String, Object> atual() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("addressing", servico.atual());
        m.put("interfaces", servico.interfacesDoKernel());
        m.put("observado", servico.observado());
        return m;
    }

    @GetMapping("/interfaces")
    public List<String> interfaces() {
        return servico.interfacesDoKernel();
    }

    /** Validacao + plano, sem aplicar. Prova que a mudanca e' possivel antes de pedir. */
    @PostMapping("/validar")
    public ResponseEntity<Map<String, Object>> validar(@RequestBody EnderecamentoRede desejo) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        List<String> erros = desejo.validar();
        corpo.put("erros", erros);
        if (!erros.isEmpty()) {
            corpo.put("aprovado", false);
            corpo.put("plano", List.of());
            return ResponseEntity.unprocessableEntity().body(corpo);
        }
        corpo.put("aprovado", true);
        corpo.put("plano", servico.plano(desejo));
        corpo.put("semMudanca", servico.plano(desejo).isEmpty());
        return ResponseEntity.ok(corpo);
    }

    @PostMapping
    @PreAuthorize("hasRole('ASTRAL_ADMIN')")
    public ResponseEntity<Map<String, Object>> aplica(@RequestBody EnderecamentoRede desejo,
                                                      Principal principal) {
        List<String> erros = desejo.validar();
        if (!erros.isEmpty()) {
            return ResponseEntity.unprocessableEntity()
                    .body(Map.of("erros", erros, "aprovado", false));
        }
        String payload;
        try {
            payload = json.writeValueAsString(desejo);
        } catch (Exception e) {
            return ResponseEntity.unprocessableEntity()
                    .body(Map.of("erros", List.of("serializacao: " + e.getMessage())));
        }
        Intent intent = publisher.publica(ReconcileModule.NETWORK, "addressing", payload,
                principal == null ? "desconhecido" : principal.getName());
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("intentId", intent.getId());
        corpo.put("status", intent.getStatus());
        corpo.put("plano", servico.plano(desejo));
        corpo.put("message", "intencao registrada; acompanhe em /api/v1/intents/" + intent.getId());
        return ResponseEntity.accepted().body(corpo);
    }
}
