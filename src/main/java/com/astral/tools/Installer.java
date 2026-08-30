package com.astral.tools;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Installer {

    private static final int PORT = 5000;
    private static final AtomicInteger progress = new AtomicInteger(0);
    private static String status = "Aguardando conexão...";

    public static void main(String[] args) throws Exception {
        if (!isRoot()) { System.err.println("ERRO: Execute com sudo"); System.exit(1); }
        String localIP = getLocalIP();
        System.out.println("=".repeat(60));
        System.out.println("[ASTRAL PLATFORM] INSTALADOR JAVA (100% WebFlux / Reactor Netty)");
        System.out.println("=".repeat(60));
        System.out.println("Acesse: http://" + localIP + ":" + PORT);
        System.out.println("=".repeat(60));

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", Installer::handleIndex);
        server.createContext("/install.html", Installer::handleInstallHTML);
        server.createContext("/api/stream", Installer::handleStream);
        server.createContext("/api/setup-db", Installer::handleSetupDB);
        server.setExecutor(Executors.newFixedThreadPool(8)); // pool só pro HTTP
        server.start();

        // Instalação em thread SEPARADA (nunca bloqueia o servidor web)
        Thread installThread = new Thread(Installer::runInstallation, "installer");
        installThread.setDaemon(true);
        installThread.start();

        Thread.currentThread().join();
    }

    // ============================================================
    // FLUXO DE INSTALAÇÃO
    // ============================================================
    private static void runInstallation() {
        try {
            updateProgress(5, "Detectando distribuição...");
            String distro = detectDistro();
            sleep(500);

            updateProgress(8, "Verificando conectividade...");
            if (!checkInternet()) {
                updateProgress(10, "SEM INTERNET - configurando rede automaticamente...");
                try { NetworkConfig.main(new String[]{"--auto"}); } catch (Exception e) { e.printStackTrace(); }
            } else {
                updateProgress(10, "Internet ativa - NetworkConfig opcional (manual)");
            }

            updateProgress(12, "Removendo Nginx de vez (Reactor Netty assume a porta 80)...");
            removeNginx();

            updateProgress(15, "Configurando firewall (iptables)...");
            configureFirewall();

            updateProgress(25, "Instalando dependências do sistema...");
            installSystemDependencies(distro);

            updateProgress(40, "Instalando Oracle JDK 21...");
            installJava(distro);

            updateProgress(55, "Instalando Maven...");
            installMaven(distro);

            updateProgress(65, "Instalando PostgreSQL...");
            installPostgreSQL(distro);

            updateProgress(75, "Configurando PostgreSQL...");
            configurePostgreSQL(distro);

            updateProgress(80, "Escrevendo pom.xml + sources + templates (sempre sobrescreve)...");
            ensureProjectLayout();
            copyStaticFrontend();
            ensureFonts();
            fixOwnership(); // devolve tudo pro seu usuário

            updateProgress(88, "Compilando projeto Spring Boot (WebFlux)...");
            buildProject();

            updateProgress(95, "Deploy em /opt + systemd service (porta 80)...");
            deploy();

            updateProgress(100, "Instalação concluída! Configure o banco de dados abaixo.");
            while (true) { sleep(1000); } // mantém o servidor de setup vivo
        } catch (Exception e) {
            e.printStackTrace();
            updateProgress(100, "ERRO: " + e.getMessage());
        }
    }

    // ============================================================
    // REMOÇÃO DO NGINX (inclusive processo órfão)
    // ============================================================
    private static void removeNginx() {
        runCmd("systemctl stop nginx 2>/dev/null || true", false);
        runCmd("systemctl disable nginx 2>/dev/null || true", false);
        runCmd("pkill -x nginx 2>/dev/null || true", false);          // mata órfãos
        runCmd("dnf remove -y nginx 2>/dev/null || apt-get purge -y nginx 2>/dev/null || true", true);
        runCmd("pkill -x nginx 2>/dev/null || true", false);          // garante pós-remove
        runCmd("rm -f /etc/nginx/conf.d/astral.conf /etc/nginx/sites-enabled/astral.conf /etc/nginx/sites-available/astral.conf", false);
    }

    // ============================================================
    // SETUP-DB (formulário da UI)
    // ============================================================
    private static void handleSetupDB(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            sendResponse(ex, 405, "{\"error\":\"Method not allowed\"}", "application/json");
            return;
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(ex.getRequestBody()))) {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) body.append(line);

            String json = body.toString();
            String username = extractJsonValue(json, "username");
            String password = extractJsonValue(json, "password");

            if (username == null || password == null || username.isEmpty() || password.isEmpty()) {
                sendResponse(ex, 400, "{\"error\":\"Dados inválidos\"}", "application/json");
                return;
            }

            runCmd("sudo -i -u postgres psql -c \"CREATE USER " + username + " WITH PASSWORD '" + password + "' SUPERUSER;\"", true);
            runCmd("sudo -i -u postgres psql -c \"CREATE DATABASE astral OWNER " + username + ";\"", true);

            Path props = Paths.get("/etc/astral/application.properties");
            Files.createDirectories(props.getParent());
            Files.writeString(props, "server.port=80\n"
                + "server.address=0.0.0.0\n"
                + "spring.datasource.url=jdbc:postgresql://localhost:5432/astral\n"
                + "spring.datasource.username=" + username + "\n"
                + "spring.datasource.password=" + password + "\n"
                + "spring.datasource.driver-class-name=org.postgresql.Driver\n"
                + "spring.jpa.hibernate.ddl-auto=update\n"
                + "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect\n"
                + "spring.jackson.serialization.fail-on-empty-beans=false\n"
                + "spring.web.resources.static-locations=classpath:/static/\n"
                + "spring.thymeleaf.cache=false\n");

            runCmd("systemctl restart astral-platform.service", false);

            String localIP = getLocalIP();
            sendResponse(ex, 200, "{\"success\":true,\"message\":\"Banco 'astral' criado!\",\"redirect_url\":\"http://" + localIP + "/\"}", "application/json");

            new Thread(() -> { try { Thread.sleep(60000); System.exit(0); } catch (InterruptedException ignored) {} }).start();
        } catch (Exception e) {
            sendResponse(ex, 500, "{\"error\":\"" + e.getMessage() + "\"}", "application/json");
        }
    }

    private static String extractJsonValue(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    // ============================================================
    // UI DO INSTALADOR
    // ============================================================
    private static void handleIndex(HttpExchange ex) throws IOException {
        sendResponse(ex, 200, "<meta http-equiv='refresh' content='0; url=/install.html'>", "text/html");
    }

    private static void handleInstallHTML(HttpExchange ex) throws IOException {
        String html = """
            <!DOCTYPE html><html lang="pt-br"><head><meta charset="UTF-8"><title>Instalando Astral</title>
            <style>
            body{background:#05070d;color:#fff;font-family:'Segoe UI',sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0}
            .box{width:500px;background:rgba(4,10,22,.8);border:2px solid #3fa9ff;border-radius:14px;padding:30px;text-align:center}
            h1{color:#9fd8ff;margin-top:0}
            .bar{width:100%;background:#111;height:20px;border-radius:10px;overflow:hidden;margin:20px 0;border:1px solid #333}
            .fill{width:0%;height:100%;background:linear-gradient(90deg,#3fa9ff,#1668ff);transition:width .4s}
            #status{color:#aaa;font-size:14px;margin-bottom:20px}
            #setupForm{display:none;margin-top:20px}
            #setupForm input{width:80%;padding:10px;margin:8px 0;border:1px solid #3fa9ff;border-radius:6px;background:#0b0f14;color:#fff;font-size:14px}
            #setupForm button{padding:12px 30px;background:#3fa9ff;color:#000;border:none;border-radius:6px;cursor:pointer;font-weight:bold;margin-top:10px}
            #redirectBtn{display:none;padding:12px 30px;background:#57e389;color:#000;border:none;border-radius:6px;cursor:pointer;font-weight:bold;margin-top:20px}
            </style></head>
            <body>
            <div class="box">
            <h1>🚀 Instalando Astral Platform</h1>
            <div class="bar"><div class="fill" id="fill"></div></div>
            <div id="status">Iniciando...</div>
            <div id="setupForm">
                <h2 style="color:#9fd8ff;margin-top:0">Configurar Banco de Dados</h2>
                <input type="text" id="dbUser" placeholder="Usuário do banco (ex: astral)">
                <input type="password" id="dbPass" placeholder="Senha do banco">
                <button onclick="setupDB()">Criar Banco e Configurar</button>
            </div>
            <button id="redirectBtn" onclick="redirectToLogin()">Acessar Sistema</button>
            </div>
            <script>
            var evt = new EventSource('/api/stream');
            evt.onmessage = function(e) {
                var d = JSON.parse(e.data);
                document.getElementById('fill').style.width = d.progress + '%';
                document.getElementById('status').textContent = d.status;
                if (d.progress >= 100) { evt.close(); document.getElementById('setupForm').style.display = 'block'; }
            };
            function setupDB() {
                var user = document.getElementById('dbUser').value;
                var pass = document.getElementById('dbPass').value;
                if (!user || !pass) { alert('Preencha usuário e senha!'); return; }
                fetch('/api/setup-db', {method:'POST', headers:{'Content-Type':'application/json'},
                    body: JSON.stringify({username:user, password:pass})})
                .then(r => r.json())
                .then(data => {
                    if (data.success) {
                        alert(data.message);
                        document.getElementById('setupForm').style.display = 'none';
                        document.getElementById('redirectBtn').style.display = 'inline-block';
                        window.redirectUrl = data.redirect_url;
                    } else { alert('Erro: ' + data.error); }
                })
                .catch(e => alert('Erro: ' + e));
            }
            function redirectToLogin() { window.location.href = window.redirectUrl || '/'; }
            </script>
            </body></html>""";
        sendResponse(ex, 200, html, "text/html");
    }

    private static void handleStream(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream os = ex.getResponseBody()) {
            while (true) {
                os.write(("data: {\"progress\": " + progress.get() + ", \"status\": \"" + status + "\"}\n\n").getBytes());
                os.flush();
                if (progress.get() >= 100) break;
                Thread.sleep(500);
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ============================================================
    // LAYOUT DO PROJETO — SEMPRE SOBRESCREVE
    // ============================================================
    private static void ensureProjectLayout() throws IOException {
        String app = System.getProperty("user.dir");
        Path base  = Paths.get(app, "src", "main", "java", "com", "astral", "main");
        Path ctrl  = base.resolve("controller");
        Path model = base.resolve("model");
        Path cfg   = base.resolve("config");
        Path tpl   = Paths.get(app, "src", "main", "resources", "templates");
        Path imgs  = Paths.get(app, "src", "main", "resources", "static", "images");
        Path fonts = Paths.get(app, "src", "main", "resources", "static", "fonts");
        for (Path d : new Path[]{base, ctrl, model, cfg, tpl, imgs, fonts}) Files.createDirectories(d);

        write(Paths.get(app, "pom.xml"), POM_XML);
        write(base.resolve("AstralApplication.java"), ASTRAL_APP_JAVA);
        write(ctrl.resolve("HomeController.java"), HOME_CONTROLLER_JAVA);
        write(ctrl.resolve("LoginController.java"), LOGIN_CONTROLLER_JAVA);
        write(ctrl.resolve("ProxyController.java"), PROXY_CONTROLLER_JAVA);
        write(model.resolve("DashboardButton.java"), DASHBOARD_BUTTON_JAVA);
        write(cfg.resolve("WebSocketConfig.java"), WS_CONFIG_JAVA);
        write(cfg.resolve("TerminalWebSocketHandler.java"), WS_HANDLER_JAVA);
        write(cfg.resolve("DatabaseBootstrap.java"), DB_BOOTSTRAP_JAVA);
        write(tpl.resolve("home.html"), DEFAULT_HOME_HTML);
    }

    private static void write(Path p, String content) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        System.out.println("[OK] " + p.getFileName() + " escrito/atualizado.");
    }

    private static void copyStaticFrontend() {
        try {
            Path src = Paths.get(System.getProperty("user.dir"), "fabric", "frontend", "login");
            Path dst = Paths.get(System.getProperty("user.dir"), "src", "main", "resources", "static");
            if (!Files.isDirectory(src)) return;
            try (var walk = Files.walk(src)) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    Path target = dst.resolve(src.relativize(p).toString());
                    if (Files.isDirectory(p)) Files.createDirectories(target);
                    else { Files.createDirectories(target.getParent());
                           Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING); }
                }
            }
            System.out.println("[OK] Frontend (login/css/js/images) copiado para static/ do JAR.");
        } catch (IOException e) {
            System.err.println("[AVISO] Falha ao copiar frontend estático: " + e.getMessage());
        }
    }

    private static void ensureFonts() {
        try {
            Path fonts = Paths.get(System.getProperty("user.dir"), "src", "main", "resources", "static", "fonts");
            Files.createDirectories(fonts);
            String[][] fs = {
                {"orbitron-bold.woff2", "https://cdn.jsdelivr.net/fontsource/fonts/orbitron@latest/latin-700-normal.woff2"},
                {"orbitron-black.woff2", "https://cdn.jsdelivr.net/fontsource/fonts/orbitron@latest/latin-900-normal.woff2"}
            };
            for (String[] f : fs) {
                Path dst = fonts.resolve(f[0]);
                if (Files.exists(dst) && Files.size(dst) > 1000) continue;
                runCmd("curl -fsSL -o " + dst + " " + f[1], false);
            }
        } catch (IOException ignored) {}
    }

    private static void fixOwnership() {
        try {
            String owner = Files.getOwner(Paths.get(System.getProperty("user.dir"))).getName();
            runCmd("chown -R " + owner + ":" + owner + " " + System.getProperty("user.dir") + " 2>/dev/null || true", false);
            System.out.println("[OK] Ownership devolvido para: " + owner);
        } catch (IOException ignored) {}
    }

    // ============================================================
    // BUILD + DEPLOY
    // ============================================================
    private static void buildProject() {
        String app = System.getProperty("user.dir");
        runCmd("cd " + app + " && mvn -B -DskipTests clean package", true);
        try {
            String owner = Files.getOwner(Paths.get(app)).getName();
            runCmd("chown -R " + owner + ":" + owner + " " + app + "/target", false);
        } catch (IOException ignored) {}
    }

    private static String detectJavaBin() {
        String cand = runCmd("readlink -f $(which java) 2>/dev/null", false);
        if (cand != null && !cand.trim().isEmpty() && Files.exists(Paths.get(cand.trim()))) return cand.trim();
        cand = runCmd("ls -d /usr/lib/jvm/jdk-*/bin/java 2>/dev/null | head -1", false);
        if (cand != null && !cand.trim().isEmpty()) return cand.trim();
        cand = runCmd("ls -d /usr/lib/jvm/*/bin/java 2>/dev/null | head -1", false);
        if (cand != null && !cand.trim().isEmpty()) return cand.trim();
        return "/usr/bin/java";
    }

    private static void deploy() {
        String javaBin = detectJavaBin();
        System.out.println("[OK] Binário Java detectado: " + javaBin);
        runCmd("ln -sf " + javaBin + " /usr/bin/java", false); // garante symlink p/ futuro

        String jar = System.getProperty("user.dir") + "/target/astral-platform-1.0.0.jar";
        runCmd("mkdir -p /opt/astral-platform && cp " + jar + " /opt/astral-platform/ && chown -R root:root /opt/astral-platform && chmod 755 /opt/astral-platform", true);

        runCmd("mkdir -p /etc/astral", false);
        Path props = Paths.get("/etc/astral/application.properties");
        if (!Files.exists(props)) {
            try {
                Files.writeString(props, "server.port=80\nserver.address=0.0.0.0\n"
                    + "spring.datasource.url=jdbc:postgresql://localhost:5432/astral\n"
                    + "spring.datasource.username=astral\nspring.datasource.password=astral\n"
                    + "spring.datasource.driver-class-name=org.postgresql.Driver\n"
                    + "spring.jpa.hibernate.ddl-auto=update\n"
                    + "spring.web.resources.static-locations=classpath:/static/\n"
                    + "spring.thymeleaf.cache=false\n");
            } catch (IOException ignored) {}
        }

        String svc = "[Unit]\n"
            + "Description=Astral Platform (Reactor Netty)\n"
            + "After=network.target postgresql.service\n"
            + "Requires=postgresql.service\n\n"
            + "[Service]\n"
            + "Type=simple\n"
            + "User=root\n"
            + "WorkingDirectory=/opt/astral-platform\n"
            + "ExecStart=" + javaBin + " -jar /opt/astral-platform/astral-platform-1.0.0.jar --spring.config.location=file:/etc/astral/application.properties\n"
            + "Restart=always\n"
            + "RestartSec=10\n"
            + "StandardOutput=journal\n"
            + "StandardError=journal\n\n"
            + "[Install]\n"
            + "WantedBy=multi-user.target\n";
        try { Files.writeString(Paths.get("/etc/systemd/system/astral-platform.service"), svc); } catch (IOException ignored) {}
        runCmd("systemctl daemon-reload", false);
        runCmd("systemctl enable astral-platform.service", false);
        runCmd("systemctl restart astral-platform.service", false);

        for (int i = 0; i < 30; i++) {
            try (var s = new java.net.Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", 80), 500);
                System.out.println("[OK] Reactor Netty rodando na porta 80!");
                return;
            } catch (IOException e) { sleep(1000); }
        }
        System.out.println("[AVISO] Porta 80 não respondeu. journalctl -u astral-platform.service");
    }

    // ============================================================
    // ETAPAS DE SISTEMA
    // ============================================================
    private static void configureFirewall() {
        int[] ports = {22, 80, 443, 5432, 5000, 9090};
        runCmd("systemctl stop firewalld ufw 2>/dev/null || true", false);
        runCmd("systemctl disable firewalld ufw 2>/dev/null || true", false);
        for (int p : ports) runCmd("iptables -I INPUT 1 -p tcp --dport " + p + " -j ACCEPT", false);
        runCmd("iptables-save > /etc/sysconfig/iptables 2>/dev/null || iptables-save > /etc/iptables/rules.v4 2>/dev/null || true", false);
    }

    private static void installSystemDependencies(String distro) {
        switch (distro) {
            case "debian": runCmd("apt-get update", true); runCmd("DEBIAN_FRONTEND=noninteractive apt-get install -y curl git", true); break;
            case "rhel": runCmd("dnf makecache", true); runCmd("dnf install -y curl git", true); break;
            case "arch": runCmd("pacman -Sy --noconfirm curl git", true); break;
        }
    }

    private static void installJava(String distro) {
        String chk = runCmd("java -version 2>&1", false);
        if (chk != null && chk.contains("Oracle")) return;
        switch (distro) {
            case "debian": runCmd("curl -s -L -o /tmp/jdk.deb https://download.oracle.com/java/21/latest/jdk-21_linux-x64_bin.deb", true); runCmd("dpkg -i /tmp/jdk.deb", true); break;
            case "rhel": runCmd("dnf install -y https://download.oracle.com/java/21/latest/jdk-21_linux-x64_bin.rpm", true); break;
            case "arch": runCmd("curl -s -L -o /tmp/jdk.tar.gz https://download.oracle.com/java/21/latest/jdk-21_linux-x64_bin.tar.gz", true); runCmd("tar -xzf /tmp/jdk.tar.gz -C /opt/ && ln -sf /opt/jdk-21*/bin/java /usr/bin/java", true); break;
        }
    }

    private static void installMaven(String distro) {
        if (runCmd("which mvn", false) != null) return;
        switch (distro) {
            case "debian": runCmd("DEBIAN_FRONTEND=noninteractive apt-get install -y maven", true); break;
            case "rhel": runCmd("dnf install -y maven", true); break;
            case "arch": runCmd("pacman -S --noconfirm maven", true); break;
        }
    }

    private static void installPostgreSQL(String distro) {
        String pkg = distro.equals("rhel") ? "postgresql postgresql-server postgresql-contrib" : "postgresql postgresql-contrib";
        switch (distro) {
            case "debian": runCmd("DEBIAN_FRONTEND=noninteractive apt-get install -y " + pkg, true); break;
            case "rhel": runCmd("dnf install -y " + pkg, true); break;
            case "arch": runCmd("pacman -S --noconfirm " + pkg, true); break;
        }
    }

    private static void configurePostgreSQL(String distro) {
        if (distro.equals("rhel")) {
            String pg = runCmd("ls -A /var/lib/pgsql/data 2>/dev/null", false);
            if (pg == null || pg.trim().isEmpty()) {
                runCmd("chown -R postgres:postgres /var/lib/pgsql && /usr/bin/postgresql-setup --initdb", true);
            }
        } else if (distro.equals("arch")) {
            if (!Files.exists(Paths.get("/var/lib/postgres/data/PG_VERSION"))) {
                runCmd("sudo -u postgres initdb -D /var/lib/postgres/data", true);
            }
        }
        runCmd("systemctl enable postgresql", false);
        runCmd("systemctl start postgresql", false);
        String hba = distro.equals("rhel") ? "/var/lib/pgsql/data/pg_hba.conf" :
                     distro.equals("arch") ? "/var/lib/postgres/data/pg_hba.conf" :
                     "/etc/postgresql/*/main/pg_hba.conf";
        runCmd("grep -q '0.0.0.0/0' " + hba + " || echo 'host all all 0.0.0.0/0 md5' >> " + hba, false);
        runCmd("grep -q '^host.*127.0.0.1/32.*md5' " + hba + " || sed -i '1i host all all 127.0.0.1/32 md5' " + hba, false);
        runCmd("systemctl restart postgresql", false);
    }

    // ============================================================
    // UTILITÁRIOS
    // ============================================================
    private static void sendResponse(HttpExchange ex, int code, String body, String type) throws IOException {
        byte[] b = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type + "; charset=UTF-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private static void updateProgress(int p, String s) { progress.set(p); status = s; System.out.println("[" + p + "%] " + s); }

    private static String runCmd(String cmd, boolean log) {
        if (log) System.out.println("$ " + cmd);
        try {
            Process p = new ProcessBuilder("bash", "-c", cmd).redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String l; while ((l = br.readLine()) != null) { if (log) System.out.println("  " + l); out.append(l).append("\n"); }
            }
            int code = p.waitFor();
            return code == 0 ? out.toString() : null;
        } catch (Exception e) { return null; }
    }

    private static String detectDistro() {
        try {
            String c = Files.readString(Paths.get("/etc/os-release")).toLowerCase();
            if (c.contains("debian") || c.contains("ubuntu")) return "debian";
            if (c.contains("rhel") || c.contains("fedora") || c.contains("rocky") || c.contains("centos")) return "rhel";
            if (c.contains("arch")) return "arch";
        } catch (IOException ignored) {}
        return "unknown";
    }

    private static boolean checkInternet() {
        try (var s = new java.net.Socket()) { s.connect(new InetSocketAddress("8.8.8.8", 53), 3000); return true; }
        catch (IOException e) { return false; }
    }

    private static String getLocalIP() {
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c",
                "ip route get 1.1.1.1 2>/dev/null | awk '/src/ {for(i=1;i<=NF;i++) if($i==\"src\") print $(i+1)}'");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String l; while ((l = br.readLine()) != null) out.append(l);
            }
            int code = p.waitFor();
            String result = out.toString().trim();
            if (code == 0 && !result.isEmpty() && result.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) return result;
        } catch (Exception ignored) {}
        try (var s = new java.net.Socket()) {
            s.connect(new InetSocketAddress("8.8.8.8", 80), 3000);
            return s.getLocalAddress().getHostAddress();
        } catch (IOException e) { return "127.0.0.1"; }
    }

    private static boolean isRoot() { return System.getProperty("user.name").equals("root"); }
    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    // ============================================================
    // ARQUIVOS EMBUTIDOS
    // ============================================================
    private static final String POM_XML = """
        <?xml version="1.0" encoding="UTF-8"?>
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
            <properties><java.version>21</java.version></properties>
            <dependencies>
                <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-webflux</artifactId></dependency>
                <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-thymeleaf</artifactId></dependency>
                <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
                <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>runtime</scope></dependency>
            </dependencies>
            <build><plugins><plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build>
        </project>
        """;

    private static final String ASTRAL_APP_JAVA = """
        package com.astral.main;
        import org.springframework.boot.SpringApplication;
        import org.springframework.boot.autoconfigure.SpringBootApplication;
        @SpringBootApplication
        public class AstralApplication {
            public static void main(String[] args) { SpringApplication.run(AstralApplication.class, args); }
        }
        """;

    private static final String HOME_CONTROLLER_JAVA = """
        package com.astral.main.controller;
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
        """;

    private static final String LOGIN_CONTROLLER_JAVA = """
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
                    try (Connection c = DriverManager.getConnection("jdbc:postgresql://localhost:5432/astral", username, password)) {
                        response.put("success", true);
                        response.put("token", UUID.randomUUID().toString());
                        response.put("message", "Autenticado com sucesso");
                        return ResponseEntity.ok(response);
                    } catch (SQLException e) {
                        response.put("success", false);
                        response.put("message", "Usuário ou senha inválidos.");
                        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
                    }
                });
            }
        }
        """;

    private static final String DASHBOARD_BUTTON_JAVA = """
        package com.astral.main.model;
        public record DashboardButton(String id, String label, String image, String route) {}
        """;

    private static final String WS_CONFIG_JAVA = """
        package com.astral.main.config;
        import org.springframework.context.annotation.Bean;
        import org.springframework.context.annotation.Configuration;
        import org.springframework.web.reactive.HandlerMapping;
        import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
        import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
        import java.util.HashMap;
        import java.util.Map;
        @Configuration
        public class WebSocketConfig {
            @Bean
            public HandlerMapping terminalWebSocketMapping(TerminalWebSocketHandler handler) {
                Map<String, Object> map = new HashMap<>();
                map.put("/ws/terminal", handler);
                SimpleUrlHandlerMapping m = new SimpleUrlHandlerMapping();
                m.setUrlMap(map);
                m.setOrder(-1);
                return m;
            }
            @Bean
            public WebSocketHandlerAdapter webSocketHandlerAdapter() { return new WebSocketHandlerAdapter(); }
        }
        """;

    private static final String WS_HANDLER_JAVA = """
        package com.astral.main.config;
        import org.springframework.stereotype.Component;
        import org.springframework.web.reactive.socket.WebSocketHandler;
        import org.springframework.web.reactive.socket.WebSocketMessage;
        import org.springframework.web.reactive.socket.WebSocketSession;
        import reactor.core.publisher.Flux;
        import reactor.core.publisher.Mono;
        import java.io.File;
        import java.io.IOException;
        import java.io.InputStream;
        import java.nio.charset.StandardCharsets;
        @Component
        public class TerminalWebSocketHandler implements WebSocketHandler {
            @Override
            public Mono<Void> handle(WebSocketSession session) {
                final Process proc;
                try {
                    ProcessBuilder pb = new ProcessBuilder("script", "-qfc", "/bin/bash", "/dev/null");
                    pb.environment().put("TERM", "dumb");
                    pb.directory(new File("/root"));
                    proc = pb.start();
                } catch (IOException e) { return session.close(); }
                Flux<WebSocketMessage> output = Flux.create(sink -> {
                    Thread t = new Thread(() -> {
                        try (InputStream in = proc.getInputStream()) {
                            byte[] buf = new byte[4096]; int n;
                            while ((n = in.read(buf)) != -1)
                                sink.next(session.textMessage(new String(buf, 0, n, StandardCharsets.UTF_8)));
                        } catch (IOException ignored) {}
                        sink.complete();
                    });
                    t.setDaemon(true); t.start();
                });
                Mono<Void> send = session.send(output);
                Mono<Void> recv = session.receive().doOnNext(msg -> {
                    try {
                        proc.getOutputStream().write(msg.getPayloadAsText().getBytes(StandardCharsets.UTF_8));
                        proc.getOutputStream().flush();
                    } catch (IOException ignored) {}
                }).then();
                return Mono.zip(send, recv).then().doFinally(s -> proc.destroyForcibly());
            }
        }
        """;

    private static final String PROXY_CONTROLLER_JAVA = """
        package com.astral.main.controller;
        import org.springframework.http.ResponseEntity;
        import org.springframework.web.bind.annotation.RequestMapping;
        import org.springframework.web.bind.annotation.RestController;
        import org.springframework.web.reactive.function.client.WebClient;
        import org.springframework.web.server.ServerWebExchange;
        import reactor.core.publisher.Mono;
        import java.net.URI;
        import java.util.Map;
        @RestController
        public class ProxyController {
            private static final Map<String, Integer> ROUTES = Map.ofEntries(
                Map.entry("dns", 8053),
                Map.entry("firewall", 8040),
                Map.entry("proxy", 8085),
                Map.entry("domain", 8090),
                Map.entry("postgres", 5433),
                Map.entry("web", 8080),
                Map.entry("vm", 8070),
                Map.entry("storage", 8060),
                Map.entry("network", 8024),
                Map.entry("alerts", 8010),
                Map.entry("telemetry", 8015)
            );
            private final WebClient client = WebClient.create();
            @RequestMapping({"/dns", "/dns/**", "/firewall", "/firewall/**", "/proxy", "/proxy/**",
                "/domain", "/domain/**", "/postgres", "/postgres/**", "/web", "/web/**",
                "/vm", "/vm/**", "/storage", "/storage/**", "/network", "/network/**",
                "/alerts", "/alerts/**", "/telemetry", "/telemetry/**"})
            public Mono<ResponseEntity<byte[]>> proxy(ServerWebExchange exchange) {
                String path = exchange.getRequest().getURI().getRawPath();
                String first = path.replaceFirst("^/", "").split("/")[0];
                Integer port = ROUTES.get(first);
                if (port == null) return Mono.just(ResponseEntity.notFound().build());
                String rest = path.substring(("/" + first).length());
                String q = exchange.getRequest().getURI().getRawQuery();
                String url = "http://127.0.0.1:" + port + rest + (q != null ? "?" + q : "");
                return client.method(exchange.getRequest().getMethod()).uri(URI.create(url))
                    .exchangeToMono(resp -> resp.bodyToMono(byte[].class).defaultIfEmpty(new byte[0])
                        .map(body -> ResponseEntity.status(resp.statusCode()).body(body)))
                    .onErrorResume(e -> Mono.just(ResponseEntity.status(502)
                        .body(("Backend " + first + " indisponivel").getBytes())));
            }
        }
        """;

    private static final String DB_BOOTSTRAP_JAVA = """
        package com.astral.main.config;
        import org.springframework.beans.factory.annotation.Value;
        import org.springframework.boot.context.event.ApplicationReadyEvent;
        import org.springframework.context.event.EventListener;
        import org.springframework.stereotype.Component;
        import java.nio.file.Files;
        import java.nio.file.Path;
        import java.sql.DriverManager;
        @Component
        public class DatabaseBootstrap {
            @Value("${spring.datasource.username}") private String username;
            @Value("${spring.datasource.password}") private String password;
            @Value("${spring.datasource.url}") private String url;
            @EventListener(ApplicationReadyEvent.class)
            public void ensureUserAndDatabase() {
                try (var conn = DriverManager.getConnection(url, username, password)) {
                    System.out.println("[BOOTSTRAP] Conexão com o banco 'astral' OK.");
                    return;
                } catch (Exception e) {
                    System.out.println("[BOOTSTRAP] Credenciais ausentes; auto-criando via psql...");
                }
                try {
                    String gexec = (char) 92 + "gexec";
                    String nl = System.lineSeparator();
                    String sql = "DO $$ BEGIN "
                        + "IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '" + username + "') THEN "
                        + "CREATE ROLE " + username + " LOGIN SUPERUSER PASSWORD '" + password + "'; "
                        + "ELSE ALTER ROLE " + username + " WITH LOGIN SUPERUSER PASSWORD '" + password + "'; "
                        + "END IF; END $$;" + nl
                        + "SELECT 'CREATE DATABASE astral OWNER " + username + "' "
                        + "WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'astral')" + gexec + nl;
                    Path tmp = Path.of("/tmp/astral-bootstrap.sql");
                    Files.writeString(tmp, sql);
                    Process p = new ProcessBuilder("sudo", "-u", "postgres", "psql", "-f", "/tmp/astral-bootstrap.sql")
                            .redirectErrorStream(true).start();
                    p.waitFor();
                    System.out.println("[BOOTSTRAP] Auto-provisionamento executado.");
                } catch (Exception e) {
                    System.out.println("[BOOTSTRAP] Falha no auto-provisionamento: " + e.getMessage());
                }
            }
        }
        """;

    private static final String DEFAULT_HOME_HTML = """
        <!DOCTYPE html>
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
        display:flex;flex-wrap:wrap;gap:var(--gap);justify-content:center;align-content:center}
        .card{--c:#8fb7ff;
        flex:0 0 calc((100% - (var(--cols) - 1)*var(--gap))/var(--cols) - .5px);
        aspect-ratio:3/1;border:2px solid var(--c);border-radius:14px;background:rgba(4,10,22,.66);
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
        <div class="termhead"><span>ASTRAL TERMINAL</span>
        <span><button id="detachBtn" type="button">Destacar</button><button id="closeBtn" type="button">X</button></span></div>
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
        function clean(s){return s.replace(/\\x1b\\[[0-9;?]*[a-zA-Z]/g,'').replace(/\\r/g,'')}
        function attach(out,inp){var ws=new WebSocket((location.protocol==='https:'?'wss://':'ws://')+location.host+'/ws/terminal');
        ws.onmessage=function(e){out.textContent+=clean(e.data);out.scrollTop=out.scrollHeight};
        ws.onclose=function(){out.textContent+='\\n[conexao encerrada]\\n'};
        inp.addEventListener('keydown',function(e){if(e.key==='Enter'&&ws.readyState===1){ws.send(inp.value+'\\n');inp.value=''}});
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
        """;
}
