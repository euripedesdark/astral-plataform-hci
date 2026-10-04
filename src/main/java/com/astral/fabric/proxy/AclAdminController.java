package com.astral.fabric.proxy;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CRUD da politica de navegacao. Somente {@code ASTRAL_ADMIN} -- o motor decide
 * acesso de navegacao, quem escreve a politica e' quem administra o portal.
 *
 * <p><b>Toda escrita invalida cache e indice.</b> Nao e' opcional e nao depende
 * de esperar TTL: politica que so' vale daqui a 15 minutos nao e' politica,
 * e' sugestao. A invalidacao falhar nao bloqueia a escrita (Redis fora nao pode
 * impedir o operador de corrigir uma regra), mas e' logada.
 */
@RestController
@RequestMapping("/api/v1/acl")
@PreAuthorize("hasRole('ASTRAL_ADMIN')")
public class AclAdminController {

    private final AclCategoryRepo categorias;
    private final AclDomainRepo dominios;
    private final AclDomainOverrideRepo overrides;
    private final AclRuleRepo regras;
    private final AclCacheService cache;
    private final AclEvaluatorService motor;

    public AclAdminController(AclCategoryRepo categorias,
                              AclDomainRepo dominios,
                              AclDomainOverrideRepo overrides,
                              AclRuleRepo regras,
                              AclCacheService cache,
                              AclEvaluatorService motor) {
        this.categorias = categorias;
        this.dominios = dominios;
        this.overrides = overrides;
        this.regras = regras;
        this.cache = cache;
        this.motor = motor;
    }

    // ------------------------------------------------------------------ leitura

    @GetMapping("/categories")
    public List<Map<String, Object>> categorias() {
        return categorias.findAll().stream()
                .sorted((a, b) -> a.code.compareToIgnoreCase(b.code))
                .map(c -> Map.<String, Object>of(
                        "id", c.id,
                        "code", c.code,
                        "name", c.name,
                        "description", c.description == null ? "" : c.description,
                        "blockedByDefault", c.blockedByDefault,
                        "source", c.source))
                .toList();
    }

    @GetMapping("/domains")
    public List<Map<String, Object>> dominios() {
        return dominios.findAllByOrderByPatternAsc().stream()
                .map(d -> Map.<String, Object>of(
                        "id", d.id,
                        "pattern", d.pattern,
                        "categoryId", d.category.id,
                        "categoryCode", d.category.code,
                        "categoryName", d.category.name,
                        "source", d.source))
                .toList();
    }

    @GetMapping("/overrides")
    public List<Map<String, Object>> excecoes() {
        return overrides.findAll().stream()
                .sorted((a, b) -> a.pattern.compareToIgnoreCase(b.pattern))
                .map(o -> {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", o.id);
                    m.put("pattern", o.pattern);
                    m.put("adGroup", o.adGroup == null ? "*" : o.adGroup);
                    m.put("action", o.action);
                    m.put("reason", o.reason == null ? "" : o.reason);
                    m.put("enabled", o.enabled);
                    m.put("createdBy", o.createdBy == null ? "-" : o.createdBy);
                    return m;
                })
                .toList();
    }

    @GetMapping("/rules")
    public List<Map<String, Object>> regras() {
        return regras.findAllByOrderByPriorityAsc().stream()
                .map(r -> {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", r.id);
                    m.put("categoryId", r.category.id);
                    m.put("categoryCode", r.category.code);
                    m.put("categoryName", r.category.name);
                    m.put("adGroup", r.adGroup);
                    m.put("action", r.action);
                    m.put("priority", r.priority);
                    m.put("enabled", r.enabled);
                    m.put("justification", r.justification == null ? "" : r.justification);
                    return m;
                })
                .toList();
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> m = new java.util.LinkedHashMap<>(motor.metricas());
        m.put("categorias", categorias.count());
        m.put("dominios", dominios.count());
        m.put("excecoes", overrides.count());
        m.put("regras", regras.count());
        return m;
    }

    // ------------------------------------------------------------------ dominio

    @PostMapping("/domains")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> criaDominio(@RequestBody DomainsRequest req) {
        String padrao = AclDomain.normaliza(req.pattern());
        if (padrao.isEmpty()) erro("pattern e' obrigatorio");
        if (!padrao.contains("*") && !padrao.contains(".")) {
            erro("pattern precisa ser um dominio (ex.: facebook.com) ou padrao (ex.: *.facebook.com)");
        }
        AclCategory cat = categorias.findById(req.categoryId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "categoria nao existe"));
        if (dominios.findByPattern(padrao).isPresent()) erro("padrao ja' cadastrado: " + padrao);

        AclDomain d = new AclDomain(padrao, cat, req.source() == null || req.source().isBlank() ? "manual" : req.source());
        d = dominios.save(d);
        invalidaTudo();
        return Map.of("id", d.id, "pattern", d.pattern, "categoryCode", cat.code);
    }

    @DeleteMapping("/domains/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void removeDominio(@PathVariable Long id) {
        if (!dominios.existsById(id)) erro("dominio nao existe");
        dominios.deleteById(id);
        invalidaTudo();
    }

    // ---------------------------------------------------------------- excecao

    @PostMapping("/overrides")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> criaExcecao(@RequestBody OverridesRequest req) {
        String padrao = AclDomain.normaliza(req.pattern());
        if (padrao.isEmpty()) erro("pattern e' obrigatorio");
        String acao = req.action() == null ? "" : req.action().trim().toUpperCase(Locale.ROOT);
        if (!acao.equals(AclDomainOverride.ALLOW) && !acao.equals(AclDomainOverride.DENY)
                && !acao.equals(AclDomainOverride.BYPASS)) {
            erro("action deve ser ALLOW, DENY ou BYPASS");
        }
        if (overrides.findAll().stream().anyMatch(o -> AclDomain.normaliza(o.pattern).equals(padrao))) {
            erro("excecao ja' existe para " + padrao);
        }
        AclDomainOverride o = new AclDomainOverride();
        o.pattern = padrao;
        o.adGroup = req.adGroup() == null || req.adGroup().isBlank() ? null : req.adGroup().trim();
        o.action = acao;
        o.reason = req.reason();
        o.createdBy = usuarioAtual();
        o = overrides.save(o);
        // Indice em memoria + todos os caches: excecao decide ANTES de tudo,
        // entao ela tem que valer ja' na proxima requisicao.
        invalidaTudo();
        return Map.of("id", o.id, "pattern", o.pattern, "action", o.action);
    }

    @DeleteMapping("/overrides/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void removeExcecao(@PathVariable Long id) {
        if (!overrides.existsById(id)) erro("excecao nao existe");
        overrides.deleteById(id);
        invalidaTudo();
    }

    // ------------------------------------------------------------------ regra

    @PostMapping("/rules")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> criaRegra(@RequestBody RulesRequest req) {
        AclCategory cat = categorias.findById(req.categoryId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "categoria nao existe"));
        String grupo = req.adGroup() == null ? "" : req.adGroup().trim();
        if (grupo.isEmpty()) erro("adGroup e' obrigatorio (use * para valer para todo mundo autenticado)");
        String acao = req.action() == null ? "" : req.action().trim().toUpperCase(Locale.ROOT);
        if (!acao.equals(AclRule.ALLOW) && !acao.equals(AclRule.DENY)) {
            erro("action deve ser ALLOW ou DENY");
        }
        int prioridade = req.priority() == null ? 100 : req.priority();
        if (prioridade < 1 || prioridade > 9999) erro("priority deve ficar entre 1 e 9999");

        try {
            AclRule r = new AclRule(cat, grupo, acao, prioridade);
            r.enabled = req.enabled() == null || req.enabled();
            r.justification = req.justification();
            r = regras.save(r);
            invalidaTudo();
            return Map.of("id", r.id, "categoryCode", cat.code, "adGroup", r.adGroup, "action", r.action);
        } catch (DataIntegrityViolationException e) {
            // Unicidade (categoria, grupo): duas regras concorrentes fariam a
            // ordem de leitura decidir acesso.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "ja' existe regra para esta categoria e grupo");
        }
    }

    @DeleteMapping("/rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void removeRegra(@PathVariable Long id) {
        if (!regras.existsById(id)) erro("regra nao existe");
        regras.deleteById(id);
        invalidaTudo();
    }

    // ------------------------------------------------------------------- cache

    @PostMapping("/cache/invalidate")
    public Map<String, Object> invalidaCache() {
        cache.invalidaTudo();
        motor.invalidaIndice();
        return Map.of("ok", true, "estado", cache.estado());
    }

    // ---------------------------------------------------------------- auxiliar

    /**
     * Invalida tudo de uma vez. Se o Redis estiver fora, {@code invalidaTudo}
     * engole a excecao e o indice em memoria ainda e' resetado -- no pior caso
     * a politica antiga vale por mais um TTL, o que e' infinitamente melhor
     * que travar o operado no meio de uma correcao de incidente.
     */
    private void invalidaTudo() {
        cache.invalidaTudo();
        motor.invalidaIndice();
    }

    private void erro(String mensagem) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, mensagem);
    }

    private String usuarioAtual() {
        try {
            var auth = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication();
            return auth == null ? "desconhecido" : auth.getName();
        } catch (Exception e) {
            return "desconhecido";
        }
    }

    public record DomainsRequest(String pattern, Long categoryId, String source) {
    }

    public record OverridesRequest(String pattern, String adGroup, String action, String reason) {
    }

    public record RulesRequest(Long categoryId, String adGroup, String action,
                               Integer priority, Boolean enabled, String justification) {
    }
}
