package com.astral.firewall.model;
import jakarta.persistence.*;
@Entity public class PortForward {
@Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
public String iface; public String externalPort; public String protocol="TCP";
public String internalIp; public String internalPort; public boolean enabled=true;
@Column(length=512) public String description="";
public String bytes="0"; public String packets="0";
}
