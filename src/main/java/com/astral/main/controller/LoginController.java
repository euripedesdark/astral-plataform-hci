package com.astral.main.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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
            String jdbcUrl = "jdbc:postgresql://127.0.0.1:5432/astral";

            try (Connection c = DriverManager.getConnection(jdbcUrl, username, password)) {
                response.put("success", true);
                response.put("token", UUID.randomUUID().toString());
                response.put("message", "Autenticado com sucesso");
                return ResponseEntity.ok(response);
            } catch (SQLException e) {
                response.put("success", false);
                response.put("message", "Falha de autenticação: " + e.getMessage());
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
            }
        });
    }
}
