package com.astral.fabric.firewall;

import com.astral.fabric.firewall.service.IptablesService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Modulo de firewall da Astral.
 *
 * <p>Antes da v1.4 isto era um aplicativo Spring Boot proprio
 * ({@code fabric/firewall}, porta 8040, com {@code @SpringBootApplication} e
 * {@code main}). A reforma de codigo legado da v1.4 moveu o codigo para
 * {@code com.astral.fabric.firewall} DENTRO da plataforma: o firewall agora e'
 * um modulo da mesma aplicacao, nao um processo separado, e a porta 8040
 * deixou de existir.
 *
 * <p>Consequencia de projeto, e' deliberada: com um so' processo ha um so'
 * dono do schema (Flyway no {@code astral-platform}) e uma so' trilha de
 * auditoria. Dois processos migrando o mesmo banco competem pela tabela de
 * historico do Flyway -- era exatamente o que o application.properties do
 * modulo solto avisava.
 *
 * <p>A sincronizacao de estado continua acontecendo uma unica vez, no
 * {@link ApplicationReadyEvent}, na mesma ordem de antes: grupos primeiro
 * (ipset), depois protecoes, depois runtime, depois NAT e por fim zonas.
 */
@Configuration
@EnableScheduling
public class FirewallModule {

    private final IptablesService iptables;

    public FirewallModule(IptablesService iptables) {
        this.iptables = iptables;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void sincronizaEstadoInicial() {
        iptables.syncGroupsFromDb();
        iptables.syncProtectionsFromDb();
        iptables.syncFromRuntime();
        iptables.syncNatFromDb();
        iptables.syncZonesFromDb();
    }
}
