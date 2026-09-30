package com.astral.firewall.model;
import jakarta.persistence.*; import java.time.Instant;
@Entity public class FirewallSnapshot {
@Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
public Instant createdAt=Instant.now(); public String label;
@Lob @Column(columnDefinition="text") public String dumpText;
public FirewallSnapshot(){} public FirewallSnapshot(String l,String d){label=l;dumpText=d;}
}
