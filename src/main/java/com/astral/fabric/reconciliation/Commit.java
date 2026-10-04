package com.astral.fabric.reconciliation;

import java.util.List;

/**
 * Snapshot de rollback capturado ANTES da aplicacao.
 *
 * <p>{@code token} e' a chave que localiza a copia de seguranca no disco ou no
 * banco do proprio modulo (um {@code iptables-save}, um dump de perfil
 * NetworkManager, uma copia de smb.conf). {@code comandosAplicados} e' a ordem
 * real de execucao: o rollback roda na ordem inversa desta lista.
 */
public record Commit(String token, List<String> comandosAplicados, boolean aplicou) {

    public static Commit nada() {
        return new Commit(null, List.of(), false);
    }

    public static Commit de(String token, List<String> comandos) {
        return new Commit(token, List.copyOf(comandos), true);
    }

    /** Ordem inversa da aplicacao -- a unica ordem que desfaz sem deixar lixo. */
    public List<String> comandosInvertidos() {
        return comandosAplicados.reversed();
    }
}
