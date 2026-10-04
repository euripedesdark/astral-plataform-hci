package com.astral.fabric.firewall.repo;
import com.astral.fabric.firewall.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface FirewallSnapshotRepo extends JpaRepository<FirewallSnapshot,Long> { java.util.List<FirewallSnapshot> findAllByOrderByCreatedAtDesc(); }
