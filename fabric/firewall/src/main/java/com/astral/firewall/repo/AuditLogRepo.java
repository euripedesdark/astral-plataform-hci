package com.astral.firewall.repo;
import com.astral.firewall.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface AuditLogRepo extends JpaRepository<AuditLog,Long> { java.util.List<AuditLog> findTop200ByOrderByTimestampDesc(); }
