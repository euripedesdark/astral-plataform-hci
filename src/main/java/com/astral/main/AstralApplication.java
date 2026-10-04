package com.astral.main;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Entrada da Astral Platform.
 *
 * <p>{@code @SpringBootApplication} sem nada escaneia o pacote ONDE ELE ESTA:
 * {@code com.astral.main}. Isto e' o que fez a v1.4 ficar com codigo compilado
 * e morto: {@code com.astral.fabric.*} (firewall, network, proxy/ACL,
 * reconciliation) passou a morar dentro do mesmo artefato, mas nenhum bean
 * dele era registrado -- o {@code AclCheckController} devolvia 404, o
 * ReconciliationEngine nao consumia fila, e o Hibernate nem reclamava porque
 * as entidades tambem ficavam de fora do {@code PersistenceUnitInfo}. Tudo
 * compilava, o {@code mvn package} passava, e o sistema simplesmente nao
 * existia em producao.
 *
 * <p>Por isso o scan e' expllicito e cobre {@code com.astral} inteiro:
 *
 * <ul>
 *   <li>{@code com.astral.main} -- portal, autenticacao, SecurityConfig;</li>
 *   <li>{@code com.astral.fabric} -- os modulos da v1.4 (Task 1), incluindo o
 *       {@link com.astral.fabric.firewall.FirewallModule}, que sincroniza o
 *       iptables no {@code ApplicationReadyEvent};</li>
 *   <li>{@code com.astral.tools} -- nao registra bean algum (as anotacoes
 *       Spring la' estao dentro de text block que geram codigo de instalador),
 *       mas entrar no scan deixa isso explicito em vez de implícito.</li>
 * </ul>
 *
 * <p>{@code com.astral.fabric.firewall.FirewallModule} e' {@code @Configuration}
 * simples, nao uma segunda aplicacao: a v1.4 aposentou a porta 8040 e o
 * processo solto do astral-firewall, de proposito -- dois processos migrando o
 * mesmo banco brigam pela tabela do Flyway.
 *
 * <p>Três anotações, não uma: {@code scanBasePackages} move o scan de beans,
 * mas NÃO move o scan de entidades nem de repositórios -- esses seguem o
 * pacote da classe anotada com {@code @SpringBootApplication} até que
 * {@code @EntityScan} e {@code @EnableJpaRepositories} digam o contrário. Sem
 * as três, o sintoma é pior do que um 404: o {@code AclCategoryRepo} não
 * existe como bean e o contexto nem sobe, com um erro que aponta para o
 * {@code AclSeeder} e não para a causa.
 */
@SpringBootApplication(scanBasePackages = "com.astral")
@EntityScan(basePackages = "com.astral")
@EnableJpaRepositories(basePackages = "com.astral")
public class AstralApplication {
    public static void main(String[] args) {
        SpringApplication.run(AstralApplication.class, args);
    }
}
