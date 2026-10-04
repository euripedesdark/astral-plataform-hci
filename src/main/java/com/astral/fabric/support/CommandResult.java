package com.astral.fabric.support;

import java.time.Duration;
import java.time.Instant;

/**
 * Resultado de um comando executado fora do JVM.
 *
 * <p>Registro imutavel e auditavel: quem executou, o que, com que codigo de
 * saida e em quanto tempo. Nada de {@code void} escondendo falha -- em
 * infraestrutura, "o comando rodou" e' uma afirmacao que precisa ser provavel.
 */
public record CommandResult(
        String command,
        int exitCode,
        String stdout,
        String stderr,
        Duration elapsed,
        boolean dryRun) {

    public static CommandResult dryRun(String command) {
        return new CommandResult(command, 0, "", "", Duration.ZERO, true);
    }

    /** {@code dry-run} conta como sucesso de proposito: nao executar e' o pedido. */
    public boolean ok() {
        return dryRun || exitCode == 0;
    }

    public String saidaUnica() {
        String s = stdout == null ? "" : stdout.strip();
        return s.isBlank() && stderr != null ? stderr.strip() : s;
    }

    public String resumo() {
        return (dryRun ? "[dry-run] " : "") + command
                + " -> exit=" + exitCode
                + " em " + elapsed.toMillis() + "ms";
    }

    public Instant when() {
        return Instant.now();
    }
}
