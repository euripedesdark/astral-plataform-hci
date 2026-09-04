package com.astral.main.controller;

import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import com.unboundid.util.ssl.SSLUtil;
import com.unboundid.util.ssl.TrustAllTrustManager;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.net.ssl.SSLSocketFactory;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;

@RestController
@RequestMapping("/api/auth")
public class LoginController {

    @CrossOrigin(origins = "*")
    @PostMapping("/login")
    public Mono<ResponseEntity<Map<String, Object>>> login(@RequestBody Map<String, String> credentials) {
        return Mono.fromCallable(() -> {
            String username = credentials.get("username");
            String password = credentials.get("password");
            Map<String, Object> response = new HashMap<>();

            // ===== 1) ACTIVE DIRECTORY (prioridade) =====
            String domain = prop("astral.ad.domain");
            if (domain != null && !domain.isBlank() && password != null && !password.isBlank()) {
                List<String> groups = adAuth(username, password, domain);
                if (groups != null) {
                    boolean admin = isAdAdmin(groups);
                    if (admin) provisionAdmin(username);
                    response.put("success", true);
                    response.put("token", UUID.randomUUID().toString());
                    response.put("tipo", "AD");
                    response.put("tipoUsuario", 3);
                    response.put("tipoLabel", "AD");
                    response.put("groups", groups);
                    response.put("admin", admin);
                    response.put("message", "Autenticado via Active Directory");
                    return ResponseEntity.ok(response);
                }
            }

            // ===== 2) FALLBACK: banco local =====
            try (Connection c = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:5432/astral", username, password)) {
                response.put("success", true);
                response.put("token", UUID.randomUUID().toString());
                response.put("tipo", "BD");
                response.put("tipoUsuario", 2);
                response.put("tipoLabel", "BD");
                response.put("admin", true);
                response.put("message", "Autenticado com sucesso");
                return ResponseEntity.ok(response);
            } catch (SQLException e) {
                response.put("success", false);
                response.put("message", "Falha de autenticação: " + e.getMessage());
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ===== Bind no AD (LDAPS 636 trust-all, fallback 389) + grupos memberOf =====
    private List<String> adAuth(String user, String pass, String domain) {
        try (LDAPConnection conn = connect(domain)) {
            String principal = user.contains("@") ? user : user + "@" + domain;
            conn.bind(principal, pass);
            String sam = user.contains("@") ? user.split("@")[0] : user;
            SearchResult sr = conn.search("", SearchScope.SUB,
                    "(&(objectClass=user)(sAMAccountName=" + sam + "))", "memberOf");
            List<String> groups = new ArrayList<>();
            for (SearchResultEntry e : sr.getSearchEntries()) {
                String[] mo = e.getAttributeValues("memberOf");
                if (mo != null) for (String dn : mo) groups.add(cnOf(dn));
            }
            if (groups.isEmpty()) groups.add("Domain Users");
            return groups;
        } catch (Exception e) {
            return null; // credencial AD inválida → cai no fallback
        }
    }

    private LDAPConnection connect(String domain) throws Exception {
        try {
            SSLUtil sslUtil = new SSLUtil(new TrustAllTrustManager());
            SSLSocketFactory sf = sslUtil.createSSLSocketFactory();
            LDAPConnection conn = new LDAPConnection(sf);
            conn.connect(domain, 636, 5000);
            return conn;
        } catch (Exception e) {
            LDAPConnection conn = new LDAPConnection();
            conn.connect(domain, 389, 5000);
            return conn;
        }
    }

    private boolean isAdAdmin(List<String> groups) {
        return groups.stream().anyMatch(g ->
                g.equalsIgnoreCase("Domain Admins") ||
                g.equalsIgnoreCase("Administrators") ||
                g.equalsIgnoreCase("Enterprise Admins"));
    }

    // Admin do AD = superuser no Postgres + wheel no Linux
    private void provisionAdmin(String user) {
        String safe = user.replaceAll("[\"'\\\\]", "");
        try {
            Properties props = new Properties();
            props.setProperty("user", "astral");
            props.setProperty("ssl", "true");
            props.setProperty("sslmode", "verify-ca");
            props.setProperty("sslcert", "/etc/astral/certs/client-astral.crt");
            props.setProperty("sslkey", "/etc/astral/certs/client-astral.pk8");
            props.setProperty("sslrootcert", "/etc/astral/certs/root.crt");
            try (Connection c = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:5432/astral", props)) {
                c.createStatement().execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='" + safe + "') THEN CREATE ROLE \"" + safe + "\" LOGIN SUPERUSER; ELSE ALTER ROLE \"" + safe + "\" LOGIN SUPERUSER; END IF; END $$;");
            }
        } catch (Exception ignored) {}
        try {
            new ProcessBuilder("bash", "-c", "id " + safe + " >/dev/null 2>&1 && usermod -aG wheel " + safe + " || true")
                    .start().waitFor();
        } catch (Exception ignored) {}
    }

    private String cnOf(String dn) {
        for (String part : dn.split(",")) {
            if (part.trim().toLowerCase().startsWith("cn=")) return part.trim().substring(3);
        }
        return dn;
    }

    private static String prop(String k) {
        try {
            for (String l : Files.readAllLines(Paths.get("/etc/astral/ad.properties")))
                if (l.startsWith(k + "=")) return l.substring(k.length() + 1).trim();
        } catch (Exception ignored) {}
        return null;
    }
}
