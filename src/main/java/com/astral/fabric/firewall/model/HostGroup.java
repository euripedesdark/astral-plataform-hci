package com.astral.fabric.firewall.model;
import jakarta.persistence.*;
@Entity public class HostGroup { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id; public String name; @ElementCollection(fetch=FetchType.EAGER) public java.util.List<String> cidrs=new java.util.ArrayList<>(); }
