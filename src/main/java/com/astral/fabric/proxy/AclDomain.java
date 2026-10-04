package com.astral.fabric.proxy;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Dominio (ou padrao de dominio) ligado a uma categoria.
 *
 * <p>{@code pattern} aceita tres formas, e' deliberado:
 * <ul>
 *   <li>{@code facebook.com}   -- exato, e' o que a maioria das regras quer;</li>
 *   <li>{@code *.facebook.com} -- subdominios, porque www/api/m connect nao
 *       sao o mesmo host mas sao o mesmo dono;</li>
 *   <li>{@code *.gov.br}       -- TLD organizacional inteiro.</li>
 * </ul>
 *
 * <p>Sem curinga implicito: {@code facebook.com} NAO casa com
 * {@code www.facebook.com}. Curinga que aparece so' quando alguem espera e'
 * como o filtro deixa de funcionar apos uma atualizacao de CDN.
 */
@Entity
@Table(name = "acl_domain",
        indexes = @Index(name = "ix_acl_domain_pattern", columnList = "pattern", unique = true))
public class AclDomain {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, length = 255)
    public String pattern;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    public AclCategory category;

    /** de onde veio: manual, import, auto. Auditoria pergunta isso sempre. */
    @Column(nullable = false, length = 32)
    public String source = "manual";

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    public AclDomain() {
    }

    public AclDomain(String pattern, AclCategory category, String source) {
        this.pattern = pattern;
        this.category = category;
        this.source = source;
    }

    /**
     * Normaliza para a forma canonica: minusculas, sem ponto final, sem
     * {@code www.} inicial so' quando o padrao nao foi escrito assim.
     * A comparacao sempre acontece sobre isto, nunca sobre o texto cru.
     */
    public static String normaliza(String dominio) {
        if (dominio == null) return "";
        String d = dominio.trim().toLowerCase(java.util.Locale.ROOT);
        while (d.startsWith(".")) d = d.substring(1);
        while (d.endsWith(".")) d = d.substring(0, d.length() - 1);
        return d;
    }

    /** Match exato ou por curinga. Curinga so' casa na fronteira de rotulo. */
    public static boolean casa(String padrao, String dominio) {
        String p = normaliza(padrao);
        String d = normaliza(dominio);
        if (p.isEmpty() || d.isEmpty()) return false;
        if (!p.startsWith("*.")) return p.equals(d);
        String base = p.substring(2);
        return d.equals(base) || d.endsWith("." + base);
    }
}
