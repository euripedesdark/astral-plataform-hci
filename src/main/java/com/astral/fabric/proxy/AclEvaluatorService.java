package com.astral.fabric.proxy;

import com.astral.fabric.proxy.audit.GraylogAuditPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Motor de decisao de ACL -- o caminho quente do {@code auth_request} do Nginx.
 *
 * <p>Quatro passos, sempre nesta ordem, e a ordem e' a politica:
 *
 * <ol>
 *   <li><b>PASSO A -- excecao explicita de dominio.</b> Indice em memoria.
 *       Existe porque excecao e' quando o operador disse "deste host
 *       especifico, nada de conteudo"; rodar depois do cruzamento forçaria o
 *       operador a escrever a excecao tambem na categoria.</li>
 *   <li><b>Cache de decisao.</b> Mesmo usuario + mesmo dominio ja' decidido
 *       nas ultimas 15min devolve sem tocar em nada. Pula para fora quando o
 *       PASSO A mandou BYPASS.</li>
 *   <li><b>PASSO B -- categoria do dominio.</b> Redis, depois PostgreSQL.</li>
 *   <li><b>PASSO C -- regra categoria x grupo.</b> Redis, depois PostgreSQL.</li>
 * </ol>
 *
 * <p><b>Os tres invariantes desta classe:</b>
 *
 * <ul>
 *   <li><i>Fechado por padrao.</i> Sem identidade 401, sem categoria a politica
 *       decide ({@code default-uncategorized}), sem regra a politica decide
 *       ({@code default-without-rule}) e o default da casa e' DENY.</li>
 *   <li><i>Fallback sobre dependencia.</i> Redis fora cai para PostgreSQL;
 *       PostgreSQL fora devolve 401 e nao 200. Nada de fail-open porque um
 *       cache caiu.</li>
 *   <li><i>Auditoria fora do caminho quente.</i> Cada decisao vira evento GELF
 *       assincrono; se o Graylog sumir, o JSONL de fallback guarda a trilha.</li>
 * </ul>
 */
@Service
public class AclEvaluatorService {

    private static final Logger log = LoggerFactory.getLogger(AclEvaluatorService.class);

    /** Categoria inexistente gravada como cache negativo. */
    static final String SEM_CATEGORIA = "SEM_CATEGORIA";
    /** Valor que nunca casa com grupo nenhum, para a query nao ficar com IN vazio. */
    private static final String GRUPO_IMPOSSIVEL = "__NENHUM__";

    private final AclDomainRepo dominios;
    private final AclCategoryRepo categorias;
    private final AclRuleRepo regras;
    private final AclDomainOverrideRepo overrides;
    private final AclCacheService cache;
    private final GraylogAuditPublisher auditoria;

    private final String semRegra;
    private final String semCategoria;
    private final boolean auditoriaHabilitada;

    /** PASSO A: indice em memoria, reconstruido por TTL e por invalidacao explicita. */
    private volatile Map<String, AclDomainOverride> excecoesExatas = Map.of();
    private volatile List<AclDomainOverride> excecoesCuringa = List.of();
    private volatile long indiceExpiraEm;
    private final long indiceTtlMs;
    private final Object travaIndice = new Object();

    private long permitidas;
    private long negadas;
    private long semIdentidade;
    private long falhasInfra;

    public AclEvaluatorService(AclDomainRepo dominios,
                               AclCategoryRepo categorias,
                               AclRuleRepo regras,
                               AclDomainOverrideRepo overrides,
                               AclCacheService cache,
                               GraylogAuditPublisher auditoria,
                               @Value("${astral.acl.default-without-rule:DENY}") String semRegra,
                               @Value("${astral.acl.default-uncategorized:ALLOW}") String semCategoria,
                               @Value("${astral.acl.audit.enabled:true}") boolean auditoriaHabilitada,
                               @Value("${astral.acl.override-index-ttl:60000}") long indiceTtlMs) {
        this.dominios = dominios;
        this.categorias = categorias;
        this.regras = regras;
        this.overrides = overrides;
        this.cache = cache;
        this.auditoria = auditoria;
        this.semRegra = normalizaPolitica(semRegra, "DENY");
        this.semCategoria = normalizaPolitica(semCategoria, "ALLOW");
        this.auditoriaHabilitada = auditoriaHabilitada;
        this.indiceTtlMs = Math.max(1000, indiceTtlMs);
        this.indiceExpiraEm = 0;
    }

    /**
     * Ponto de entrada unico. Nunca lanca: uma excecao propagada viraria 500 no
     * Nginx e 500 no meio de um auth_request e' erro de pagina, nao de acesso.
     */
    public AclDecision avalia(AclIdentity identidade, String host, String ip) {
        long t0 = System.nanoTime();
        try {
            AclDecision d = decide(identidade, host, ip, t0);
            contabiliza(d);
            audita(identidade, host, ip, d);
            return d;
        } catch (Exception e) {
            // Infraestrutura fora (banco, cache, indice): recusa e conta. Se um
            // dia isso passar de ~1% do trafego, o alarme certo e' este.
            falhasInfra++;
            if (falhasInfra % 50 == 1) {
                log.error("acl: falha de infraestrutura na avaliacao de {} ({}) -- respondendo 401, nunca 200",
                        host, e.getMessage(), e);
            }
            AclDecision d = AclDecision.erro("INFRA", "infraestrutura indisponivel: " + e.getMessage(), ms(t0));
            contabiliza(d);
            return d;
        }
    }

    private AclDecision decide(AclIdentity identidade, String host, String ip, long t0) {
        if (identidade == null || identidade.usuario().isBlank()) {
            return AclDecision.unauth("IDENTIDADE", "sem identidade autenticada no pedido", ms(t0));
        }
        String dominio = AclDomain.normaliza(host);
        if (dominio.isEmpty()) {
            // Host ausente/vazio: pedido malformado. Fechado por padrao.
            return AclDecision.deny(null, null, "FORMA", "host ausente no X-Original-Host", ms(t0));
        }

        // ---- PASSO A: excecao explicita (indice em memoria) ------------------
        Optional<AclDomainOverride> excecao = excecao(dominio, identidade);
        if (excecao.isPresent()) {
            AclDomainOverride o = excecao.get();
            if (AclDomainOverride.BYPASS.equalsIgnoreCase(o.action)) {
                // BYPASS pula o motor inteiro, inclusive o cache: e' a
                // excecao de manutencao, precisa valer ja' na proxima requisicao.
                return AclDecision.allow(null, null, "PASSO_A/bypass", "excecao BYPASS: " + o.reason, ms(t0));
            }
            if (AclDomainOverride.ALLOW.equalsIgnoreCase(o.action)) {
                return AclDecision.allow(null, null, "PASSO_A/allow", "excecao ALLOW: " + o.reason, ms(t0));
            }
            return AclDecision.deny(null, null, "PASSO_A/deny", "excecao DENY: " + o.reason, ms(t0));
        }

        // ---- Cache de decisao ------------------------------------------------
        String chaveCache = dominio + "|" + identidade.chaveGrupos();
        Optional<String> cacheado = cache.decisao(chaveCache);
        if (cacheado.isPresent()) {
            String[] p = cacheado.get().split("\\|", -1);
            if (p.length == 3) {
                String categoria = "-".equals(p[1]) ? null : p[1];
                String grupo = "-".equals(p[2]) ? null : p[2];
                if (AclDecision.ALLOW.equals(p[0])) {
                    return AclDecision.allow(categoria, grupo, "CACHE", "decisao em cache", ms(t0));
                }
                return AclDecision.deny(categoria, grupo, "CACHE", "decisao em cache", ms(t0));
            }
        }

        // ---- PASSO B: categoria do dominio -----------------------------------
        // Se o PostgreSQL estiver fora, esta linha lanca e o catch de cima
        // responde 401: sem categoria nao ha o que decidir, e 200 seria
        // liberar por falta de dado.
        CategoriaResolvida cat = categoria(dominio);

        if (cat.vazia()) {
            boolean permite = "ALLOW".equalsIgnoreCase(semCategoria);
            AclDecision d = permite
                    ? AclDecision.allow(null, null, "PADRAO/sem-categoria",
                        "dominio sem categoria; default-uncategorized=ALLOW", ms(t0))
                    : AclDecision.deny(null, null, "PADRAO/sem-categoria",
                        "dominio sem categoria; default-uncategorized=DENY", ms(t0));
            gravaCache(chaveCache, d);
            return d;
        }

        // ---- PASSO C: regra categoria x grupo --------------------------------
        AclRule regra = regra(cat, identidade);
        if (regra != null) {
            if (regra.permite()) {
                AclDecision d = AclDecision.allow(cat.codigo(), regra.adGroup, "PASSO_C/regra",
                        "regra prioridade " + regra.priority, ms(t0));
                gravaCache(chaveCache, d);
                return d;
            }
            AclDecision d = AclDecision.deny(cat.codigo(), regra.adGroup, "PASSO_C/regra",
                    "regra prioridade " + regra.priority, ms(t0));
            gravaCache(chaveCache, d);
            return d;
        }

        // ---- Sem regra: a politica decide, nao o codigo ----------------------
        boolean permite;
        String origem;
        String motivo;
        if ("ALLOW".equalsIgnoreCase(semRegra)) {
            permite = true;
            origem = "PADRAO/sem-regra";
            motivo = "nenhuma regra para " + cat.codigo() + "; default-without-rule=ALLOW";
        } else if ("BY_CATEGORY".equalsIgnoreCase(semRegra)) {
            permite = !cat.bloqueadaPorPadrao();
            origem = "PADRAO/por-categoria";
            motivo = "nenhuma regra; categoria " + cat.codigo()
                    + (cat.bloqueadaPorPadrao() ? " e' bloqueada por padrao" : " e' liberada por padrao");
        } else {
            permite = false;
            origem = "PADRAO/sem-regra";
            motivo = "nenhuma regra para " + cat.codigo() + "; default-without-rule=DENY";
        }
        AclDecision d = permite
                ? AclDecision.allow(cat.codigo(), null, origem, motivo, ms(t0))
                : AclDecision.deny(cat.codigo(), null, origem, motivo, ms(t0));
        gravaCache(chaveCache, d);
        return d;
    }

    // ---------------------------------------------------------------- PASSO A

    private Optional<AclDomainOverride> excecao(String dominio, AclIdentity identidade) {
        talvezReconstruaIndice();
        AclDomainOverride exata = excecoesExatas.get(dominio);
        if (exata != null && cobre(exata, identidade)) return Optional.of(exata);
        for (AclDomainOverride o : excecoesCuringa) {
            if (AclDomain.casa(o.pattern, dominio) && cobre(o, identidade)) return Optional.of(o);
        }
        return Optional.empty();
    }

    /**
     * A excecao cobre o usuario se valer para todos ({@code adGroup} vazio ou
     * {@code *}) ou se valer para QUALQUER um dos seus grupos. "Qualquer um" e'
     * de proposito: o AD devolve todos os grupos do usuario, e exigir que todos
     * casassem transformaria uma excecao concedida em excecao que nunca vale.
     */
    private boolean cobre(AclDomainOverride o, AclIdentity identidade) {
        if (!o.enabled) return false;
        if (o.adGroup == null || o.adGroup.isBlank() || "*".equals(o.adGroup)) return true;
        String alvo = o.adGroup.toUpperCase(Locale.ROOT);
        for (String g : identidade.gruposNormalizados()) {
            if (alvo.equals(g)) return true;
        }
        return false;
    }

    /**
     * Reconstruo o indice quando expira. Se o banco falhar, mantem o indice
     * anterior: indice velho decide, indice vazio nao decide nada -- e a
     * alternativa (deixar tudo sem excecao) liberaria hosts que o operador
     * tinha bloqueado.
     */
    private void talvezReconstruaIndice() {
        if (System.currentTimeMillis() < indiceExpiraEm) return;
        synchronized (travaIndice) {
            if (System.currentTimeMillis() < indiceExpiraEm) return;
            try {
                reconstruaIndice();
            } catch (Exception e) {
                // Mantem o que ja' esta' em memoria e tenta de novo no proximo acesso.
                indiceExpiraEm = System.currentTimeMillis() + indiceTtlMs;
                log.warn("acl: indice de excecoes nao atualizado ({}); mantendo o anterior", e.getMessage());
            }
        }
    }

    /** Reconstruicao explicita -- chamada pelo CRUD apos qualquer escrita. */
    public void invalidaIndice() {
        synchronized (travaIndice) {
            indiceExpiraEm = 0;
        }
    }

    @Transactional(readOnly = true)
    protected void reconstruaIndice() {
        List<AclDomainOverride> todas = overrides.findAllByEnabledTrueOrderByPatternAsc();
        Map<String, AclDomainOverride> exatas = new LinkedHashMap<>();
        List<AclDomainOverride> curingas = new ArrayList<>();
        for (AclDomainOverride o : todas) {
            String p = AclDomain.normaliza(o.pattern);
            if (p.startsWith("*.")) curingas.add(o);
            else exatas.put(p, o);
        }
        curingas.sort(Comparator.comparingInt((AclDomainOverride o) -> AclDomain.normaliza(o.pattern).length()).reversed());
        this.excecoesExatas = Map.copyOf(exatas);
        this.excecoesCuringa = List.copyOf(curingas);
        this.indiceExpiraEm = System.currentTimeMillis() + indiceTtlMs;
        log.debug("acl: indice de excecoes reconstruido ({} exatas, {} curinga)", exatas.size(), curingas.size());
    }

    // ---------------------------------------------------------------- PASSO B

    private CategoriaResolvida categoria(String dominio) {
        // Cache primeiro: a classe de dominio muda devagar e o hit evita a
        // consulta de candidatos a cada requisicao do proxy.
        Optional<String> c = cache.categoria(dominio);
        if (c.isPresent()) {
            if (SEM_CATEGORIA.equals(c.get())) return CategoriaResolvida.nenhuma();
            return porCodigo(c.get());
        }

        String codigo = null;
        Long id = null;
        boolean bloqueada = false;
        for (AclDomain d : dominios.candidatos(dominio)) {
            if (AclDomain.casa(d.pattern, dominio)) {
                codigo = d.category.code;
                id = d.category.id;
                bloqueada = d.category.blockedByDefault;
                break;
            }
        }
        if (codigo == null) {
            // Cache negativo: sem esta linha um dominio sem categoria consultaria
            // o banco a cada pagina carregada.
            cache.gravaCategoria(dominio, SEM_CATEGORIA);
            return CategoriaResolvida.nenhuma();
        }
        cache.gravaCategoria(dominio, codigo);
        return new CategoriaResolvida(codigo, id, bloqueada);
    }

    private CategoriaResolvida porCodigo(String codigo) {
        Optional<AclCategory> cat = categorias.findByCode(codigo);
        if (cat.isEmpty()) {
            // Categoria sumiu do banco depois de cacheada: nao e' decisao, e'
            // dado inconsistente. Trata como "sem categoria" e deixa o padrao
            // decidir, que e' exatamente o que aconteceria sem a linha.
            return CategoriaResolvida.nenhuma();
        }
        return new CategoriaResolvida(cat.get().code, cat.get().id, cat.get().blockedByDefault);
    }

    // ---------------------------------------------------------------- PASSO C

    private AclRule regra(CategoriaResolvida cat, AclIdentity identidade) {
        Optional<String> acao = cache.acaoRegra(cat.codigo(), identidade.chaveGrupos());
        if (acao.isPresent()) {
            if (AclRule.ALLOW.equals(acao.get())) return regraVirtual(AclRule.ALLOW);
            if (AclRule.DENY.equals(acao.get())) return regraVirtual(AclRule.DENY);
        }

        List<String> grupos = identidade.gruposNormalizados();
        if (grupos.isEmpty()) grupos = List.of(GRUPO_IMPOSSIVEL);
        List<AclRule> candidatas = regras.candidatas(cat.id(), grupos);
        if (candidatas.isEmpty()) return null;

        AclRule vencedora = candidatas.get(0);
        // O curinga ja' entrou na query; se ele venceu por prioridade, ele e'
        // a resposta. Gravamos a acao para o proximo hit nao repetir a consulta.
        cache.gravaAcaoRegra(cat.codigo(), identidade.chaveGrupos(), vencedora.action);
        return vencedora;
    }

    /**
     * Regra vinda do cache: o motor so' precisa saber a acao, nao a entidade.
     * Prioridade fica zerada porque nao ha prioridade real para reportar -- e
     * reportar a prioridade de outra regra seria mentira na auditoria.
     */
    private AclRule regraVirtual(String acao) {
        AclRule r = new AclRule();
        r.action = acao;
        r.adGroup = "(cache)";
        r.priority = -1;
        return r;
    }

    // ------------------------------------------------------------- auxiliares

    private void gravaCache(String chave, AclDecision d) {
        String cat = d.categoria() == null ? "-" : d.categoria();
        String grupo = d.grupo() == null ? "-" : d.grupo();
        cache.gravaDecisao(chave, d.action() + "|" + cat + "|" + grupo);
    }

    private void contabiliza(AclDecision d) {
        switch (d.status()) {
            case 200 -> permitidas++;
            case 403 -> negadas++;
            default -> semIdentidade++;
        }
    }

    private void audita(AclIdentity identidade, String host, String ip, AclDecision d) {
        if (!auditoriaHabilitada) return;
        String usuario = identidade == null ? "-" : (identidade.usuario().isBlank() ? "-" : identidade.usuario());
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("dominio", AclDomain.normaliza(host));
        extra.put("status", d.status());
        extra.put("categoria", d.categoria() == null ? "-" : d.categoria());
        extra.put("grupo", d.grupo() == null ? "-" : d.grupo());
        extra.put("origem", d.origem());
        extra.put("motivo", d.motivo());
        extra.put("identidade_fonte", identidade == null ? "-" : identidade.source());
        extra.put("grupos", identidade == null ? "" : identidade.chaveGrupos());
        extra.put("ip", ip == null ? "-" : ip);
        extra.put("latencia_ms", d.ms());
        // Assistincrono de verdade: publicar enfileira e devolve. Se o Graylog
        // nao responder, o evento cai no JSONL -- ele nao some.
        auditoria.audit(usuario, "acl", d.action(), AclDomain.normaliza(host),
                String.valueOf(d.status()), extra);
    }

    private int ms(long t0) {
        return (int) ((System.nanoTime() - t0) / 1_000_000);
    }

    private String normalizaPolitica(String valor, String padrao) {
        if (valor == null || valor.isBlank()) return padrao;
        String v = valor.trim().toUpperCase(Locale.ROOT);
        if (v.equals("ALLOW") || v.equals("DENY") || v.equals("BY_CATEGORY")) return v;
        log.warn("acl: politica '{}' invalida; usando {}", valor, padrao);
        return padrao;
    }

    /** Contadores para o endpoint de status e para o dashboard. */
    public Map<String, Object> metricas() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("permitidas", permitidas);
        m.put("negadas", negadas);
        m.put("semIdentidade", semIdentidade);
        m.put("falhasInfra", falhasInfra);
        m.put("excecoesExatas", excecoesExatas.size());
        m.put("excecoesCuringa", excecoesCuringa.size());
        m.put("indiceExpiraEm", indiceExpiraEm);
        m.put("cache", cache.estado());
        return m;
    }

    private record CategoriaResolvida(String codigo, Long id, boolean bloqueadaPorPadrao) {
        static CategoriaResolvida nenhuma() {
            return new CategoriaResolvida(null, null, false);
        }

        boolean vazia() {
            return codigo == null;
        }
    }
}
