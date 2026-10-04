package com.astral.fabric.firewall.model;
import jakarta.persistence.*;
@Entity public class PortGroup { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id; public String name; @ElementCollection(fetch=FetchType.EAGER) public java.util.List<String> ports=new java.util.ArrayList<>(); }
