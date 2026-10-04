package com.astral.fabric.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Beans de infraestrutura compartilhados pelos modulos de {@code fabric}.
 *
 * <p>Existe porque {@link RollbackStore} e' uma classe de dominio, nao um
 * componente: ela nao tem anotacao nenhuma de Spring (e' instanciada direto
 * em teste, com o diretorio escolhido no teste). Quem precisa dela em producao
 * sao os modulos de reconciliacao -- {@code NetworkReconcileModule} e
 * {@code FirewallReconcileModule} -- e sem este {@code @Bean} o contexto nem
 * sobe: o erro que aparece e' "No qualifying bean of type RollbackStore",
 * apontando para o modulo errado.
 *
 * <p>Diretorio configuravel por {@code astral.reconciliation.rollback-dir}
 * (vazio = o padrao de {@link RollbackStore#padrao}). O padrao fica em
 * {@code ${java.io.tmpdir}/astral/rollback}, que sobrevive ao crash do
 * processo -- mas o servico roda com {@code PrivateTmp=true}, e ai' o diretorio
 * e' da instancia: some quando o systemd para a unidade. Para snapshot que
 * precisa atravessar reinicio, aponte para /var/lib/astral/rollback.
 */
@Configuration
public class FabricSupportConfig {

    @Bean
    RollbackStore rollbackStore(ObjectMapper json,
                                @Value("${astral.reconciliation.rollback-dir:}") String diretorio) {
        if (diretorio == null || diretorio.isBlank()) {
            return RollbackStore.padrao(json);
        }
        return new RollbackStore(Path.of(diretorio), json);
    }
}
