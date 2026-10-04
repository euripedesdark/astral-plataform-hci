package com.astral.fabric.firewall.repo;
import com.astral.fabric.firewall.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface AuditLogRepo extends JpaRepository<AuditLog,Long> { java.util.List<AuditLog> findTop200ByOrderByTimestampDesc(); }
