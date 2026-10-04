package com.astral.fabric.firewall.model;
import jakarta.persistence.*;
@Entity public class MasqueradeRule { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id; public String iface; public boolean enabled=true; public String bytes="0"; public String packets="0"; }
