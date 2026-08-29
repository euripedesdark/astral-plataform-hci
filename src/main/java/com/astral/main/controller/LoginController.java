package com.astral.main.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> credentials) {
        String username = credentials.get("username");
        String password = credentials.get("password");

        Map<String, Object> response = new HashMap<>();
        String jdbcUrl = "jdbc:postgresql://localhost:5432/astral";

        // Valida as credenciais conectando diretamente no PostgreSQL configurado
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            String token = UUID.randomUUID().toString();

            response.put("success", true);
            response.put("token", token);
            response.put("message","Autenticado com sucesso");

            return ResponseEntity.ok(response);

        } catch (SQLException e) {
            response.put("success", false);
            response.put("message","Usuário ou senha inválidos.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
        }
    }
}
