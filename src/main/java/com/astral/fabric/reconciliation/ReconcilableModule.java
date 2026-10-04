package com.astral.fabric.reconciliation;

/**
 * Contrato SPI entre o motor de reconciliacao e um modulo de infraestrutura.
 *
 * <p>Cada implementacao e' dona do proprio payload e do proprio rollback. O
 * motor nao sabe o que e' um nmcli nem o que e' um iptables-save; ele sabe
 * apenas que precisa chamar na ordem e que, se algo falhar depois do
 * {@link #commit}, o {@link #rollback} roda obrigatoriamente.
 *
 * <p>Os quatro metodos sao chamados exatamente uma vez por intencao, na ordem.
 * Implementacao pode assumir isso: nao ha reexecucao parcial dentro de um passo.
 */
public interface ReconcilableModule {

    /** Chave de roteamento -- precisa ser unica por bean. */
    ReconcileModule module();

    /**
     * Precondicoes: sintaxe do payload, existencia de recursos, ferramenta
     * instalada. Falha aqui e' barata e nunca mexe no sistema.
     */
    Validacao validar(Intent intent);

    /**
     * Calcula o delta entre o estado observado e o desejado.
     * Deve ser barato e nao-destrutivo; e' o "Reconciliação (Diff)" do ciclo.
     */
    Diff diff(Intent intent);

    /**
     * Aplica. ANTES de aplicar, captura tudo de que precisa para desfazer e
     * devolve no {@code token}. Se lancar excecao no meio, o motor chama
     * {@link #rollback} com o que deu tempo de registrar -- por isso
     * {@link Progresso} reporta cada comando antes de executa-lo.
     */
    Commit commit(Intent intent, Diff diff, Progresso progresso);

    /**
     * Desfaz. Deterministico: recebe exatamente o snapshot capturado no commit,
     * na ordem inversa da aplicacao. Nunca pode lancar excecao nao tratada.
     */
    void rollback(Intent intent, Commit commit);
}
