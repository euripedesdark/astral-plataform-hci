package com.astral.fabric.reconciliation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API da intencao declarativa. CLI-First e API-Driven: a UI e' so' um cliente
 * destes endpoints, nao um caminho proprio.
 */
@RestController
@RequestMapping("/api/v1/intents")
public class IntentController {

    private final IntentPublisher publisher;
    private final IntentRepo repo;
    private final ObjectMapper json;

    public IntentController(IntentPublisher publisher, IntentRepo repo, ObjectMapper json) {
        this.publisher = publisher;
        this.repo = repo;
        this.json = json;
    }

    public record NovaIntencao(
            @NotBlank String module,
            @NotBlank String type,
            String payload) {
    }

    @PostMapping
    @PreAuthorize("hasRole('ASTRAL_ADMIN')")
    public ResponseEntity<?> cria(@RequestBody NovaIntencao req, java.security.Principal principal) {
        ReconcileModule modulo;
        try {
            modulo = ReconcileModule.valueOf(req.module().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return erros(List.of("module desconhecido: " + req.module()
                    + " (esperado: " + java.util.Arrays.toString(ReconcileModule.values()) + ")"));
        }
        String payload = req.payload() == null || req.payload().isBlank() ? "{}" : req.payload();
        try {
            JsonNode no = json.readTree(payload);
            if (!no.isObject()) return erros(List.of("payload precisa ser um objeto JSON"));
        } catch (Exception e) {
            return erros(List.of("payload invalido: " + e.getMessage()));
        }
        Intent intent = publisher.publica(modulo, req.type(), payload,
                principal == null ? "desconhecido" : principal.getName());
        return ResponseEntity.accepted().body(visao(intent));
    }

    @GetMapping("/lista")
    public List<Map<String, Object>> lista(@RequestParam(required = false) String module) {
        List<Intent> origem = module == null || module.isBlank()
                ? repo.findTop50ByOrderByCreatedAtDesc()
                : repo.findByModuleOrderByCreatedAtDesc(
                        ReconcileModule.valueOf(module.trim().toUpperCase()));
        return origem.stream().map(this::visao).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detalhe(@PathVariable String id) {
        return repo.findById(id)
                .<ResponseEntity<?>>map(i -> ResponseEntity.ok(visao(i)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private Map<String, Object> visao(Intent i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.getId());
        m.put("module", i.getModule());
        m.put("type", i.getType());
        m.put("status", i.getStatus());
        m.put("applied", i.isApplied());
        m.put("requestedBy", i.getRequestedBy());
        m.put("diff", i.getDiffText());
        m.put("error", i.getError());
        m.put("trail", i.getTrail());
        m.put("createdAt", i.getCreatedAt());
        m.put("updatedAt", i.getUpdatedAt());
        m.put("finishedAt", i.getFinishedAt());
        return m;
    }

    private ResponseEntity<Map<String, Object>> erros(List<String> erros) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("erros", erros);
        return ResponseEntity.badRequest().body(corpo);
    }
}
