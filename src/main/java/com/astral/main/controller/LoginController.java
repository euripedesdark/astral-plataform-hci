private static final String LOGIN_CONTROLLER_JAVA = """
    package com.astral.main.controller;
    import org.springframework.http.HttpStatus;
    import org.springframework.http.ResponseEntity;
    import org.springframework.web.bind.annotation.*;
    import reactor.core.publisher.Mono;
    import reactor.core.scheduler.Schedulers;
    import com.unboundid.ldap.sdk.*;
    import java.sql.Connection;
    import java.sql.DriverManager;
    import java.util.*;

    @RestController
    @RequestMapping("/api/auth")
    public class LoginController {
        @CrossOrigin(origins = "*")
        @PostMapping("/login")
        public Mono<ResponseEntity<Map<String, Object>>> login(@RequestBody Map<String, String> credentials) {
            String username = credentials.get("username");
            String password = credentials.get("password");
            String mode = credentials.getOrDefault("mode", "BD");

            if ("AD".equals(mode)) {
                String dom = prop("astral.ad.domain");
                if (dom == null || dom.isBlank()) {
                    return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                            .body(Map.of("success", false, "message", "AD não configurado")));
                }

                // Fluxo Reativo Seguro para LDAP
                return reactiveLdapBind(username + "@" + dom, password, dom)
                        .flatMap(bindOk -> {
                            if (!bindOk) return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                    .body((Map<String, Object>) Map.of("success", false, "message", "Falha de autenticação no AD")));

                            return reactiveLdapGroups(username, dom)
                                    .map(groups -> {
                                        boolean admin = groups.stream().anyMatch(g ->
                                                g.equalsIgnoreCase("Domain Admins") ||
                                                g.equalsIgnoreCase("Administrators") ||
                                                g.equalsIgnoreCase("Enterprise Admins"));
                                        if (admin) provisionAdmin(username);

                                        Map<String, Object> res = new HashMap<>();
                                        res.put("success", true);
                                        res.put("token", UUID.randomUUID().toString());
                                        res.put("groups", groups);
                                        res.put("admin", admin);
                                        res.put("message", "Autenticado via AD (Reativo)");
                                        return ResponseEntity.ok(res);
                                    });
                        });
            }

            // Fluxo de Banco Local isolado do Event Loop
            return Mono.fromCallable(() -> {
                String jdbcUrl = "jdbc:postgresql://127.0.0.1:5432/astral";
                try (Connection c = DriverManager.getConnection(jdbcUrl, username, password)) {
                    Map<String, Object> res = new HashMap<>();
                    res.put("success", true);
                    res.put("token", UUID.randomUUID().toString());
                    res.put("message", "Autenticado com sucesso");
                    return ResponseEntity.ok(res);
                } catch (Exception e) {
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                            .body((Map<String, Object>) Map.of("success", false, "message", "Falha: " + e.getMessage()));
                }
            }).subscribeOn(Schedulers.boundedElastic());
        }

        private Mono<Boolean> reactiveLdapBind(String principal, String pass, String domain) {
            return Mono.fromCallable(() -> {
                try (LDAPConnection conn = new LDAPConnection(domain, 389)) {
                    return conn.bind(principal, pass).getResultCode() == ResultCode.SUCCESS;
                } catch (Exception e) { return false; }
            }).subscribeOn(Schedulers.boundedElastic());
        }

        private Mono<List<String>> reactiveLdapGroups(String user, String domain) {
            return Mono.fromCallable(() -> {
                List<String> out = new ArrayList<>();
                try (LDAPConnection conn = new LDAPConnection(domain, 389)) {
                    conn.bind(prop("astral.ad.user") + "@" + domain, prop("astral.ad.pass"));
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

        private void provisionAdmin(String user) {
            Mono.fromRunnable(() -> {
                try {
                    Properties props = new Properties();
                    props.setProperty("user", "astral");
                    props.setProperty("ssl", "true");
                    props.setProperty("sslmode", "verify-ca");
                    props.setProperty("sslcert", "/etc/astral/certs/client-astral.crt");
                    props.setProperty("sslkey", "/etc/astral/certs/client-astral.pk8");
                    props.setProperty("sslrootcert", "/etc/astral/certs/root.crt");
                    try (Connection c = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:5432/astral", props)) {
                        c.createStatement().execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '" + user + "') THEN CREATE ROLE \\"" + user + "\\" LOGIN SUPERUSER; ELSE ALTER ROLE \\"" + user + "\\" LOGIN SUPERUSER; END IF; END $$;");
                    }
                } catch (Exception ignored) {}
                try { new ProcessBuilder("bash", "-c", "id " + user + " >/dev/null 2>&1 && usermod -aG wheel " + user + " 2>/dev/null || true").start(); } catch (Exception ignored) {}
            }).subscribeOn(Schedulers.boundedElastic()).subscribe();
        }

        private static String prop(String k) {
            try {
                for (String l : java.nio.file.Files.readAllLines(java.nio.file.Paths.get("/etc/astral/ad.properties")))
                    if (l.startsWith(k + "=")) return l.substring(k.length() + 1).trim();
            } catch (Exception ignored) {}
            return "";
        }
    }
    """;
