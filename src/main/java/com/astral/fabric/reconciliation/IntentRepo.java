package com.astral.fabric.reconciliation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IntentRepo extends JpaRepository<Intent, String> {
    List<Intent> findTop50ByOrderByCreatedAtDesc();
    List<Intent> findByModuleOrderByCreatedAtDesc(ReconcileModule module);
}
