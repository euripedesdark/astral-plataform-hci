package com.astral.fabric.reconciliation;

/** Modulos que sabem se reconciliar. O nome vira coluna e chave de roteamento. */
public enum ReconcileModule {
    NETWORK,
    FIREWALL,
    DNS,
    IDENTITY,
    PROXY
}
