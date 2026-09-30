package com.astral.firewall.model;
import jakarta.persistence.*;
@Entity public class Schedule { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id; public String name; public String daysOfWeek="SEG,TER,QUA,QUI,SEX"; public java.time.LocalTime startTime=java.time.LocalTime.of(8,0); public java.time.LocalTime endTime=java.time.LocalTime.of(18,0); }
