package com.astral.fabric.reconciliation;

/**
 * Callback do motor para o modulo reportar cada comando ANTES de executa-lo.
 *
 * <p>Nao e' otimizacao: e' como o registro da intencao continua verdadeiro
 * mesmo que o processo morra no meio do commit. Sem isto, um kill no meio de um
 * {@code nmcli con up} deixaria o registro dizendo "COMMITTING" sem dizer o que
 * ja' tinha sido feito -- e nesse caso o rollback nao teria o que desfazer.
 */
public interface Progresso {

    void comandoAplicado(String comando);

    Progresso NULO = comando -> { };
}
