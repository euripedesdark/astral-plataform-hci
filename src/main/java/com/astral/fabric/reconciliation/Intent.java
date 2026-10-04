package com.astral.fabric.reconciliation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Uma intencao declarativa registrada -- nao um "job".
 *
 * <p>A diferenca importa: job pressupoe que o sistema decide; intencao
 * pressupoe que alguem declarou o que quer e o sistema tenta chegar la',
 * reprovavelmente. Por isso o corpo e' JSON livre ({@code payload}) e nao um
 * enum de operacoes: o que muda de um modulo para o outro e' o contrato do
 * payload, e isso e' responsabilidade do modulo, nao do motor.
 */
@Entity
@Table(name = "reconciliation_intent",
        indexes = {
                @Index(name = "ix_intent_status", columnList = "status"),
                @Index(name = "ix_intent_created", columnList = "created_at")
        })
public class Intent {

    @Id
    @Column(length = 36)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReconcileModule module;

    @Column(nullable = false, length = 64)
    private String type;

    /** Corpo declarativo. Validado pelo modulo, nao pelo motor. */
    @Lob
    @Column(columnDefinition = "text")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IntentStatus status = IntentStatus.RECEIVED;

    @Column(length = 128)
    private String requestedBy;

    /** Idempotencia: a mesma intencao nao pode ser aplicada duas vezes. */
    private boolean applied;

    @Lob
    @Column(columnDefinition = "text")
    private String diffText;

    @Lob
    @Column(columnDefinition = "text")
    private String error;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    private Instant finishedAt;

    /** Trilha de transicoes no formato {@code RECEIVED@12:00:01 -> VALIDATING@...}. */
    @Column(length = 2048)
    private String trail;

    public Intent() {
    }

    public static Intent nova(ReconcileModule module, String type, String payload, String who) {
        Intent i = new Intent();
        i.id = UUID.randomUUID().toString();
        i.module = module;
        i.type = type;
        i.payload = payload;
        i.requestedBy = who;
        return i;
    }

    public void transicao(IntentStatus novo) {
        String passo = novo + "@" + java.time.LocalTime.now().withNano(0);
        trail = (trail == null || trail.isBlank()) ? passo : trail + " -> " + passo;
        status = novo;
        updatedAt = Instant.now();
        if (novo == IntentStatus.COMMITTED || novo == IntentStatus.ROLLED_BACK
                || novo == IntentStatus.NO_OP || novo == IntentStatus.FAILED) {
            finishedAt = Instant.now();
        }
    }

    // --- accessors -------------------------------------------------------
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public ReconcileModule getModule() { return module; }
    public void setModule(ReconcileModule module) { this.module = module; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public IntentStatus getStatus() { return status; }
    public void setStatus(IntentStatus status) { this.status = status; }
    public String getRequestedBy() { return requestedBy; }
    public void setRequestedBy(String requestedBy) { this.requestedBy = requestedBy; }
    public boolean isApplied() { return applied; }
    public void setApplied(boolean applied) { this.applied = applied; }
    public String getDiffText() { return diffText; }
    public void setDiffText(String diffText) { this.diffText = diffText; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public String getTrail() { return trail; }
    public void setTrail(String trail) { this.trail = trail; }
}
