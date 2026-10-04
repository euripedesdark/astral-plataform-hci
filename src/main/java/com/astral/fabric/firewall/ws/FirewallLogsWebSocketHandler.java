package com.astral.fabric.firewall.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reproduz o filtro {@code ASTRAL-FW} do journal do kernel para o navegador.
 *
 * <p>Implementacao servlet (antes: {@code WebSocketHandler} do webflux com
 * {@code Flux.create}). A estrutura continua a mesma de proposito: um processo
 * por sessao, thread daemon leitora, e o processo morto na desconexao. O que
 * mudou e' a API e o fato de a thread agora pode bloquear sem custo, porque o
 * MVC roda em virtual threads.
 */
@Component
public class FirewallLogsWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(FirewallLogsWebSocketHandler.class);

    /** Sessao -> processo journalctl. Fechar a sessao sem matar o filho deixa
     *  um {@code journalctl -f} orfa o a cada aba fechada. */
    private final Map<String, Process> filhos = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Process proc;
        try {
            proc = new ProcessBuilder("bash", "-c",
                    "journalctl -k -f --output=cat 2>/dev/null | grep --line-buffered 'ASTRAL-FW'")
                    .redirectErrorStream(true)
                    .start();
        } catch (Exception e) {
            log.warn("ws firewall-logs: nao abriu o journal ({})", e.getMessage());
            fecha(session, null);
            return;
        }
        filhos.put(session.getId(), proc);
        Thread leitor = Thread.ofVirtual().name("fw-logs-" + session.getId()).start(() -> tentaLer(session, proc));
        leitor.setDaemon(true);
    }

    private void tentaLer(WebSocketSession session, Process proc) {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String linha;
            while ((linha = in.readLine()) != null && session.isOpen()) {
                synchronized (session) {
                    session.sendMessage(new TextMessage(linha + "\n"));
                }
            }
        } catch (Exception ignorada) {
            // sessao fechada no meio da leitura: normal, nao e' erro.
        } finally {
            fecha(session, proc);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        fecha(session, filhos.remove(session.getId()));
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("ws firewall-logs: erro de transporte na sessao {}", session.getId());
        fecha(session, filhos.remove(session.getId()));
    }

    private void fecha(WebSocketSession session, Process proc) {
        if (proc != null) {
            proc.destroyForcibly();
            filhos.remove(session.getId(), proc);
        }
        try {
            if (session.isOpen()) session.close(CloseStatus.NO_CLOSE_FRAME);
        } catch (Exception ignorada) {
            // ja' fechada.
        }
    }
}
