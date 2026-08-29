#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Instalador Web Unificado - Astral Platform HCI
Uso: sudo python3 install.py
"""
import os, sys, socket, subprocess, time, json, threading, re, shutil, base64

APP_DIR = os.path.dirname(os.path.abspath(__file__))
PORT = 5000
HOST_IP = "0.0.0.0"
EXTRA_PORT = 9090

class InstallState:
    def __init__(self):
        self.progress = 0
        self.status = "Aguardando conexão..."
        self.package_name = ""
        self.lock = threading.Lock()

state = InstallState()

ORBITRON_BOLD_B64  = ""
ORBITRON_BLACK_B64 = ""
FONT_FILES = {
    "orbitron-bold.woff2":  ("https://cdn.jsdelivr.net/fontsource/fonts/orbitron@latest/latin-700-normal.woff2", ORBITRON_BOLD_B64),
    "orbitron-black.woff2": ("https://cdn.jsdelivr.net/fontsource/fonts/orbitron@latest/latin-900-normal.woff2", ORBITRON_BLACK_B64),
}

def ensure_fonts():
    fonts_dir = os.path.join(APP_DIR, "src", "main", "resources", "static", "fonts")
    os.makedirs(fonts_dir, exist_ok=True)
    for fn, (url, b64) in FONT_FILES.items():
        dst = os.path.join(fonts_dir, fn)
        if os.path.exists(dst) and os.path.getsize(dst) > 1000:
            continue
        if b64:
            with open(dst, "wb") as f: f.write(base64.b64decode(b64))
            print(f"[OK] Fonte {fn} gravada (base64 embutido).")
            continue
        r = subprocess.run(f"curl -fsSL -o {dst} {url}", shell=True, capture_output=True)
        if r.returncode == 0 and os.path.exists(dst) and os.path.getsize(dst) > 1000:
            print(f"[OK] Fonte {fn} baixada e self-hosted.")
        else:
            print(f"[AVISO] Não obtive {fn}; fallback de fonte.")

# ============================================================
# ARQUIVOS CANÔNICOS EMBUTIDOS
# ============================================================
ASTRAL_APP_JAVA = """package com.astral.main;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AstralApplication {
    public static void main(String[] args) {
        SpringApplication.run(AstralApplication.class, args);
    }
}
"""

HOME_CONTROLLER_JAVA = """package com.astral.main.controller;

import com.astral.main.model.DashboardButton;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import java.util.List;

@Controller
public class HomeController {

    @GetMapping("/inicio")
    public String home(Model model) {
        model.addAttribute("buttons", buildButtons());
        model.addAttribute("pageTitle", "ASTRAL PLATFORM");
        return "home";
    }

    private List<DashboardButton> buildButtons() {
        return List.of(
            new DashboardButton("dns", "DNS Management", "dns_management.png", "/dns"),
            new DashboardButton("firewall", "Firewall", "firewall.png", "/firewall"),
            new DashboardButton("proxy", "Proxy System", "proxy_system.png", "/proxy"),
            new DashboardButton("domain", "Domain Controllers", "domain_controllers.png", "/domain"),
            new DashboardButton("postgres", "PostgreSQL Admin", "postgresql_admin.png", "/postgres"),
            new DashboardButton("web", "Web Server Admin", "web_server_admin.png", "/web"),
            new DashboardButton("vm", "Virtual Machines", "virtual_machines.png", "/vm"),
            new DashboardButton("storage", "Storage", "storage.png", "/storage"),
            new DashboardButton("network", "Network Config & VLAN", "network_config_vlan.png", "/network"),
            new DashboardButton("terminal", "Terminal", "terminal.jpeg", "#terminal")
        );
    }
}
"""

LOGIN_CONTROLLER_JAVA = """package com.astral.main.controller;

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
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            String token = UUID.randomUUID().toString();
            response.put("success", true);
            response.put("token", token);
            response.put("message", "Autenticado com sucesso");
            return ResponseEntity.ok(response);
        } catch (SQLException e) {
            response.put("success", false);
            response.put("message", "Usuário ou senha inválidos.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
        }
    }
}
"""

DASHBOARD_BUTTON_JAVA = """package com.astral.main.model;

public record DashboardButton(String id, String label, String image, String route) {
}
"""

WS_CONFIG_JAVA = """package com.astral.main.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new TerminalWebSocketHandler(), "/ws/terminal")
                .setAllowedOrigins("*");
    }
}
"""

WS_HANDLER_JAVA = """package com.astral.main.config;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TerminalWebSocketHandler extends TextWebSocketHandler {

    private final Map<String, Process> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("script", "-qfc", "/bin/bash", "/dev/null");
        pb.environment().put("TERM", "dumb");
        pb.directory(new File("/root"));
        Process proc = pb.start();
        sessions.put(session.getId(), proc);
        Thread t = new Thread(() -> {
            try (InputStream in = proc.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                    synchronized (session) {
                        if (session.isOpen())
                            session.sendMessage(new TextMessage(
                                new String(buf, 0, n, StandardCharsets.UTF_8)));
                    }
                }
            } catch (IOException ignored) {}
        });
        t.setDaemon(true);
        t.start();
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Process p = sessions.get(session.getId());
        if (p != null && p.isAlive()) {
            p.getOutputStream().write(message.getPayload().getBytes(StandardCharsets.UTF_8));
            p.getOutputStream().flush();
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Process p = sessions.remove(session.getId());
        if (p != null) p.destroyForcibly();
    }
}
"""

# NOVO: auto-provisiona usuário/database se as credenciais não existirem no PG
DB_BOOTSTRAP_JAVA = """package com.astral.main.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

@Component
public class DatabaseBootstrap {

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${spring.datasource.password}")
    private String password;

    @Value("${spring.datasource.url}")
    private String url;

    @EventListener(ApplicationReadyEvent.class)
    public void ensureUserAndDatabase() {
        try (var conn = DriverManager.getConnection(url, username, password)) {
            System.out.println("[BOOTSTRAP] Conexão com o banco 'astral' OK.");
            return;
        } catch (Exception e) {
            System.out.println("[BOOTSTRAP] Credenciais ausentes no PostgreSQL; auto-criando...");
        }
        try {
            String sql = "DO $$ BEGIN "
                + "IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '" + username + "') THEN "
                + "CREATE ROLE " + username + " LOGIN SUPERUSER PASSWORD '" + password + "'; "
                + "ELSE "
                + "ALTER ROLE " + username + " WITH LOGIN SUPERUSER PASSWORD '" + password + "'; "
                + "END IF; END $$;\\n"
                + "SELECT 'CREATE DATABASE astral OWNER " + username + "' "
                + "WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'astral')\\\\gexec\\n";
            Path tmp = Path.of("/tmp/astral-bootstrap.sql");
            Files.writeString(tmp, sql);
            Process p = new ProcessBuilder("su", "-", "postgres", "-c",
                    "psql -f /tmp/astral-bootstrap.sql")
                    .redirectErrorStream(true).start();
            p.waitFor();
            System.out.println("[BOOTSTRAP] Auto-provisionamento de usuário/database executado.");
        } catch (Exception e) {
            System.out.println("[BOOTSTRAP] Falha no auto-provisionamento: " + e.getMessage());
        }
    }
}
"""

DEFAULT_HOME_HTML = r"""<!DOCTYPE html>
<html lang="pt-br" xmlns:th="http://www.thymeleaf.org">
<head>
<meta charset="UTF-8">
<title th:text="${pageTitle}">ASTRAL PLATFORM</title>
<style th:inline="css">
  @font-face{font-family:'Orbitron';font-style:normal;font-weight:700;font-display:swap;
             src:url([[@{'/fonts/orbitron-bold.woff2'}]]) format('woff2')}
  @font-face{font-family:'Orbitron';font-style:normal;font-weight:900;font-display:swap;
             src:url([[@{'/fonts/orbitron-black.woff2'}]]) format('woff2')}
  html,body{height:100%;margin:0}
  body{background:#05070d url([[@{'/images/Fundo.png'}]]) no-repeat center center fixed;
       background-size:cover;font-family:'Segoe UI',sans-serif;color:#fff}
  h1{position:fixed;top:4%;left:4%;margin:0;text-align:left;
     font-family:'Orbitron','Segoe UI',sans-serif;font-weight:900;
     letter-spacing:.28em;font-size:clamp(18px,2.4vw,36px);
     color:#eef5ff;text-shadow:0 0 6px #9fd8ff,0 0 18px #4da6ff,0 0 42px #1668ff}
  .grid{position:fixed;inset:25% 20%;--gap:18px;--cols:3;
        display:flex;flex-wrap:wrap;gap:var(--gap);
        justify-content:center;align-content:center}
  .card{--c:#8fb7ff;
        flex:0 0 calc((100% - (var(--cols) - 1)*var(--gap))/var(--cols) - .5px);
        aspect-ratio:3/1;
        border:2px solid var(--c);border-radius:14px;background:rgba(4,10,22,.66);
        box-shadow:0 0 10px var(--c),inset 0 0 22px rgba(0,0,0,.55);
        color:#fff;text-decoration:none;padding:10px 16px;
        display:flex;flex-direction:column;gap:6px;min-height:0;transition:transform .15s}
  .card:hover{transform:translateY(-3px)}
  .card .label{font-weight:600;font-size:clamp(13px,1.3vw,21px);text-align:left}
  .card img{flex:1;min-height:0;width:100%;object-fit:contain}
  .card[data-id="dns"]{--c:#57e389}.card[data-id="firewall"]{--c:#ff5c5c}
  .card[data-id="proxy"]{--c:#ffb347}.card[data-id="domain"]{--c:#ff7b7b}
  .card[data-id="postgres"]{--c:#4fa3ff}.card[data-id="web"]{--c:#c77bff}
  .card[data-id="vm"]{--c:#59d9e8}.card[data-id="storage"]{--c:#ffc16b}
  .card[data-id="network"]{--c:#63e6a4}.card[data-id="terminal"]{--c:#dfe6ee}
  .hidden{display:none}
  .modal{position:fixed;inset:0;background:rgba(0,0,0,.6);display:flex;align-items:center;justify-content:center;z-index:50}
  .termbox{width:min(880px,92vw);height:min(560px,82vh);background:#0b0f14;border:1px solid #3fa9ff;border-radius:10px;display:flex;flex-direction:column;box-shadow:0 0 24px #1668ff}
  .termhead{display:flex;justify-content:space-between;align-items:center;padding:8px 12px;background:#101820;border-bottom:1px solid #234}
  .termhead span:first-child{font-family:'Orbitron',sans-serif;letter-spacing:.2em;color:#9fd8ff}
  .termhead button{background:#16324a;color:#cfe9ff;border:1px solid #3fa9ff;border-radius:6px;padding:4px 10px;cursor:pointer;margin-left:6px}
  #termOut{flex:1;margin:0;padding:10px;overflow:auto;background:#000;color:#d7ffd7;font:13px/1.35 Consolas,Monaco,monospace;white-space:pre-wrap}
  #termIn{border:none;outline:none;background:#050a0f;color:#d7ffd7;padding:10px;font:13px Consolas,monospace;border-top:1px solid #234}
</style>
</head>
<body>
<h1 th:text="${pageTitle}">ASTRAL PLATFORM</h1>
<div class="grid" id="grid">
  <a class="card" th:each="btn : ${buttons}" th:href="@{${btn.route}}" th:data-id="${btn.id}" th:title="${btn.label}">
    <div class="label" th:text="${btn.label}">Card</div>
    <img th:src="@{'/images/' + ${btn.image}}" alt="" onerror="this.style.display='none'">
  </a>
</div>
<div id="termModal" class="modal hidden">
  <div class="termbox">
    <div class="termhead">
      <span>ASTRAL TERMINAL</span>
      <span>
        <button id="detachBtn" type="button">Destacar</button>
        <button id="closeBtn" type="button">X</button>
      </span>
    </div>
    <pre id="termOut"></pre>
    <input id="termIn" autocomplete="off">
  </div>
</div>
<script>
function layout(n){if(n<=1)return{c:1};var c=0,i,k;
for(i=0;i<5;i++){k=[6,5,4,3,2][i];if(n%k===0){c=k;break}}
if(!c)for(i=0;i<5;i++){k=[6,5,4,3,2][i];if((n-1)%k===0){c=k;break}}
return{c:c||3}}
(function(){var g=document.getElementById('grid');
g.style.setProperty('--cols',layout(g.querySelectorAll('.card').length).c)})();
function clean(s){return s.replace(/\x1b\[[0-9;?]*[a-zA-Z]/g,'').replace(/\r/g,'')}
function attach(out,inp){var ws=new WebSocket((location.protocol==='https:'?'wss://':'ws://')+location.host+'/ws/terminal');
ws.onmessage=function(e){out.textContent+=clean(e.data);out.scrollTop=out.scrollHeight};
ws.onclose=function(){out.textContent+='\n[conexao encerrada]\n'};
inp.addEventListener('keydown',function(e){if(e.key==='Enter'&&ws.readyState===1){ws.send(inp.value+'\n');inp.value=''}});
return ws}
var ws=null;
var modal=document.getElementById('termModal'),out=document.getElementById('termOut'),inp=document.getElementById('termIn');
document.querySelectorAll('.card').forEach(function(a){a.addEventListener('click',function(e){
if(a.dataset.id==='terminal'){e.preventDefault();modal.classList.remove('hidden');out.textContent='';ws=attach(out,inp);inp.focus()}})});
document.getElementById('closeBtn').onclick=function(){if(ws)ws.close();modal.classList.add('hidden')};
document.getElementById('detachBtn').onclick=function(){if(ws)ws.close();modal.classList.add('hidden');
window.open('/terminal-popup.html','astralTerm','width=960,height=600')};
</script>
</body>
</html>
"""

# ============================================================
# FUNÇÕES AUXILIARES
# ============================================================
def check_internet():
    try:
        socket.create_connection(("8.8.8.8", 53), timeout=3)
        return True
    except OSError:
        return False

def get_local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(('8.8.8.8', 80)); ip = s.getsockname()[0]
    except Exception:
        ip = '127.0.0.1'
    finally:
        s.close()
    return ip

def detect_distro():
    try:
        with open('/etc/os-release', 'r') as f:
            content = f.read().lower()
        if 'debian' in content or 'ubuntu' in content: return 'debian'
        elif 'rhel' in content or 'fedora' in content or 'almalinux' in content \
             or 'centos' in content or 'rocky' in content: return 'rhel'
        elif 'arch' in content or 'manjaro' in content: return 'arch'
    except FileNotFoundError:
        pass
    return 'unknown'

def detect_pg_service():
    for name in ["postgresql", "postgresql-server", "postgresql-16", "postgresql-15",
                 "postgresql-14", "postgresql-13", "postgresql-12"]:
        r = subprocess.run(["systemctl", "cat", name], capture_output=True)
        if r.returncode == 0: return name
    return "postgresql"

def configure_firewall():
    ports_to_open = [22, 80, 443, 3000, PORT, 5173, 5432, 8081, EXTRA_PORT]
    print("[INFO] Exterminando firewalld/ufw para uso exclusivo do iptables...")
    subprocess.run("systemctl stop firewalld ufw 2>/dev/null || true", shell=True)
    subprocess.run("systemctl disable firewalld ufw 2>/dev/null || true", shell=True)
    print(f"[INFO] Injetando portas da aplicação no firewall: {ports_to_open}...")
    try:
        rules_changed = False
        for p in ports_to_open:
            while True:
                d = subprocess.run(['iptables', '-D', 'INPUT', '-p', 'tcp', '--dport', str(p), '-j', 'ACCEPT'], capture_output=True)
                if d.returncode != 0: break
            subprocess.run(['iptables', '-I', 'INPUT', '1', '-p', 'tcp', '--dport', str(p), '-j', 'ACCEPT'], check=True, capture_output=True)
            rules_changed = True
        if rules_changed:
            if os.path.exists('/etc/init.d/iptables-persistent') or os.path.exists('/usr/sbin/netfilter-persistent'):
                subprocess.run(['sh', '-c', 'iptables-save > /etc/iptables/rules.v4'], check=True, capture_output=True)
            elif os.path.exists('/etc/sysconfig/iptables'):
                subprocess.run(['sh', '-c', 'iptables-save > /etc/sysconfig/iptables'], check=True, capture_output=True)
        print("[OK] Portas da aplicação liberadas no iptables.")
    except Exception as e:
        print(f"[AVISO] Falha ao injetar portas no iptables: {e}")

def ensure_dependencies_installed():
    print("[INFO] Assegurando dependências globais de Python (Flask, Psycopg2)...")
    distro = detect_distro()
    pip_install_cmd = ""
    if distro == 'debian': pip_install_cmd = "apt-get update && apt-get install -y python3-pip python3-psycopg2"
    elif distro == 'rhel': pip_install_cmd = "dnf install -y python3-pip python3-psycopg2"
    elif distro == 'arch': pip_install_cmd = "pacman -Sy --noconfirm python-pip python-psycopg2"
    if pip_install_cmd: subprocess.run(pip_install_cmd, shell=True, capture_output=True)
    res = subprocess.run("pip3 install flask psycopg2-binary --break-system-packages", shell=True, capture_output=True, text=True)
    if res.returncode != 0:
        subprocess.run("pip3 install flask psycopg2-binary", shell=True, capture_output=True)

def inject_java_pom_template():
    pom_path = os.path.join(APP_DIR, "pom.xml")
    if not os.path.exists(pom_path):
        print("[INFO] Injetando pom.xml (Web + Thymeleaf + WebSocket + JPA + Postgres)...")
        pom_content = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.0</version>
        <relativePath/>
    </parent>
    <groupId>com.astral</groupId>
    <artifactId>astral-platform</artifactId>
    <version>1.0.0</version>
    <name>astral-platform</name>
    <properties>
        <java.version>21</java.version>
    </properties>
    <dependencies>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-thymeleaf</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-websocket</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
        <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>runtime</scope></dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin>
        </plugins>
    </build>
</project>"""
        with open(pom_path, "w") as f: f.write(pom_content)
        print("[OK] pom.xml injetado na raiz do projeto.")
    else:
        try:
            with open(pom_path, "r") as f: content = f.read()
            missing = []
            if "spring-boot-starter-thymeleaf" not in content:
                missing.append("        <dependency>\n            <groupId>org.springframework.boot</groupId>\n            <artifactId>spring-boot-starter-thymeleaf</artifactId>\n        </dependency>\n")
            if "spring-boot-starter-websocket" not in content:
                missing.append("        <dependency>\n            <groupId>org.springframework.boot</groupId>\n            <artifactId>spring-boot-starter-websocket</artifactId>\n        </dependency>\n")
            if missing:
                content = content.replace("    </dependencies>", "".join(missing) + "    </dependencies>", 1)
                with open(pom_path, "w") as f: f.write(content)
                print("[OK] Dependências faltantes adicionadas ao pom.xml existente.")
        except Exception as e:
            print(f"[AVISO] Não foi possível atualizar o pom.xml: {e}")

def ensure_project_layout():
    print("[INFO] Garantindo layout Maven correto (src/main/...)...")
    base = os.path.join(APP_DIR, "src", "main", "java", "com", "astral", "main")
    tpl_dir = os.path.join(APP_DIR, "src", "main", "resources", "templates")
    imgs_dir = os.path.join(APP_DIR, "src", "main", "resources", "static", "images")
    fonts_dir = os.path.join(APP_DIR, "src", "main", "resources", "static", "fonts")
    for d in (base, os.path.join(base, "controller"), os.path.join(base, "model"),
              os.path.join(base, "config"), tpl_dir, imgs_dir, fonts_dir):
        os.makedirs(d, exist_ok=True)

    files = {
        os.path.join(base, "AstralApplication.java"): ASTRAL_APP_JAVA,
        os.path.join(base, "controller", "HomeController.java"): HOME_CONTROLLER_JAVA,
        os.path.join(base, "controller", "LoginController.java"): LOGIN_CONTROLLER_JAVA,
        os.path.join(base, "model", "DashboardButton.java"): DASHBOARD_BUTTON_JAVA,
        os.path.join(base, "config", "WebSocketConfig.java"): WS_CONFIG_JAVA,
        os.path.join(base, "config", "TerminalWebSocketHandler.java"): WS_HANDLER_JAVA,
        os.path.join(base, "config", "DatabaseBootstrap.java"): DB_BOOTSTRAP_JAVA,
        os.path.join(tpl_dir, "home.html"): DEFAULT_HOME_HTML,
    }
    for path, content in files.items():
        rel = os.path.relpath(path, APP_DIR)
        if not os.path.exists(path):
            with open(path, "w", encoding="utf-8") as f: f.write(content)
            print(f"[OK] {rel} criado no layout correto.")
        else:
            print(f"[OK] {rel} já existe (mantido, sem sobrescrever).")

    login_imgs = os.path.join(APP_DIR, "fabric", "frontend", "login", "images")
    os.makedirs(login_imgs, exist_ok=True)
    src_fundo = os.path.join(imgs_dir, "Fundo.png")
    dst_fundo = os.path.join(login_imgs, "Fundo.png")
    if os.path.exists(src_fundo) and not os.path.exists(dst_fundo):
        shutil.copy2(src_fundo, dst_fundo)
        print("[OK] Fundo.png copiado para o frontend de login (Nginx).")

def build_and_deploy_spring_boot():
    print("\n[INFO] ========== COMPILANDO SPRING BOOT ==========")
    pom_path = os.path.join(APP_DIR, "pom.xml")
    if not os.path.exists(pom_path):
        print("[ERRO] pom.xml não encontrado. Abortando compilação.")
        return False
    print("[INFO] Compilando projeto com Maven (mvn package)...")
    env = os.environ.copy()
    if os.environ.get("JAVA_HOME"): env["JAVA_HOME"] = os.environ["JAVA_HOME"]
    result = subprocess.run(f"cd {APP_DIR} && mvn -B -DskipTests clean package",
                            shell=True, capture_output=True, text=True, env=env)
    if result.returncode != 0:
        print("[ERRO] Falha na compilação Maven:")
        print(result.stdout[-1500:]); print(result.stderr[-1500:])
        return False
    print("[OK] Compilação Maven concluída com sucesso.")
    target_dir = os.path.join(APP_DIR, "target")
    jar_files = [f for f in os.listdir(target_dir) if f.endswith(".jar") and "original" not in f]
    if not jar_files:
        print("[ERRO] Nenhum .jar encontrado em target/")
        return False
    jar_file = os.path.join(target_dir, jar_files[0])
    print(f"[OK] JAR localizado: {jar_file}")

    # Deploy em /opt (fora do /home, sem problemas de permissão/SELinux)
    prod_dir = "/opt/astral-platform"
    subprocess.run(f"mkdir -p {prod_dir}", shell=True, capture_output=True)
    subprocess.run(f"cp {jar_file} {prod_dir}/", shell=True, capture_output=True)
    subprocess.run(f"chown -R root:root {prod_dir} && chmod 755 {prod_dir}", shell=True, capture_output=True)
    print(f"[OK] JAR copiado para {prod_dir}/")

    # Config externa em /etc/astral (sobrevive a rebuilds)
    config_dir = "/etc/astral"
    subprocess.run(f"mkdir -p {config_dir}", shell=True, capture_output=True)
    props = os.path.join(config_dir, "application.properties")
    if not os.path.exists(props):
        with open(props, "w") as f:
            f.write("""# Configuracao Astral Platform (Spring Boot)
server.port=8081
spring.datasource.url=jdbc:postgresql://localhost:5432/astral
spring.datasource.username=astral
spring.datasource.password=astral
spring.datasource.driver-class-name=org.postgresql.Driver
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect
spring.jackson.serialization.fail-on-empty-beans=false
""")
        print(f"[OK] application.properties padrão criado em {props} (usuário astral/astral).")

    service_content = f"""[Unit]
Description=Astral Platform Spring Boot Application
After=network.target postgresql.service
Requires=postgresql.service

[Service]
Type=simple
User=root
WorkingDirectory={prod_dir}
ExecStart=/usr/bin/java -jar {prod_dir}/astral-platform-1.0.0.jar --spring.config.location=file:{props}
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
"""
    try:
        with open("/etc/systemd/system/astral-platform.service", "w") as f: f.write(service_content)
        print("[OK] Service astral-platform criado/atualizado.")
    except Exception as e:
        print(f"[ERRO] Falha ao criar systemd service: {e}")
        return False
    subprocess.run("systemctl daemon-reload", shell=True, capture_output=True)
    subprocess.run("systemctl enable astral-platform.service", shell=True, capture_output=True)
    subprocess.run("systemctl restart astral-platform.service", shell=True, capture_output=True)
    print("[INFO] Aguardando Spring Boot inicializar...")
    for i in range(30):
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            r = sock.connect_ex(('127.0.0.1', 8081)); sock.close()
            if r == 0:
                print("[OK] Spring Boot está rodando na porta 8081!")
                return True
        except Exception: pass
        time.sleep(1)
    print("[AVISO] Spring Boot pode não ter subido. Verifique: journalctl -u astral-platform.service")
    return False

def fix_ownership():
    uid = os.stat(APP_DIR).st_uid
    gid = os.stat(APP_DIR).st_gid
    subprocess.run(f"chown -R {uid}:{gid} {APP_DIR}", shell=True, capture_output=True)
    print(f"[OK] chown -R {uid}:{gid} aplicado (arquivos devolvidos ao usuário do projeto).")

def inject_spring_properties(username, password):
    config_dir = "/etc/astral"
    subprocess.run(f"mkdir -p {config_dir}", shell=True, capture_output=True)
    properties_path = os.path.join(config_dir, "application.properties")
    properties_content = f"""# Configuracao Astral Platform (Spring Boot)
server.port=8081
spring.datasource.url=jdbc:postgresql://localhost:5432/astral
spring.datasource.username={username}
spring.datasource.password={password}
spring.datasource.driver-class-name=org.postgresql.Driver
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect
spring.jackson.serialization.fail-on-empty-beans=false
"""
    try:
        with open(properties_path, "w") as f: f.write(properties_content)
        subprocess.run(f"chmod 640 {properties_path} && chown root:root {properties_path}", shell=True, capture_output=True)
        print(f"[OK] application.properties injetado em: {properties_path}")
    except Exception as e:
        print(f"[AVISO] Falha ao criar application.properties: {e}")

def run_command_stream(cmd, shell=True):
    print(f"\n[SISTEMA] Executando: {cmd}")
    process = subprocess.Popen(cmd, shell=shell, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True, bufsize=1)
    for line in process.stdout:
        match = re.search(r'(?:unpacking|installing|upgrading|processing)\s+([a-zA-Z0-9\-_.]+)', line, re.IGNORECASE)
        if match:
            with state.lock: state.package_name = match.group(1)
    process.wait()
    return process.returncode == 0

def update_progress(percent, status_msg):
    with state.lock:
        state.progress = percent
        state.status = status_msg

# ============================================================
# THREAD PRINCIPAL
# ============================================================
def installation_thread():
    time.sleep(2)
    configure_firewall()
    inject_java_pom_template()
    ensure_project_layout()
    ensure_fonts()

    distro = detect_distro()
    if distro == 'debian': update_cmd, install_cmd_base = "apt-get update", "DEBIAN_FRONTEND=noninteractive apt-get install -y"
    elif distro == 'rhel': update_cmd, install_cmd_base = "dnf makecache", "dnf install -y"
    elif distro == 'arch': update_cmd, install_cmd_base = "pacman -Sy", "pacman -S --noconfirm"
    else:
        update_progress(0, "Erro: Distro não detectada.")
        return

    update_progress(5, "Sincronizando repositórios do Linux...")
    run_command_stream(update_cmd)
    update_progress(15, "Repositórios sincronizados.")

    update_progress(20, "Verificando Node.js, NPM e dependências JS...")
    if not (shutil.which("node") or shutil.which("nodejs")) or not shutil.which("npm"):
        node_pkg = "nodejs npm curl" if distro != 'rhel' else "nodejs nodejs-npm curl"
        run_command_stream(f"{install_cmd_base} {node_pkg}")
    subprocess.run("npm install -g pg express cors 2>/dev/null || true", shell=True)
    update_progress(35, "Ambiente JS e dependências prontos.")

    update_progress(40, "Avaliando instalação do Oracle Java 21 LTS...")
    java_check = subprocess.run("java -version", shell=True, capture_output=True, text=True)
    if "Oracle" not in (java_check.stderr + java_check.stdout):
        if distro == 'debian':
            run_command_stream("curl -s -L -o /tmp/jdk.deb https://download.oracle.com/java/21/latest/jdk-21_linux-x64_bin.deb")
            run_command_stream("DEBIAN_FRONTEND=noninteractive dpkg -i /tmp/jdk.deb")
        elif distro == 'rhel':
            run_command_stream("dnf install -y https://download.oracle.com/java/21/latest/jdk-21_linux-x64_bin.rpm")
        elif distro == 'arch':
            run_command_stream("curl -s -L -o /tmp/jdk.tar.gz https://download.oracle.com/java/21/latest/jdk-21_linux-x64_bin.tar.gz")
            run_command_stream("tar -xzf /tmp/jdk.tar.gz -C /opt/")
            subprocess.run("ln -sf /opt/jdk-21*/bin/java /usr/bin/java", shell=True)
    update_progress(50, "Oracle Java configurado.")

    update_progress(52, "Instalando o Maven...")
    if not shutil.which("mvn"):
        if distro == 'debian': run_command_stream("DEBIAN_FRONTEND=noninteractive apt-get install -y maven")
        elif distro == 'rhel': run_command_stream("dnf install -y maven")
        elif distro == 'arch': run_command_stream("pacman -S --noconfirm maven")
    java_home = None
    rl = subprocess.run("readlink -f $(which java)", shell=True, capture_output=True, text=True)
    if rl.returncode == 0 and rl.stdout.strip():
        java_bin = rl.stdout.strip()
        java_home = os.path.dirname(os.path.dirname(java_bin))
        subprocess.run(["alternatives", "--set", "java", java_bin], capture_output=True)
        try:
            with open("/etc/profile.d/java_home.sh", "w") as f:
                f.write(f"export JAVA_HOME={java_home}\nexport PATH=$JAVA_HOME/bin:$PATH\n")
            subprocess.run("chmod +x /etc/profile.d/java_home.sh", shell=True)
        except Exception: pass
        os.environ["JAVA_HOME"] = java_home
    update_progress(53, "Pré-baixando dependências do Spring Boot (Maven)...")
    env = os.environ.copy()
    if shutil.which("mvn") and os.path.exists(os.path.join(APP_DIR, "pom.xml")):
        subprocess.run(f"cd {APP_DIR} && mvn -B -q dependency:go-offline", shell=True, capture_output=True, env=env)
    update_progress(54, "Maven configurado.")

    update_progress(55, "Instalando o motor de banco de dados (PostgreSQL)...")
    pg_pkg = "postgresql postgresql-contrib"
    if distro == 'rhel': pg_pkg = "postgresql postgresql-server postgresql-contrib"
    run_command_stream(f"{install_cmd_base} {pg_pkg}")
    update_progress(65, "PostgreSQL instalado.")

    update_progress(70, "Instalando e configurando proxy Nginx...")
    success_nginx = True
    if not shutil.which("nginx"): success_nginx = run_command_stream(f"{install_cmd_base} nginx")
    if success_nginx:
        subprocess.run("systemctl disable --now httpd apache2 2>/dev/null", shell=True, capture_output=True)
        subprocess.run("sed -i 's/.*listen.*\\[::\\]:80.*/#&/' /etc/nginx/nginx.conf 2>/dev/null", shell=True)
        frontend_path = os.path.join(APP_DIR, "fabric", "frontend")
        subprocess.run(f"chmod -R 755 {frontend_path} 2>/dev/null", shell=True)
        cur = frontend_path
        while cur != '/':
            subprocess.run(f"chmod o+x {cur} 2>/dev/null", shell=True)
            cur = os.path.dirname(cur)
        if distro == 'rhel':
            subprocess.run("setsebool -P httpd_can_network_connect 1 2>/dev/null", shell=True)
            subprocess.run(f"chcon -Rt httpd_sys_content_t {frontend_path} 2>/dev/null", shell=True)
        nginx_conf = f"""server {{
    listen 80 default_server;
    server_name _;

    root {frontend_path}/login;
    index index.html;

    location / {{
        try_files $uri $uri/ =404;
    }}

    location /images/ {{
        try_files $uri @spring;
    }}
    location /fonts/ {{
        try_files $uri @spring;
    }}

    location @spring {{
        proxy_pass http://127.0.0.1:8081;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }}

    location /ws/ {{
        proxy_pass http://127.0.0.1:8081;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }}

    location /api/ {{
        proxy_pass http://127.0.0.1:8081;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }}

    location /inicio {{
        proxy_pass http://127.0.0.1:8081;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }}

    location /dns {{
        proxy_pass http://127.0.0.1:8053;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }}

    location /dns/pihole {{
        proxy_pass http://127.0.0.1:8081/admin;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }}

    location /firewall {{
        proxy_pass http://127.0.0.1:8040;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }}

    location /proxy {{
        proxy_pass http://127.0.0.1:8085;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }}

    location /domain {{
        proxy_pass http://127.0.0.1:8090;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }}

    location /postgres {{
        proxy_pass http://127.0.0.1:5433;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }}

    location /web {{
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }}

    location /vm {{
        proxy_pass http://127.0.0.1:8070;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }}

    location /storage {{
        proxy_pass http://127.0.0.1:8060;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }}

    location /network {{
        proxy_pass http://127.0.0.1:8024;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }}

    location /alerts {{
        proxy_pass http://127.0.0.1:8010;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }}

    location /telemetry {{
        proxy_pass http://127.0.0.1:8015;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }}
}}"""
        try:
            conf_path = "/etc/nginx/conf.d/astral.conf"
            if distro == 'debian': conf_path = "/etc/nginx/sites-available/astral.conf"
            with open(conf_path, "w") as f: f.write(nginx_conf)
            if distro == 'debian':
                subprocess.run("ln -sf /etc/nginx/sites-available/astral.conf /etc/nginx/sites-enabled/", shell=True)
                subprocess.run("rm -f /etc/nginx/sites-enabled/default", shell=True)
            else:
                subprocess.run("rm -f /etc/nginx/conf.d/default.conf", shell=True)
        except Exception as e:
            print(f"[ERRO] Falha ao escrever configuração do Nginx: {e}")
        subprocess.run("systemctl enable nginx", shell=True, capture_output=True)
        subprocess.run("systemctl restart nginx", shell=True, capture_output=True)
        print("[OK] Nginx inicializado e configurado.")
    update_progress(80, "Nginx configurado.")

    update_progress(85, "Ativando serviços de dados e ajustando SELinux...")
    svc_name = "postgresql"
    if distro == 'rhel':
        svc_name = detect_pg_service()
        subprocess.run(f"systemctl stop {svc_name}", shell=True, capture_output=True)
        pgdata_check = subprocess.run("ls -A /var/lib/pgsql/data", shell=True, capture_output=True, text=True)
        if not pgdata_check.stdout.strip():
            subprocess.run("chown -R postgres:postgres /var/lib/pgsql", shell=True, capture_output=True)
            if shutil.which("restorecon"): subprocess.run("restorecon -Rv /var/lib/pgsql", shell=True, capture_output=True)
            subprocess.run("/usr/bin/postgresql-setup --initdb", shell=True, capture_output=True)
        subprocess.run("chown -R postgres:postgres /var/lib/pgsql/data", shell=True, capture_output=True)
        subprocess.run("chmod 700 /var/lib/pgsql/data", shell=True, capture_output=True)
        subprocess.run("grep -q \"^listen_addresses\" /var/lib/pgsql/data/postgresql.conf || echo \"listen_addresses = '*'\" >> /var/lib/pgsql/data/postgresql.conf", shell=True)
        subprocess.run("grep -q '0.0.0.0/0' /var/lib/pgsql/data/pg_hba.conf || echo 'host    all             all             0.0.0.0/0               md5' >> /var/lib/pgsql/data/pg_hba.conf", shell=True)
        subprocess.run("grep -q '^host.*127.0.0.1/32.*md5' /var/lib/pgsql/data/pg_hba.conf || sed -i '1i host    all             all             127.0.0.1/32            md5' /var/lib/pgsql/data/pg_hba.conf", shell=True)
        subprocess.run(f"systemctl enable {svc_name}", shell=True, capture_output=True)
        subprocess.run(f"systemctl start {svc_name}", shell=True, capture_output=True)
    elif distro == 'arch':
        if not os.path.exists("/var/lib/postgres/data/PG_VERSION"):
            subprocess.run("sudo -u postgres initdb -D /var/lib/postgres/data", shell=True, capture_output=True)
        subprocess.run(f"systemctl enable --now {svc_name}", shell=True, capture_output=True)
    else:
        subprocess.run(f"systemctl restart {svc_name}", shell=True, capture_output=True)

    update_progress(88, "Organizando projeto e aplicando chown...")
    fix_ownership()

    update_progress(90, "Compilando Spring Boot e criando systemd service...")
    build_and_deploy_spring_boot()

    update_progress(95, "Aguardando o serviço de banco de dados iniciar...")
    db_ready = False
    for i in range(30):
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            if sock.connect_ex(('127.0.0.1', 5432)) == 0:
                db_ready = True; sock.close(); break
            sock.close()
        except Exception: pass
        time.sleep(1)
    if db_ready:
        update_progress(100, "Instalação concluída! Configure o banco na próxima tela.")
    else:
        update_progress(100, "Falha crítica: PostgreSQL não está escutando na porta 5432.")

# ============================================================
# BLOCO PRINCIPAL
# ============================================================
if __name__ == '__main__':
    if os.geteuid() != 0:
        print("ERRO: Este script deve ser executado com sudo.", flush=True)
        sys.exit(1)

    ensure_dependencies_installed()
    frontend_dir = os.path.join(APP_DIR, 'fabric', 'frontend')

    try:
        import flask
        from flask import Flask, send_from_directory, request, jsonify, Response
    except ImportError:
        print("\n[CRÍTICO] Falha ao importar o Flask mesmo após tentar instalar.", flush=True)
        sys.exit(1)

    app = Flask(__name__, static_folder=frontend_dir, static_url_path='')

    @app.route('/')
    def index():
        if os.path.exists(os.path.join(frontend_dir, 'install.html')):
            return send_from_directory(frontend_dir, 'install.html')
        return send_from_directory(frontend_dir, 'index.html')

    @app.route('/api/stream')
    def stream():
        def generate():
            last_pkg = ""
            while True:
                with state.lock:
                    display_status = state.status
                    if state.package_name and state.package_name != last_pkg:
                        display_status = f"{state.status} ({state.package_name})"
                        last_pkg = state.package_name
                    data = {"porcentagem": state.progress, "status": display_status, "package": state.package_name}
                    if state.progress >= 100:
                        data["redirect_url"] = f"http://{get_local_ip()}"
                yield f"data: {json.dumps(data)}\n\n"
                if state.progress >= 100: break
                time.sleep(0.5)
        return Response(generate(), mimetype='text/event-stream')

    @app.route('/api/setup-db', methods=['POST'])
    def setup_db():
        data = request.json
        username = data.get('username')
        password = data.get('password')
        if not username or not password: return jsonify({"error": "Dados inválidos"}), 400
        cmd_user = f'sudo -i -u postgres psql -c "CREATE USER {username} WITH PASSWORD \'{password}\' SUPERUSER;"'
        cmd_db = f'sudo -i -u postgres psql -c "CREATE DATABASE astral OWNER {username};"'
        try:
            subprocess.run(cmd_user, shell=True, capture_output=True, text=True)
            subprocess.run(cmd_db, shell=True, capture_output=True, text=True)
            inject_spring_properties(username, password)
            subprocess.run("systemctl restart astral-platform.service", shell=True, capture_output=True)
            print("\n[INFO] Banco configurado! Agendando encerramento do instalador em 60s...", flush=True)
            threading.Timer(60.0, lambda: os._exit(0)).start()
            return jsonify({"success": True, "message": "Banco 'astral' criado e Spring Boot configurado!",
                            "redirect_url": f"http://{get_local_ip()}"})
        except Exception as e:
            return jsonify({"error": str(e)}), 500

    @app.route('/api/shutdown', methods=['POST'])
    def shutdown():
        print("\n[INFO] Sinal de encerramento manual recebido. Desligando...", flush=True)
        threading.Timer(1.0, lambda: os._exit(0)).start()
        return jsonify({"success": True})

    local_ip = get_local_ip()
    print("\n" + "="*60, flush=True)
    print("[ASTRAL PLATFORM] INSTALADOR WEB UNIFICADO", flush=True)
    print("="*60, flush=True)
    print(f"[AÇÃO] Abra o navegador e acesse:", flush=True)
    print(f"[ENDEREÇO] http://{local_ip}:{PORT}", flush=True)
    print("="*60 + "\n", flush=True)

    t = threading.Thread(target=installation_thread)
    t.daemon = True
    t.start()
    app.run(host=HOST_IP, port=PORT, threaded=True)
