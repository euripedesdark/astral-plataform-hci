package com.astral.fabric.proxy;

/**
 * Resposta do motor. Tres status possiveis, e nenhum outro:
 *
 * <ul>
 *   <li><b>200</b> -- deixa passar;</li>
 *   <li><b>403</b> -- negado por politica;</li>
 *   <li><b>401</b> -- nao ha quem avaliar ou a infraestrutura caiu. O Nginx
 *       traduz isso para o navegador como "nao autenticado", que e' o unico
 *       codigo honesto quando o motor <b>nao sabe</b> decidir.</li>
 * </ul>
 *
 * <p>Nunca 500 e nunca 200 por duvida: 500 vira pagina de erro no meio do
 * proxy e 200 por duvida e' fail-open com outro nome.
 */
public record AclDecision(
        int status,
        String action,
        String categoria,
        String grupo,
        String origem,
        String motivo,
        long ms) {

    public static final String ALLOW = "ALLOW";
    public static final String DENY = "DENY";
    public static final String UNAUTHENTICATED = "UNAUTHENTICATED";
    public static final String ERROR = "ERROR";

    public boolean permitido() {
        return status == 200;
    }

    public static AclDecision allow(String categoria, String grupo, String origem, String motivo, long ms) {
        return new AclDecision(200, ALLOW, categoria, grupo, origem, motivo, ms);
    }

    public static AclDecision deny(String categoria, String grupo, String origem, String motivo, long ms) {
        return new AclDecision(403, DENY, categoria, grupo, origem, motivo, ms);
    }

    public static AclDecision unauth(String origem, String motivo, long ms) {
        return new AclDecision(401, UNAUTHENTICATED, null, null, origem, motivo, ms);
    }

    public static AclDecision erro(String origem, String motivo, long ms) {
        return new AclDecision(401, ERROR, null, null, origem, motivo, ms);
    }
}
