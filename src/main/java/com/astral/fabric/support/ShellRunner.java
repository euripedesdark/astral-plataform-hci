package com.astral.fabric.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Executor unico de comandos de infraestrutura.
 *
 * <p>Substitui os {@code subprocess.run} espalhados pelos scripts legados de
 * {@code fabric/}. Tres regras que os scripts nao tinham:
 *
 * <ol>
 *   <li><b>dry-run e' propriedade, nao um if espalhado.</b> Quem chama recebe o
 *       mesmo objeto, com {@code dryRun=true}, e o fluxo nao precisa saber.</li>
 *   <li><b>Tudo entra num journal.</b> A filosofia da Astral exige
 *       auditabilidade: a lista de comandos executados num ciclo de
 *       reconciliacao e' o log que permite reconstruir o que aconteceu.</li>
 *   <li><b>Nunca {@code shell=true} com string montada.</b> Os argumentos vem
 *       como {@code List} e vao direto para o {@link ProcessBuilder}; o unico
 *       caminho com shell e' o {@link #sh(String)}, que existe para scripts
 *       ja' escritos, nao para interpolar entrada de usuario.</li>
 * </ol>
 *
 * <p>Timeout e' obrigatorio: um {@code nmcli} pendurado nao pode segurar uma
 * thread de reconciliacao para sempre.
 */
public class ShellRunner {

    private static final Logger log = LoggerFactory.getLogger(ShellRunner.class);
    private static final Duration PADRAO = Duration.ofSeconds(60);

    private final boolean dryRun;
    private final Duration timeout;
    private final List<CommandResult> journal =
            Collections.synchronizedList(new ArrayList<>());

    public ShellRunner(boolean dryRun) {
        this(dryRun, PADRAO);
    }

    public ShellRunner(boolean dryRun, Duration timeout) {
        this.dryRun = dryRun;
        this.timeout = Objects.requireNonNull(timeout);
    }

    public boolean isDryRun() {
        return dryRun;
    }

    /** Journal imutavel em copia: e' evidencia, nao buffer. */
    public List<CommandResult> journal() {
        synchronized (journal) {
            return List.copyOf(journal);
        }
    }

    public CommandResult run(List<String> argv) {
        return run(argv, null);
    }

    public CommandResult run(List<String> argv, String stdin) {
        String rotulo = String.join(" ", argv);
        if (dryRun) {
            CommandResult r = CommandResult.dryRun(rotulo);
            journal.add(r);
            log.info("[dry-run] {}", rotulo);
            return r;
        }
        Instant ini = Instant.now();
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.redirectErrorStream(false);
            Process p = pb.start();
            if (stdin != null) {
                try (var os = p.getOutputStream()) {
                    os.write(stdin.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                p.getOutputStream().close();
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                CommandResult r = new CommandResult(rotulo, 124, out,
                        "timeout apos " + timeout.toSeconds() + "s",
                        Duration.between(ini, Instant.now()), false);
                journal.add(r);
                log.warn("[timeout] {}", r.resumo());
                return r;
            }
            CommandResult r = new CommandResult(rotulo, p.exitValue(), out, err,
                    Duration.between(ini, Instant.now()), false);
            journal.add(r);
            if (!r.ok()) log.warn("[cmd falhou] {}", r.resumo());
            return r;
        } catch (IOException e) {
            CommandResult r = new CommandResult(rotulo, 127, "", String.valueOf(e.getMessage()),
                    Duration.between(ini, Instant.now()), false);
            journal.add(r);
            log.error("[cmd nao executou] {}", r.resumo());
            return r;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            CommandResult r = new CommandResult(rotulo, 130, "", "interrompido",
                    Duration.between(ini, Instant.now()), false);
            journal.add(r);
            return r;
        }
    }

    /**
     * Caminho shell. Existe para os scripts legados ja' escritos e para pipelines
     * do proprio sistema ({@code iptables-save | tee ...}). Entrada de usuario
     * nao pode chegar aqui sem passar por {@link #escapesSeguro(String)}.
     */
    public CommandResult sh(String script) {
        return run(List.of("bash", "-c", script));
    }

    public String saida(String script) {
        CommandResult r = sh(script);
        return r.ok() ? r.saidaUnica() : "";
    }

    /** Cobre o caminho shell contra injecao quando o valor vem de fora. */
    public static String escapesSeguro(String valor) {
        if (valor == null) return "";
        return valor.replace("'", "'\\''").replaceAll("[;&|`$><\\n\\r]", "");
    }
}
