package com.astral.firewall.model;
import jakarta.persistence.*;
@Entity public class ZoneInterface { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id; public Long zoneId; public String ifaceName; }
