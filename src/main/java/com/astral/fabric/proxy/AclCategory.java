package com.astral.fabric.proxy;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Categoria de conteudo. E' o vocabulario do PASSO B: o dominio requisitado e'
 * traduzido para uma categoria, e so' depois a categoria e' cruzada com os
 * grupos do AD no PASSO C.
 *
 * <p>O seeding (automatico, na subida da aplicacao) garante que este
 * vocabulario exista ANTES de qualquer avaliacao: um motor que classifica com
 * a tabela vazia bloqueia tudo e parece um firewall quebrado.
 */
@Entity
@Table(name = "acl_category",
        indexes = @Index(name = "ix_acl_category_code", columnList = "code", unique = true))
public class AclCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** Chave estavel e' o codigo, nao o id: e' o que vai para Redis e para o log. */
    @Column(nullable = false, length = 64)
    public String code;

    @Column(nullable = false, length = 128)
    public String name;

    @Column(length = 512)
    public String description;

    /**
     * Categoria bloqueada para quem nao tiver regra explicita.
     * {@code false} = permitido por padrao; {@code true} = negado por padrao.
     */
    @Column(nullable = false)
    public boolean blockedByDefault;

    @Column(nullable = false, length = 32)
    public String source = "seed";

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    public AclCategory() {
    }

    public AclCategory(String code, String name, String description, boolean blockedByDefault) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.blockedByDefault = blockedByDefault;
    }
}
