package com.astral.main.filter;

import org.springframework.core.annotation.Order;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.kerberos.authentication.KerberosTicketValidation;
import org.springframework.security.kerberos.authentication.sun.SunJaasKerberosTicketValidator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import com.unboundid.ldap.sdk.*;
import java.nio.file.*;
import java.util.*;

@Component
@Order(-100)
public class SpnegoAuthFilter implements WebFilter {
    private final boolean enabled;
    private final boolean spnegoOk;
    private final String domain;
    private final String bindUser;
    private final String bindPass;
    private SunJaasKerberosTicketValidator validator = null;

    public SpnegoAuthFilter() {
        enabled = "true".equals(prop("astral.ad.enabled"));
        spnegoOk = "true".equals(prop("astral.ad.spnego"));
        domain = prop("astral.ad.domain");
        bindUser = prop("astral.ad.user");
        bindPass = prop("astral.ad.pass");

        if (enabled && spnegoOk) {
            try {
                SunJaasKerberosTicketValidator v = new SunJaasKerberosTicketValidator();
                v.setServicePrincipal(prop("astral.ad.spn"));
                v.setKeyTabLocation(new FileSystemResource(prop("astral.ad.keytab")));
                v.afterPropertiesSet();
                validator = v;
            } catch (Exception e) { validator = null; }
        }
    }

    private static String prop(String k) {
        try {
            for (String l : Files.readAllLines(Paths.get("/etc/astral/ad.properties"))) {
                if (l.startsWith(k + "=")) return l.substring(k.length() + 1).trim();
            }
        } catch (Exception ignored) {}
        return "";
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!enabled || validator == null) return chain.filter(exchange);

        String path = exchange.getRequest().getURI().getPath();
        if (path.startsWith("/api/auth/") || path.startsWith("/login/") || path.startsWith("/css/") ||
            path.startsWith("/js/") || path.startsWith("/images/") || path.startsWith("/fonts/"))
            return chain.filter(exchange);

        if (exchange.getRequest().getCookies().getFirst("astral_token") != null)
            return chain.filter(exchange);

        String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.startsWith("Negotiate ")) {
            try {
                byte[] ticket = Base64.getDecoder().decode(auth.substring(10));
                KerberosTicketValidation val = validator.validateTicket(ticket);
                String principal = val.username();
                String uname = principal.contains("@") ? principal.split("@")[0] : principal;

                return getLdapGroupsReactive(uname).flatMap(groups -> {
                    boolean admin = groups.stream().anyMatch(g ->
                        g.equalsIgnoreCase("Domain Admins") ||
                        g.equalsIgnoreCase("Administrators") ||
                        g.equalsIgnoreCase("Enterprise Admins"));

                    String token = UUID.randomUUID().toString();
                    ServerWebExchange mutated = exchange.mutate().request(r ->
                            r.cookies(c -> {
                                c.add("astral_token", new HttpCookie("astral_token", token));
                                c.add("astral_user", new HttpCookie("astral_user", uname));
                                c.add("astral_admin", new HttpCookie("astral_admin", admin ? "1" : "0"));
                            })).build();
                    mutated.getResponse().getCookies().add("astral_token", new HttpCookie("astral_token", token));
                    mutated.getResponse().getCookies().add("astral_user", new HttpCookie("astral_user", uname));
                    return chain.filter(mutated);
                });
            } catch (Exception ignored) { }
        }

        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().add(HttpHeaders.WWW_AUTHENTICATE, "Negotiate");
        return exchange.getResponse().setComplete();
    }

    private Mono<List<String>> getLdapGroupsReactive(String user) {
        return Mono.fromCallable(() -> {
            List<String> out = new ArrayList<>();
            try (LDAPConnection conn = new LDAPConnection(domain, 389)) {
                conn.bind(bindUser + "@" + domain, bindPass);
                SearchRequest req = new SearchRequest("", SearchScope.SUB, "(&(objectClass=user)(sAMAccountName=" + user + "))", "memberOf");
                for (SearchResultEntry entry : conn.search(req).getSearchEntries()) {
                    String[] mo = entry.getAttributeValues("memberOf");
                    if (mo != null) {
                        for (String dn : mo) {
                            for (String part : dn.split(",")) {
                                if (part.trim().toLowerCase().startsWith("cn=")) out.add(part.trim().substring(3));
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
            return out;
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
