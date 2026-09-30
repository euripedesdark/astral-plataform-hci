package com.astral.main.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;

/**
 * Distribuicao do certificado CA que assina o LDAPS do AD.
 *
 * <p>Regra do dono: so baixa quem provar senha de SUPERUSER do banco. A
 * verificacao abre conexao real com as credenciais apresentadas e confere
 * {@code rolsuper} do usuario corrente. Senha errada ou nao-superuser
 * recebem o mesmo 401 generico (sem enumerar). Nada e persistido.
 */
@RestController
@RequestMapping("/api/certs")
public class CertDownloadController {

    private final String pgUrl;
    private final String caPath;

    public CertDownloadController(
            @Value("${astral.auth.postgres.url:jdbc:postgresql://127.0.0.1:5432/astral}") String pgUrl,
            @Value("${astral.ca.path:/etc/astral/certs/srvcloud-root-ca.crt}") String caPath) {
        this.pgUrl = pgUrl;
        this.caPath = caPath;
    }

    public record DownloadRequest(String username, String password) {}

    @PostMapping("/ca/download")
    public ResponseEntity<?> downloadCa(@RequestBody DownloadRequest req) throws Exception {
        if (req == null || req.username() == null || req.username().isBlank()
                || req.password() == null || req.password().isEmpty()) {
            return denied();
        }
        if (!isSuperuser(req.username().trim(), req.password())) {
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            return denied();
        }
        Path ca = Paths.get(caPath);
        if (!Files.isReadable(ca)) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "CA ainda nao emitida neste servidor."));
        }
        byte[] pem = Files.readAllBytes(ca);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.parseMediaType("application/x-pem-file"));
        h.setContentDisposition(ContentDisposition.attachment().filename("srvcloud-root-ca.crt").build());
        return ResponseEntity.ok().headers(h).body(pem);
    }

    private ResponseEntity<Map<String, String>> denied() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Credenciais inválidas."));
    }

    /** Abre conexao real e confere rolsuper do usuario corrente. */
    private boolean isSuperuser(String user, String pass) {
        try (Connection c = DriverManager.getConnection(pgUrl, user, pass);
             PreparedStatement s = c.prepareStatement(
                     "SELECT rolsuper FROM pg_roles WHERE rolname = current_user")) {
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        } catch (Exception e) {
            return false;
        }
    }
}
