package com.astral.fabric.firewall.model;
import jakarta.persistence.*;
@Entity public class ThreatList { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id; public String name; public String sourceUrl; public java.time.Instant lastUpdated; public int ipCount; public boolean enabled=true; }
