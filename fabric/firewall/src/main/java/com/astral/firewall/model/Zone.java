package com.astral.firewall.model;
import jakarta.persistence.*;
@Entity public class Zone { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id; public String name; public String trustLevel="LAN"; public String defaultPolicy="ACCEPT"; public String color="#57e389"; }
