package com.astral.proxy.config;

import com.astral.proxy.service.Store;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPConnectionPool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.StandardPasswordEncoder;
import org.springframework.security.ldap.authentication.ad.ActiveDirectoryLdapAuthenticationProvider;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Configuration
public class SecurityConfig {

    private final Store store;

    public SecurityConfig(Store store) {
        this.store = store;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // StandardPasswordEncoder é legacy; usamos só para o manager (BD tem sha próprio)
        return new StandardPasswordEncoder();
    }

    @Bean
    public ReactiveUserDetailsService userDetailsService() {
        // Não usamos o UserDetailsService tradicional — o AuthService faz tudo.
        // Este bean existe para satisfazer o ReactiveAuthenticationManager quando necessário.
        return username -> Mono.error(new UnsupportedOperationException(
            "Use AuthService.authenticate() diretamente."));
    }

    @Bean
    public ReactiveAuthenticationManager ldapAuthManager() {
        // Manager dedicado a AD via UnboundID.
        // Retorna um manager customizado que usa UnboundID LDAP SDK.
        return authentication -> {
            String username = authentication.getName();
            String password = authentication.getCredentials().toString();
            String domain = adDomain();
            if (domain == null || domain.isBlank()) {
                return Mono.error(new IllegalStateException("Nenhum domínio AD configurado"));
            }
            return authenticateWithUnboundID(username, password, domain)
                .filter(success -> success)
                .switchIfEmpty(Mono.error(new org.springframework.security.authentication.BadCredentialsException("Credenciais AD inválidas")))
                .map(success -> new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    username, password, Collections.emptyList()));
        };
    }

    @Bean
    public SecurityWebFilterChain securityFilterChain(ServerHttpSecurity http) {
        // O proxy não impõe autenticação web por aqui — ele delega ao AuthService
        // nos endpoints /proxy/api/. Esta config só garante que o Security
        // não intercepte nossos controllers REST.
        return http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .authorizeExchange(ex -> ex
                .pathMatchers("/proxy/api/**", "/api/**", "/proxy/**", "/**").permitAll())
            .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
            .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
            .logout(ServerHttpSecurity.LogoutSpec::disable)
            .build();
    }

    // ---------- Autenticação 100% reativa com UnboundID ----------
    private Mono<Boolean> authenticateWithUnboundID(String username, String password, String domain) {
        return Mono.fromCallable(() -> {
            String principal = username.contains("@") ? username : username + "@" + domain;
            // Tenta LDAPS (636) primeiro; fallback para LDAP (389)
            String[] urls = { "ldaps://" + domain + ":636", "ldap://" + domain + ":389" };
            Exception last = null;
            for (String url : urls) {
                try (LDAPConnection conn = new LDAPConnection()) {
                    conn.getOptions().setUseSSL(url.startsWith("ldaps://"));
                    conn.getOptions().setTrustManager(new com.unboundid.util.ssl.TrustAllTrustManager());
                    conn.connectToHost(domain, url.startsWith("ldaps://") ? 636 : 389, 5000);
                    conn.bind(principal, password);
                    conn.close();
                    return true;
                } catch (Exception e) {
                    last = e;
                }
            }
            if (last != null) System.err.println("[AD/UnboundID] falha final: " + last.getMessage());
            return false;
        });
    }

    // ---------- Leitura reativa dos grupos (memberOf) ----------
    public Mono<List<String>> groupsOf(String username) {
        return Mono.fromCallable(() -> {
            List<String> out = new ArrayList<>();
            String domain = adDomain();
            String bindUser = prop("astral.ad.user");
            String bindPass = prop("astral.ad.pass");
            if (domain == null || bindUser == null || bindPass == null) return out;
            String bindPrincipal = bindUser.contains("@") ? bindUser : bindUser + "@" + domain;
            try (LDAPConnection conn = new LDAPConnection()) {
                conn.getOptions().setTrustManager(new com.unboundid.util.ssl.TrustAllTrustManager());
                conn.connectToHost(domain, 389, 5000);
                conn.bind(bindPrincipal, bindPass);
                var req = new com.unboundid.ldap.sdk.SearchRequest(
                    "", com.unboundid.ldap.sdk.SearchScope.SUB,
                    "(&(objectClass=user)(sAMAccountName=" + username + "))",
                    "memberOf");
                for (var entry : conn.search(req).getSearchEntries()) {
                    String[] mo = entry.getAttributeValues("memberOf");
                    if (mo != null) {
                        for (String dn : mo) {
                            for (String part : dn.split(",")) {
                                if (part.trim().toLowerCase().startsWith("cn=")) {
                                    out.add(part.trim().substring(3));
                                }
                            }
                        }
                    }
                }
            }
            return out;
        });
    }

    private String adDomain() {
        try {
            return store.db.queryForObject(
                "select dominio from auth_sources where tipo='AD' and enabled=true limit 1",
                String.class);
        } catch (Exception e) { return null; }
    }

    private static String prop(String k) {
        try {
            for (String l : Files.readAllLines(Paths.get("/etc/astral/ad.properties")))
                if (l.startsWith(k + "=")) return l.substring(k.length() + 1).trim();
        } catch (Exception ignored) {}
        return null;
    }
}
