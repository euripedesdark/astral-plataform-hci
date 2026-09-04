package com.astral.main.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class WebSocketConfig {
    @Bean
    public HandlerMapping terminalWebSocketMapping(TerminalWebSocketHandler handler) {
        Map<String, Object> map = new HashMap<>();
        map.put("/ws/terminal", handler);
        SimpleUrlHandlerMapping m = new SimpleUrlHandlerMapping();
        m.setUrlMap(map);
        m.setOrder(-1);
        return m;
    }

    @Bean
    public WebSocketHandlerAdapter webSocketHandlerAdapter() {
        return new WebSocketHandlerAdapter();
    }
}
