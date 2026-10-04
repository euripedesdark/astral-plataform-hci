package com.astral.fabric.reconciliation;

import java.util.List;

/**
 * Resultado da validacao de precondicoes.
 *
 * <p>{@code erros} vazia significa aprovado -- nada de "provavelmente ok". Se a
 * validacao nao consegue afirmar que o sistema aguenta, ela reprova.
 */
public record Validacao(List<String> erros) {

    public static Validacao ok() {
        return new Validacao(List.of());
    }

    public static Validacao falha(String... erros) {
        return new Validacao(List.of(erros));
    }

    public boolean aprovada() {
        return erros == null || erros.isEmpty();
    }
}
