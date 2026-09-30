package com.astral.firewall.model;
import jakarta.persistence.*;
@Entity public class RateLimitPolicy { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id; public String port; public String protocol="TCP"; public int ratePerSecond=20; public boolean enabled=true; }
