package com.astral.firewall.model;
import jakarta.persistence.*;
@Entity public class AuditLog { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id; public String username; public String entityType; public String entityId; public String action; @Column(length=8192) public String diffJson; public java.time.Instant timestamp=java.time.Instant.now(); }
