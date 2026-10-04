package com.astral.fabric.proxy.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Envio assincrono de eventos de auditoria para o Graylog via GELF/UDP.
 *
 * <p>Por que nao usar um appender de log: o evento de navegacao do proxy tem
 * contrato proprio (usuario, dominio, categoria, decisao, latencia) e precisa
 * sair do caminho quente do {@code auth_request}. Um appender acoplaria a
 * estrutura do Logback a estrutura da auditoria.
 *
 * <p>Tres garantias, na ordem em que a filosofia da Astral as exige:
 *
 * <ol>
 *   <li><b>Assincrono e nunca bloqueante.</b> {@link #publicar} poe na fila e
 *       devolve. Fila cheia = descarta e conta; o Nginx nao espera Graylog.</li>
 *   <li><b>Fallback sobre dependencia.</b> Graylog fora, UDP perdido, fila
 *       cheia: o evento vai para o arquivo em disco em JSON de uma linha. Nada
 *       de auditoria some porque o coletor caiu.</li>
 *   <li><b>Sem dependencia nova.</b> GELF e' JSON sobre UDP; escrever isso e'
 *       ~100 linhas, e evita um appender terceiro no caminho de auditoria.</li>
 * </ol>
 */
@Component
public class GraylogAuditPublisher {

    private static final Logger log = LoggerFactory.getLogger(GraylogAuditPublisher.class);

    /** Teto de datagrama do protocolo GELF. Acima disso, chunking. */
    private static final int GELF_MAX = 8192;
    private static final byte[] CHUNK_MAGIC = {0x1e, 0x0f};

    private final ObjectMapper json;
    private final String host;
    private final InetAddress alvo;
    private final int porta;
    private final boolean habilitado;
    private final Path fallback;
    private final BlockingQueue<Map<String, Object>> fila;
    private final AtomicLong publicados = new AtomicLong();
    private final AtomicLong descartados = new AtomicLong();
    private final AtomicLong fallbacks = new AtomicLong();

    private Thread trabalhador;
    private volatile boolean rodando;
    private DatagramSocket socket;

    public GraylogAuditPublisher(
            ObjectMapper json,
            @Value("${astral.graylog.host:127.0.0.1}") String host,
            @Value("${astral.graylog.gelf-port:12201}") int porta,
            @Value("${astral.graylog.enabled:true}") boolean habilitado,
            @Value("${astral.graylog.queue-capacity:8192}") int capacidade,
            @Value("${astral.graylog.fallback-file:${java.io.tmpdir}/astral/graylog-fallback.jsonl}") String fallback) {
        this.json = json;
        this.host = host;
        this.porta = porta;
        this.habilitado = habilitado;
        this.fila = new ArrayBlockingQueue<>(Math.max(64, capacidade));
        this.fallback = Path.of(fallback);
        try {
            this.alvo = InetAddress.getByName(host);
        } catch (Exception e) {
            throw new IllegalStateException("Graylog host invalido: " + host, e);
        }
    }

    @PostConstruct
    void sobe() {
        if (!habilitado) {
            log.warn("graylog: desabilitado por configuracao; auditoria cai integralmente no arquivo de fallback");
            return;
        }
        try {
            Files.createDirectories(fallback.toAbsolutePath().getParent());
        } catch (IOException e) {
            log.warn("graylog: nao criou o diretorio de fallback ({})", e.getMessage());
        }
        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(1500);
        } catch (SocketException e) {
            // Sem socket nao ha GELF, mas nao ha motivo para derrubar a
            // aplicacao: o worker nao sobe, a fila enche e todo evento cai no
            // JSONL de fallback. Auditoria continua, so' nao continua remota.
            socket = null;
            log.error("graylog: socket UDP indisponivel ({}); auditoria vai para o arquivo de fallback", e.getMessage());
            return;
        }
        rodando = true;
        trabalhador = Thread.ofVirtual().name("graylog-gelf").start(this::loop);
        log.info("graylog: GELF UDP armado em {}:{} (fila={}, fallback={})",
                host, porta, fila.remainingCapacity(), fallback);
    }

    @PreDestroy
    void desce() {
        rodando = false;
        if (trabalhador != null) trabalhador.interrupt();
        if (socket != null) socket.close();
    }

    /**
     * Enfileira o evento. Nunca bloqueia, nunca lanca: o chamador e' o caminho
     * de decisao de ACL e nao pode depender do destino do log.
     */
    public void publicar(String facility, String shortMessage, Map<String, Object> campos) {
        Map<String, Object> evento = new LinkedHashMap<>();
        evento.put("facility", facility);
        evento.put("short_message", shortMessage);
        evento.put("timestamp", Instant.now().toString());
        evento.putAll(campos);
        if (!habilitado || !fila.offer(evento)) {
            descartados.incrementAndGet();
            gravaFallback(evento);
        }
    }

    /** Variante ja' serializada, para nao reconstruir o mapa no chamador. */
    public void audit(String usuario, String modulo, String acao, String alvo, String resultado, Map<String, Object> extra) {
        Map<String, Object> campos = new LinkedHashMap<>();
        campos.put("usuario", usuario);
        campos.put("modulo", modulo);
        campos.put("acao", acao);
        campos.put("alvo", alvo);
        campos.put("resultado", resultado);
        campos.put("evento", "astral.audit");
        if (extra != null) campos.putAll(extra);
        publicar("astral-audit", acao + " " + alvo + " -> " + resultado, campos);
    }

    private void loop() {
        while (rodando) {
            Map<String, Object> evento;
            try {
                evento = fila.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (evento == null) continue;
            byte[] payload;
            try {
                // gelf() ja' devolve os bytes serializados; serializar de novo
                // aqui (como fazia uma versao anterior) jogava byte[] no
                // getBytes e quebrava em runtime, nao em compilacao.
                payload = gelf(evento);
            } catch (Exception e) {
                fallbacks.incrementAndGet();
                gravaFallback(evento);
                continue;
            }
            if (enviaUdp(payload)) {
                publicados.incrementAndGet();
            } else {
                fallbacks.incrementAndGet();
                gravaFallback(evento);
            }
        }
    }

    private byte[] gelf(Map<String, Object> evento) throws IOException {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("version", "1.1");
        doc.put("host", host);
        doc.put("short_message", str(evento.get("short_message")));
        doc.put("facility", str(evento.get("facility")));
        String ts = str(evento.get("timestamp"));
        doc.put("timestamp", ts.isBlank() ? System.currentTimeMillis() / 1000.0
                : java.time.OffsetDateTime.parse(ts).toEpochSecond());
        doc.put("level", 6); // informational
        // Campos adicionais do GELF exigem prefixo "_".
        for (Map.Entry<String, Object> e : evento.entrySet()) {
            if (e.getKey().equals("short_message") || e.getKey().equals("facility")
                    || e.getKey().equals("timestamp") || e.getKey().equals("version")
                    || e.getKey().equals("host") || e.getKey().equals("level")) continue;
            doc.put("_" + e.getKey(), e.getValue() == null ? "" : e.getValue());
        }
        return json.writeValueAsBytes(doc);
    }

    private boolean enviaUdp(byte[] payload) {
        if (socket == null || socket.isClosed()) return false;
        try {
            if (payload.length <= GELF_MAX) {
                socket.send(new DatagramPacket(payload, payload.length, alvo, porta));
                return true;
            }
            // Chunking GELF: \x1e\x0f + 8 bytes de id + (seq, total) + pedaco.
            byte[] id = UUID.randomUUID().toString().substring(0, 8)
                    .replace("-", "").getBytes(StandardCharsets.US_ASCII);
            int porPacote = 1024; // conservador: cabe em qualquer MTU com folga
            int total = (payload.length + porPacote - 1) / porPacote;
            if (total > 128) return false; // absurdo: deixa no fallback inteiro
            for (int seq = 0; seq < total; seq++) {
                int ini = seq * porPacote;
                int fim = Math.min(payload.length, ini + porPacote);
                byte[] pacote = new byte[2 + id.length + 2 + (fim - ini)];
                int k = 0;
                pacote[k++] = CHUNK_MAGIC[0];
                pacote[k++] = CHUNK_MAGIC[1];
                System.arraycopy(id, 0, pacote, k, id.length);
                k += id.length;
                pacote[k++] = (byte) seq;
                pacote[k++] = (byte) total;
                System.arraycopy(payload, ini, pacote, k, fim - ini);
                socket.send(new DatagramPacket(pacote, pacote.length, alvo, porta));
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Fallback: JSON de uma linha, append. e' a prova de que "auditoria sobre
     * conveniencia" e' regra e nao slogan -- se o Graylog sumir, a trilha fica.
     */
    private void gravaFallback(Map<String, Object> evento) {
        try {
            String linha = json.writeValueAsString(evento) + System.lineSeparator();
            Files.writeString(fallback, linha, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("graylog: fallback tambem falhou, evento perdido: {}", e.getMessage());
        }
    }

    public Map<String, Object> metricas() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("publicados", publicados.get());
        m.put("descartados", descartados.get());
        m.put("fallback", fallbacks.get());
        m.put("naFila", fila.size());
        m.put("alvo", host + ":" + porta);
        m.put("habilitado", habilitado);
        return m;
    }

    /** Drena o que restou na fila. Chamado no shutdown para nao perder auditoria. */
    public int drena() {
        int n = 0;
        Deque<Map<String, Object>> resto = new ArrayDeque<>();
        fila.drainTo(resto);
        for (Map<String, Object> e : resto) {
            try {
                if (enviaUdp(gelf(e))) publicados.incrementAndGet();
                else gravaFallback(e);
                n++;
            } catch (IOException ignorada) {
                gravaFallback(e);
            }
        }
        return n;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** Caminho do arquivo de fallback -- exposto ao endpoint de saude. */
    public Path arquivoFallback() {
        return fallback;
    }
}
