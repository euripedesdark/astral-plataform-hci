package com.astral.firewall.model;
import jakarta.persistence.*; import java.time.Instant;
@Entity public class FirewallRule {
@Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
public String chain="INPUT"; public int priority; public String protocol="TCP";
public String srcCidr=""; public String dstCidr=""; public String port="";
public String iface=""; public String action="ACCEPT"; public boolean enabled=true;
@Column(length=512) public String comment="";
@Column(length=1024) public String rawRule="";
public String bytes="0"; public String packets="0";
public Instant appliedAt;
}
