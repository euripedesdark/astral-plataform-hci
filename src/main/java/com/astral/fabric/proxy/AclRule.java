package com.astral.fabric.proxy;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Regra do PASSO C: categoria x grupo do AD -> decisao.
 *
 * <p>A unicidade e' {@code (category, adGroup)} de proposito: nao pode existir
 * duas regras concorrentes para o mesmo par, senao a ordem de leitura decide
 * o acesso -- e ordem de leitura nao e' politica de seguranca.
 */
@Entity
@Table(name = "acl_rule",
        uniqueConstraints = @UniqueConstraint(name = "uq_acl_rule_cat_grupo",
                columnNames = {"category_id", "ad_group"}),
        indexes = @Index(name = "ix_acl_rule_enabled", columnList = "enabled"))
public class AclRule {

    public static final String ALLOW = "ALLOW";
    public static final String DENY = "DENY";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    public AclCategory category;

    /**
     * Grupo do Samba AD. O curinga {@code *} significa "qualquer usuario
     * autenticado" -- e' a linha que permite uma politica padrao sem repetir
     * o mesmo valor em todas as categorias.
     */
    @Column(name = "ad_group", nullable = false, length = 128)
    public String adGroup;

    @Column(nullable = false, length = 16)
    public String action = DENY;

    /** Menor numero primeiro. Empate e' erro de configuracao, nao desempate. */
    @Column(nullable = false)
    public int priority = 100;

    @Column(nullable = false)
    public boolean enabled = true;

    @Column(length = 255)
    public String justification;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    public AclRule() {
    }

    public AclRule(AclCategory category, String adGroup, String action, int priority) {
        this.category = category;
        this.adGroup = adGroup;
        this.action = action;
        this.priority = priority;
    }

    public boolean permite() {
        return ALLOW.equalsIgnoreCase(action);
    }
}
