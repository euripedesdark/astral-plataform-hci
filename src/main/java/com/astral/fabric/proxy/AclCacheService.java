package com.astral.fabric.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Lookup em Redis do motor de ACL -- o "< 2ms" do PASSO B.
 *
 * <p>Tres chaves, tres TTLs, e cada TTL tem motivo proprio:
 *
 * <table>
 *   <tr><th>chave</th><th>TTL</th><th>por que</th></tr>
 *   <tr><td>{@code acl:domain:} </td><td>1h</td><td>categoria de dominio muda devagar; 1h ja'
 *       absorve a maior parte do trafego e nao prende um erro de classificacao por um dia.</td></tr>
 *   <tr><td>{@code acl:rule:}   </td><td>5m</td><td>politica de acesso muda devagar, mas o custo
 *       de servir uma regra velha e' um acesso que nao deveria passar. 5min e' o compromisso.</td></tr>
 *   <tr><td>{@code acl:hit:}    </td><td>15m</td><td>acesso ja' decidido: evita reprocessar o
 *       dominio mais visitado sem travar a politica por horas.</td></tr>
 * </table>
 *
 * <p><b>Fallback sobre dependencia</b>: Redis fora NAO bloqueia navegacao.
 * Cada metodo devolve {@link Optional#empty()} quando o Redis falha, e o
 * chamador cai para PostgreSQL. E' mais lento, e' isso -- mais lento que
 * negar todo mundo porque o cache caiu.
 */
@Service
public class AclCacheService {

    private static final Logger log = LoggerFactory.getLogger(AclCacheService.class);

    private static final String K_DOMINIO = "acl:domain:";
    private static final String K_REGRA = "acl:rule:";
    private static final String K_OVERRIDE = "acl:override:";
    private static final String K_DECISAO = "acl:hit:";
    private static final String NEGATIVO = "__NONE__";

    private final StringRedisTemplate redis;
    private final boolean habilitado;
    private final Duration ttlDominio;
    private final Duration ttlRegra;
    private final Duration ttlDecisao;

    private long acertos;
    private long faltas;
    private long indisponivel;

    public AclCacheService(StringRedisTemplate redis,
                           @Value("${astral.acl.cache.enabled:true}") boolean habilitado,
                           @Value("${astral.acl.cache.ttl-dominio:1h}") Duration ttlDominio,
                           @Value("${astral.acl.cache.ttl-regra:5m}") Duration ttlRegra,
                           @Value("${astral.acl.cache.ttl-decisao:15m}") Duration ttlDecisao) {
        this.redis = redis;
        this.habilitado = habilitado;
        this.ttlDominio = ttlDominio;
        this.ttlRegra = ttlRegra;
        this.ttlDecisao = ttlDecisao;
    }

    public Optional<String> categoria(String dominio) {
        return le(K_DOMINIO + dominio);
    }

    public void gravaCategoria(String dominio, String categoria) {
        grava(K_DOMINIO + dominio, categoria, ttlDominio);
    }

    public Optional<String> acaoRegra(String categoria, String grupo) {
        return le(K_REGRA + categoria + "|" + grupo);
    }

    public void gravaAcaoRegra(String categoria, String grupo, String acao) {
        grava(K_REGRA + categoria + "|" + grupo, acao, ttlRegra);
    }

    /**
     * {@code "ALLOW" | "DENY" | "BYPASS" | vazio}.
     * O vazio e' cache negativo: a ausencia de excecao tambem e' uma resposta,
     * e sem ela um dominio sem regra consultaria o banco a cada requisicao.
     */
    public Optional<String> override(String chave) {
        return le(K_OVERRIDE + chave);
    }

    public void gravaOverride(String chave, String acao) {
        grava(K_OVERRIDE + chave, acao == null || acao.isBlank() ? NEGATIVO : acao, ttlRegra);
    }

    public Optional<String> decisao(String chave) {
        return le(K_DECISAO + chave);
    }

    public void gravaDecisao(String chave, String decisao) {
        grava(K_DECISAO + chave, decisao, ttlDecisao);
    }

    public void invalidaDominio(String dominio) {
        apaga(K_DOMINIO + dominio);
    }

    public void invalidaTudo() {
        try {
            var chaves = redis.keys("acl:*");
            if (chaves != null && !chaves.isEmpty()) redis.delete(chaves);
            log.info("acl cache: {} chaves invalidadas", chaves == null ? 0 : chaves.size());
        } catch (Exception e) {
            log.warn("acl cache: invalidacao total falhou ({})", e.getMessage());
        }
    }

    private Optional<String> le(String chave) {
        if (!habilitado) return Optional.empty();
        try {
            String v = redis.opsForValue().get(chave);
            if (v == null) {
                faltas++;
                return Optional.empty();
            }
            acertos++;
            return NEGATIVO.equals(v) ? Optional.empty() : Optional.of(v);
        } catch (Exception e) {
            indisponivel++;
            if (indisponivel % 100 == 1) {
                log.warn("acl cache: Redis indisponivel ({}), caindo para PostgreSQL. {} falha(s) ate agora",
                        e.getMessage(), indisponivel);
            }
            return Optional.empty();
        }
    }

    private void grava(String chave, String valor, Duration ttl) {
        if (!habilitado) return;
        try {
            redis.opsForValue().set(chave, valor, ttl);
        } catch (Exception e) {
            // escrever cache nunca pode derrubar a decisao
            log.debug("acl cache: escrita falhou em {} ({})", chave, e.getMessage());
        }
    }

    private void apaga(String chave) {
        try {
            redis.delete(chave);
        } catch (Exception ignorada) {
            // TTL cuida disso se o Redis voltar
        }
    }

    public String estado() {
        return "hit=" + acertos + " miss=" + faltas + " down=" + indisponivel
                + " habilitado=" + habilitado;
    }

    public long indisponivel() {
        return indisponivel;
    }
}
