package com.astral.firewall.ws;
import org.springframework.context.annotation.Bean; import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping; import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import java.util.Map;
@Configuration
public class WsConfig {
@Bean public HandlerMapping fwLogsMapping(FirewallLogsWebSocketHandler h){ SimpleUrlHandlerMapping m=new SimpleUrlHandlerMapping(); m.setUrlMap(Map.of("/ws/firewall-logs",h)); m.setOrder(-1); return m; }
@Bean public WebSocketHandlerAdapter wsAdapter(){ return new WebSocketHandlerAdapter(); }
}
