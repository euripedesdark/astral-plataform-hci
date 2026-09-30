package com.astral.firewall.model;
import jakarta.persistence.*;
@Entity public class AutoBanRule { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id; public int maxAttempts=5; public int windowMinutes=5; public int banMinutes=30; public String targetPort="22"; public boolean enabled=true; }
