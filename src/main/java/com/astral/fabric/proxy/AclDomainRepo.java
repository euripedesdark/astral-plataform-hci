package com.astral.fabric.proxy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AclDomainRepo extends JpaRepository<AclDomain, Long> {

    Optional<AclDomain> findByPattern(String pattern);

    /**
     * Candidatos a match no PASSO B: o padrao exato primeiro, depois os curingas
     * ordenados do mais especifico (mais rotulos) para o menos especifico.
     *
     * <p>Sem esta ordem, um {@code *.br} mau-carregado antes de um
     * {@code *.globo.com.br} passaria a decidir sozinho.
     */
    @Query("""
           SELECT d FROM AclDomain d
           ORDER BY CASE WHEN d.pattern = :dominio THEN 0
                         WHEN d.pattern LIKE '%*%' THEN 1
                         ELSE 2 END,
                    LENGTH(d.pattern) DESC
           """)
    List<AclDomain> candidatos(@Param("dominio") String dominio);

    List<AclDomain> findAllByOrderByPatternAsc();

    long countByCategoryId(Long categoryId);
}
