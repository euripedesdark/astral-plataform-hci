package com.astral.fabric.proxy;

import java.util.List;
import java.util.Locale;

/**
 * Quem esta' pedindo, do jeito que o motor de ACL precisa enxergar.
 *
 * <p>Nao e' uma autoridade do Spring e nao e' um principal de seguranca: e' o
 * recorte minimo que a politica cruza com a categoria do dominio. Papel de
 * administrador decide quem edita a politica; <b>grupo</b> decide quem passa.
 * Misturar os dois e' como acabar com uma permissao de administrador virando
 * liberacao de navegacao.
 *
 * <p>{@code source} fica no objeto de proposito: a auditoria pergunta "de onde
 * veio esta identidade" (JWT, sessao AD, sessao Postgres) e uma resposta sem
 * essa pergunta nao serve para investigacao posterior.
 */
public record AclIdentity(String usuario, List<String> grupos, String source) {

    public AclIdentity {
        usuario = usuario == null ? "" : usuario.trim();
        source = source == null ? "?" : source;
        grupos = grupos == null ? List.of() : grupos.stream()
                .filter(g -> g != null && !g.isBlank())
                .map(g -> g.trim())
                .toList();
    }

    /** Grupos em maiusculas e ordenados: a chave de cache precisa ser canonica. */
    public List<String> gruposNormalizados() {
        return grupos.stream()
                .map(g -> g.toUpperCase(Locale.ROOT))
                .sorted()
                .distinct()
                .toList();
    }

    /** Chave de cache desta identidade. Mesmo usuario, mesma politica. */
    public String chaveGrupos() {
        return String.join(",", gruposNormalizados());
    }
}
