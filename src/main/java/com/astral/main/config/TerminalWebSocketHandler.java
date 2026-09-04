package com.astral.main.config;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Component
public class TerminalWebSocketHandler implements WebSocketHandler {
    @Override
    public Mono<Void> handle(WebSocketSession session) {
        final Process proc;
        try {
            ProcessBuilder pb = new ProcessBuilder("script", "-qfc", "/bin/bash", "/dev/null");
            pb.environment().put("TERM", "dumb");
            pb.directory(new File("/root"));
            proc = pb.start();
        } catch (IOException e) { return session.close(); }

        Flux<WebSocketMessage> output = Flux.create(sink -> {
            Thread t = new Thread(() -> {
                try (InputStream in = proc.getInputStream()) {
                    byte[] buf = new byte[4096]; int n;
                    while ((n = in.read(buf)) != -1)
                        sink.next(session.textMessage(new String(buf, 0, n, StandardCharsets.UTF_8)));
                } catch (IOException ignored) {}
                sink.complete();
            });
            t.setDaemon(true); t.start();
        });

        Mono<Void> send = session.send(output);
        Mono<Void> recv = session.receive().doOnNext(msg -> {
            try {
                proc.getOutputStream().write(msg.getPayloadAsText().getBytes(StandardCharsets.UTF_8));
                proc.getOutputStream().flush();
            } catch (IOException ignored) {}
        }).then();

        return Mono.zip(send, recv).then().doFinally(s -> proc.destroyForcibly());
    }
}
