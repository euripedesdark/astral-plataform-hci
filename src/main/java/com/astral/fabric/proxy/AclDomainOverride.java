package com.astral.fabric.proxy;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Excecao explicita de dominio -- o PASSO A, que roda ANTES de tudo.
 *
 * <p>Por que antes: excecao e' quando o operador diz "deste dominio
 * especifico, nao importa a categoria". Se a excecao ficasse depois do
 * cruzamento categoria x grupo, toda excecao teria que ser escrita tambem na
 * categoria, e as duas se divergirem e' so' questao de tempo.
 *
 * <p>{@code adGroup} nulo = vale para todo mundo autenticado.
 */
@Entity
@Table(name = "acl_domain_override",
        indexes = @Index(name = "ix_acl_override_pattern", columnList = "pattern", unique = true))
public class AclDomainOverride {

    public static final String ALLOW = "ALLOW";
    public static final String DENY = "DENY";
    public static final String BYPASS = "BYPASS";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, length = 255)
    public String pattern;

    @Column(name = "ad_group", length = 128)
    public String adGroup;

    /**
     * {@code ALLOW} libera, {@code DENY} bloqueia e {@code BYPASS} pula o motor
     * inteiro (cache tambem). BYPASS e' o "excecao de manutencao": serve para
     * destravar um dominio sem que a decisao passe pelo cache stale.
     */
    @Column(nullable = false, length = 16)
    public String action = ALLOW;

    @Column(length = 255)
    public String reason;

    @Column(nullable = false)
    public boolean enabled = true;

    @Column(length = 64)
    public String createdBy;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    public AclDomainOverride() {
    }

    public boolean cobre(String grupo) {
        if (!enabled) return false;
        if (adGroup == null || adGroup.isBlank() || "*".equals(adGroup)) return true;
        return adGroup.equalsIgnoreCase(grupo);
    }
}
