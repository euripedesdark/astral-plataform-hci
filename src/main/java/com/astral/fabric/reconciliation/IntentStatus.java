package com.astral.fabric.reconciliation;

/**
 * Estados do ciclo declarativo da Astral.
 *
 * <pre>
 * Intenção → Validação → Reconciliação (Diff) → Commit → Rollback Obrigatório Automático
 * </pre>
 *
 * <p>Cada transicao e' persistida: o historico de uma intencao tem que
 * responder "em que passo ela parou" sem precisar de log correlacionado.
 * Os nomes sao em ingles porque viram valor de coluna e de API, e nesse nivel
 * consistencia vale mais que traducao.
 */
public enum IntentStatus {
    /** Enviada e aceita pela fila; nada foi verificado ainda. */
    RECEIVED,
    /** Passo 1: validacao sintatica e de precondicoes. Falha fechada. */
    VALIDATING,
    VALIDATED,
    /** Passo 2: leitura do estado observado e calculo do delta. */
    DIFFING,
    /** Delta calculado. Detalhado em {@code diffText} do registro. */
    DIFF_READY,
    /** Nada a fazer: estado observado ja' e' o desejado. */
    NO_OP,
    /** Passo 3: aplicacao, com snapshot ja' capturado. */
    COMMITTING,
    COMMITTED,
    /** Passo 4 obrigatorio apos qualquer falha no commit. */
    ROLLING_BACK,
    /**
     * Commit falhou e o rollback devolveu o sistema ao estado conhecido.
     * Terminal: a intencao nao foi aplicada, mas nao ha pendencia aberta.
     */
    ROLLED_BACK,
    /**
     * Terminal de insucesso, em dois casos que o {@code error} distingue:
     * validacao reprovou (nada chegou a ser aplicado) ou o rollback falhou
     * (estado incerto -- exige decisao humana antes de qualquer nova tentativa).
     */
    FAILED
}
