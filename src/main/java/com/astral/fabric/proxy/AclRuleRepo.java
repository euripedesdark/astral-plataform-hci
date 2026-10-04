package com.astral.fabric.proxy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AclRuleRepo extends JpaRepository<AclRule, Long> {

    Optional<AclRule> findByCategoryIdAndAdGroupIgnoreCase(Long categoryId, String adGroup);

    List<AclRule> findAllByOrderByPriorityAsc();

    /**
     * Regras candidatas para uma categoria: o grupo exato, o grupo na caixa
     * certa do AD e o curinga {@code *}, todos ja' ordenados por prioridade.
     *
     * <p>O cruzamento categoria x grupo acontece AQUI de proposito: trazer tudo
     * e filtrar no Java trocaria uma consulta indexada por um scan da tabela
     * inteira a cada requisicao do auth_request do Nginx.
     */
    @Query("""
           SELECT r FROM AclRule r
           WHERE r.enabled = true
             AND r.category.id = :categoryId
             AND (UPPER(r.adGroup) IN :grupos OR r.adGroup = '*')
           ORDER BY r.priority ASC, r.id ASC
           """)
    List<AclRule> candidatas(@Param("categoryId") Long categoryId,
                             @Param("grupos") List<String> grupos);
}
