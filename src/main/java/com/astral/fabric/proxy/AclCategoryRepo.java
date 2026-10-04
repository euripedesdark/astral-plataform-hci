package com.astral.fabric.proxy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AclCategoryRepo extends JpaRepository<AclCategory, Long> {
    Optional<AclCategory> findByCode(String code);
}
