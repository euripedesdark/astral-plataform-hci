package com.astral.fabric.firewall.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Expoe o feed de logs do kernel em {@code /ws/firewall-logs}.
 *
 * <p>Este arquivo era reativo ({@code SimpleUrlHandlerMapping} +
 * {@code WebSocketHandlerAdapter} do webflux), porque o modulo de firewall era
 * um aplicativo separado com {@code spring-boot-starter-webflux}. Dentro da
 * plataforma o MVC e' servlet com virtual threads, e a API correta e' a
 * {@code org.springframework.web.socket} -- a reativa traria o reactor no
 * classpath so' para isto, e um handler de log nunca precisou de não-bloqueio.
 */
@Configuration
@EnableWebSocket
public class WsConfig implements WebSocketConfigurer {

    private final FirewallLogsWebSocketHandler handler;

    public WsConfig(FirewallLogsWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/firewall-logs").setAllowedOrigins("*");
    }
}
