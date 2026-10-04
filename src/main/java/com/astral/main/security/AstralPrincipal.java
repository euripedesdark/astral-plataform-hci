package com.astral.main.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.Collection;
import java.util.List;

/**
 * Principal autenticado do Astral.
 *
 * <p>Alem do papel ({@code ROLE_ASTRAL_ADMIN}) carrega os <b>nomes dos grupos
 * do AD</b>. A diferenca importa para o motor de ACL: o papel diz se o usuario
 * administra o portal; o grupo diz, por exemplo, que ele esta em
 * {@code FW-Operadores}, que e' o que a politica de navegacao cruza com a
 * categoria do dominio. So' papel nao da para escrever uma politica fina.
 *
 * <p>Os grupos vem do {@code memberOf} lido DURANTE o bind do login, nao de
 * uma consulta posterior. e' deliberado: consultar o AD dentro do
 * {@code auth_request} amarraria a navegacao ao controlador de dominio, e o
 * Samba fora de ar bloquearia todo mundo em vez de deixar uma politica em
 * cache valer.
 */
public class AstralPrincipal extends User {

    private final String source;
    private final List<String> adGroups;

    public AstralPrincipal(String username, String source,
                           Collection<? extends GrantedAuthority> authorities) {
        this(username, source, authorities, List.of());
    }

    public AstralPrincipal(String username, String source,
                           Collection<? extends GrantedAuthority> authorities,
                           List<String> adGroups) {
        super(username, "", true, true, true, true, authorities);
        this.source = source;
        this.adGroups = adGroups == null ? List.of() : List.copyOf(adGroups);
    }

    public String getSource() {
        return source;
    }

    /** CN dos grupos do AD. Vazio quando a fonte nao e' AD. */
    public List<String> getAdGroups() {
        return adGroups;
    }
}
