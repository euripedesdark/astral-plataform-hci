package com.astral.firewall.repo;
import com.astral.firewall.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface FirewallSnapshotRepo extends JpaRepository<FirewallSnapshot,Long> { java.util.List<FirewallSnapshot> findAllByOrderByCreatedAtDesc(); }
