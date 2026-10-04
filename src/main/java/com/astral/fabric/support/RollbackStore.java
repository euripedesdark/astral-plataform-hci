package com.astral.fabric.support;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * Armazem de snapshots de rollback, em disco e nao em memoria.
 *
 * <p>Por que disco: o intervalo entre o commit e o rollback e' exatamente onde
 * a aplicacao pode cair (OOM, kill, reboot). Um {@code Map} em memoria faz o
 * rollback sumir junto com o processo, e' o pior lugar para guardar a unica
 * prova de como voltar atras. Arquivo em {@code ${java.io.tmpdir}} sobrevive
 * ao processo e morre no reboot, que e' o comportamento certo: um rollback de
 * configuracao de rede de tres horas atras ja' nao faz sentido.
 *
 * <p>Um arquivo por intent: {@code <prefixo>-<token>.json}. Nome e' a chave,
 * nao indice em banco -- quem olha o diretorio ve' exatamente o que pendura.
 */
public class RollbackStore {

    private final Path dir;
    private final ObjectMapper json;

    public RollbackStore(Path dir, ObjectMapper json) {
        this.dir = dir;
        this.json = json;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("rollback store sem diretorio: " + dir, e);
        }
    }

    public static RollbackStore padrao(ObjectMapper json) {
        return new RollbackStore(Path.of(System.getProperty("java.io.tmpdir"), "astral", "rollback"), json);
    }

    /** Grava o snapshot e devolve o token. O token e' o nome do arquivo. */
    public String grava(String prefixo, Object snapshot) throws IOException {
        String token = prefixo + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path alvo = dir.resolve(token + ".json");
        Files.writeString(alvo, json.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        return token;
    }

    /** Le o snapshot. Falha aqui e' erro de infraestrutura, nao "nao achei". */
    public <T> T le(String token, Class<T> tipo) throws IOException {
        if (token == null || token.isBlank()) {
            throw new IOException("rollback sem token: nao ha o que desfazer");
        }
        if (token.contains("/") || token.contains("..")) {
            throw new IOException("token de rollback invalido: " + token);
        }
        Path alvo = dir.resolve(token + ".json");
        if (!Files.isRegularFile(alvo)) {
            throw new IOException("snapshot de rollback nao encontrado: " + alvo);
        }
        return json.readValue(Files.readString(alvo, StandardCharsets.UTF_8), tipo);
    }

    /** Apagado so depois do rollback confirmado: prova nao se apaga no meio. */
    public void remove(String token) {
        if (token == null || token.isBlank()) return;
        if (token.contains("/") || token.contains("..")) return;
        try {
            Files.deleteIfExists(dir.resolve(token + ".json"));
        } catch (IOException ignorada) {
            // sobra um arquivo em /tmp; melhor sobrar do que falhar o shutdown
        }
    }

    public Path diretorio() {
        return dir;
    }
}
