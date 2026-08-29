package com.astral.main.config;

import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TerminalWebSocketHandler extends TextWebSocketHandler {
    private final Map<String, Process> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("script", "-qfc", "/bin/bash", "/dev/null");
        pb.environment().put("TERM", "dumb");
        pb.directory(new File("/root"));
        Process proc = pb.start();
        sessions.put(session.getId(), proc);
        Thread t = new Thread(() -> {
            try (InputStream in = proc.getInputStream()) {
                byte[] buf = new byte[4096]; int n;
                while ((n = in.read(buf)) != -1) {
                    synchronized (session) {
                        if (session.isOpen())
                            session.sendMessage(new TextMessage(new String(buf, 0, n, StandardCharsets.UTF_8)));
                    }
                }
            } catch (IOException ignored) {}
        });
        t.setDaemon(true); t.start();
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Process p = sessions.get(session.getId());
        if (p != null && p.isAlive()) {
            p.getOutputStream().write(message.getPayload().getBytes(StandardCharsets.UTF_8));
            p.getOutputStream().flush();
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Process p = sessions.remove(session.getId());
        if (p != null) p.destroyForcibly();
    }
}
