package com.astral.fabric.reconciliation;

import java.util.Map;

/**
 * Delta entre o estado observado e o desejado.
 *
 * <p>{@code antes} e {@code depois} sao mapas justapostos: o motor gera o diff
 * textual com a MESMA rotina para todos os modulos, em vez de cada um inventar
 * o proprio formato. Auditoria de diff so' e' comparavel se o formato for unico.
 */
public record Diff(boolean mudanca, Map<String, Object> antes, Map<String, Object> depois, String observado) {

    public static Diff semMudanca(Map<String, Object> atual) {
        return new Diff(false, atual, atual, "estado atual identico ao desejado");
    }

    public static Diff de(Map<String, Object> antes, Map<String, Object> depois, String observado) {
        return new Diff(true, antes, depois, observado);
    }
}
