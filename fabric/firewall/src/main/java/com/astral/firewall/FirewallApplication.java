package com.astral.firewall;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import com.astral.firewall.service.IptablesService;
@SpringBootApplication @EnableScheduling
public class FirewallApplication {
private final IptablesService ipt; public FirewallApplication(IptablesService i){ipt=i;}
public static void main(String[] a){ SpringApplication.run(FirewallApplication.class,a); }
@EventListener(ApplicationReadyEvent.class) public void init(){ ipt.syncGroupsFromDb(); ipt.syncProtectionsFromDb(); ipt.syncFromRuntime(); ipt.syncNatFromDb(); ipt.syncZonesFromDb(); }
}
