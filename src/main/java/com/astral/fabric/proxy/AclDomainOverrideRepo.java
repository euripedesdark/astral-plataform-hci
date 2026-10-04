package com.astral.fabric.proxy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AclDomainOverrideRepo extends JpaRepository<AclDomainOverride, Long> {

    List<AclDomainOverride> findAllByEnabledTrueOrderByPatternAsc();

    /**
     * Regras que podem casar com o dominio requisitado. O filtro grossao
     * (contem '*') esta aqui para o PASSO A nao precisar carregar as
     * excecoes exatas tambem -- ele so' precisa das que tem curinga.
     */
    @Query("""
           SELECT o FROM AclDomainOverride o
           WHERE o.enabled = true AND o.pattern LIKE '%*%'
           ORDER BY LENGTH(o.pattern) DESC
           """)
    List<AclDomainOverride> comCuringa();
}
