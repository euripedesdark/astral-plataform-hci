package com.astral.fabric.reconciliation;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Topologia RabbitMQ da intencao declarativa.
 *
 * <p>Fila duravel com DLQ. Duravel porque uma intencao que some no broker e'
 * uma decisao de infraestrutura perdida sem registro; DLQ porque uma mensagem
 * que derruba o consumidor tem que continuar existindo para ser olhada, nao
 * ser reprocessada para sempre.
 *
 * <p>Trocar a topologia aqui e' trocar o contrato de toda a plataforma: a
 * fila e' o barramento entre a API que recebe a intencao e o motor que aplica.
 */
@Configuration
public class RabbitConfig {

    public static final String EXCHANGE = "astral.intent";
    public static final String QUEUE_PADRAO = "astral.intents";
    public static final String DLQ_PADRAO = "astral.intents.dlq";
    public static final String ROUTING_PADRAO = "intent";

    @Bean
    Queue intentsQueue() {
        return QueueBuilder.durable(QUEUE_PADRAO)
                .withArgument("x-dead-letter-exchange", EXCHANGE)
                .withArgument("x-dead-letter-routing-key", "intent.dlq")
                .build();
    }

    @Bean
    Queue intentsDlq() {
        return QueueBuilder.durable(DLQ_PADRAO).build();
    }

    @Bean
    DirectExchange intentExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    Binding intentsBinding() {
        // .to(troca).with(chave): a forma encadeada .to(troca).to(chave) nao
        // existe na API do BindingBuilder e so falharia na subida.
        return BindingBuilder.bind(intentsQueue()).to(intentExchange()).with(ROUTING_PADRAO);
    }

    @Bean
    Binding intentsDlqBinding() {
        return BindingBuilder.bind(intentsDlq()).to(intentExchange()).with("intent.dlq");
    }

    /**
     * Mensagem em JSON, nao Java-serialized. Serializacao nativa do Spring e'
     * vulneravel e torna a fila ilegivel fora deste classpath; JSON deixa a
     * intencao legivel no management e versionavel.
     */
    @Bean
    Jackson2JsonMessageConverter messageConverter(ObjectMapper mapper) {
        return new Jackson2JsonMessageConverter(mapper);
    }

    @Bean
    RabbitTemplate rabbitTemplate(ConnectionFactory cf, Jackson2JsonMessageConverter conv) {
        RabbitTemplate t = new RabbitTemplate(cf);
        t.setMessageConverter(conv);
        t.setMandatory(true);
        return t;
    }
}
