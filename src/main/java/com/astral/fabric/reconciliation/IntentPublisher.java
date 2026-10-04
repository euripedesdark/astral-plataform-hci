package com.astral.fabric.reconciliation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/**
 * Publica a intencao na fila.
 *
 * <p>O unico caminho de entrada da reconciliacao e' a fila. A API nao pode
 * chamar {@code ReconciliationEngine.processa} direto: fariamos da mesma
 * requisicao HTTP o dono de uma mudanca de infraestrutura, e a aplicacao
 * reiniciada no meio deixaria a mudanca sem dono. Com a fila duravel, a
 * intencao sobrevive ao processo que a recebeu.
 */
@Service
public class IntentPublisher {

    private static final Logger log = LoggerFactory.getLogger(IntentPublisher.class);

    private final RabbitTemplate rabbit;
    private final IntentRepo repo;
    private final ObjectMapper json;

    public IntentPublisher(RabbitTemplate rabbit, IntentRepo repo, ObjectMapper json) {
        this.rabbit = rabbit;
        this.repo = repo;
        this.json = json;
    }

    /**
     * Persiste primeiro e publica depois. A ordem importa: se a publicacao
     * falhar, a intencao existe no banco como RECEIVED e pode ser republicada;
     * na ordem inversa, uma intencao publicada sem registro nao tem historico.
     */
    public Intent publica(ReconcileModule modulo, String type, String payload, String who) {
        Intent intent = Intent.nova(modulo, type, payload, who);
        repo.save(intent);
        try {
            String mensagem = json.writeValueAsString(intent);
            rabbit.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.ROUTING_PADRAO, mensagem);
            log.info("intencao {} enviada para a fila ({}/{})", intent.getId(), modulo, type);
        } catch (JsonProcessingException | RuntimeException e) {
            intent.setError("Publicacao na fila falhou: " + e.getMessage());
            intent.transicao(IntentStatus.FAILED);
            repo.save(intent);
            throw new IllegalStateException("Intencao " + intent.getId()
                    + " registrada mas nao publicada: " + e.getMessage(), e);
        }
        return intent;
    }
}
