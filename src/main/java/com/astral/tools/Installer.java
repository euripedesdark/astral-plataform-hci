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
private static String status = "Aguardando configuração inicial...";
private static final String ASTRAL_GROUP = "astral";
private static volatile boolean needAdmin = true;
private static volatile String adminUser = "", adminPass = "";
private static volatile boolean adEnabled = false;
private static volatile String adDomain = "";

public static void main(String[] args) throws Exception {
    if (!isRoot()) { System.err.println("ERRO: Execute com sudo"); System.exit(1); }
    String localIP = getLocalIP();
    System.out.println("=".repeat(60));
    System.out.println("[ASTRAL PLATFORM] INSTALADOR (mTLS + AD via UnboundID)");
    System.out.println("=".repeat(60));
    System.out.println("Acesse: http://" + localIP + ":" + PORT);
    System.out.println("=".repeat(60));
    HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
    server.createContext("/", Installer::handleIndex);
    server.createContext("/install.html", Installer::handleInstallHTML);
    server.createContext("/api/stream", Installer::handleStream);
    server.createContext("/api/setup-admin", Installer::handleSetupAdmin);
    server.setExecutor(Executors.newCachedThreadPool());
    server.start();
    Thread installThread = new Thread(Installer::runInstallation, "installer");
    installThread.setDaemon(true);
    installThread.start();
    Thread.currentThread().join();
}

private static void runInstallation() {
    try {
        configureFirewall();
        while (needAdmin) sleep(300);
        updateProgress(3, "Credenciais recebidas. Iniciando instalação...");
        ensureProjectLayout();
        ensureFonts();
        String distro = detectDistro();
        updateProgress(5, "Sincronizando repositórios do Linux...");
        syncRepos(distro);
        updateProgress(20, "Verificando Node.js, NPM e dependências JS...");
        installNode(distro);
        updateProgress(40, "Avaliando instalação do Oracle Java 21 LTS...");
        installJava(distro);
        updateProgress(52, "Instalando o Maven...");
        installMaven(distro);
        updateProgress(53, "Pré-baixando dependências do Spring Boot (Maven)...");
        preDownloadMavenDeps();
        updateProgress(55, "Instalando o motor de banco de dados (PostgreSQL)...");
        installPostgreSQL(distro);
        updateProgress(70, "Removendo Nginx (Reactor Netty assume porta 80)...");
        removeNginx();
        updateProgress(85, "Ativando serviços de dados e ajustando SELinux...");
        relaxSelinux();
        configurePostgreSQL(distro);
        updateProgress(87, "Gerando certificados SSL para mTLS...");
        generateCertificates();
        updateProgress(88, "Configurando banco com mTLS + usuário admin...");
        ensureDatabaseBaseMTLS();
        createAstralGroup();
        if (adEnabled) {
            updateProgress(89, "Configurando propriedades do Active Directory...");
            configureADProperties();
        }
        updateProgress(90, "Organizando projeto e aplicando chown...");
        fixOwnership();
        updateProgress(92, "Compilando Spring Boot e criando systemd service...");
        buildAndDeploySpringBoot();
        updateProgress(95, "Aguardando o serviço de banco de dados iniciar...");
        boolean dbReady = waitForPort(5432, 30);
        if (dbReady) updateProgress(100, "Instalação concluída! Acesse http://" + getLocalIP() + "/");
        else updateProgress(100, "Falha crítica: PostgreSQL não está escutando na porta 5432.");
        while (true) { sleep(1000); }
    } catch (Exception e) {
        e.printStackTrace();
        updateProgress(100, "ERRO: " + e.getMessage());
    }
}

private static void configureADProperties() {
    try {
        Files.createDirectories(Paths.get("/etc/astral"));
        Files.writeString(Paths.get("/etc/astral/ad.properties"),
                "astral.ad.enabled=true\n" +
                "astral.ad.domain=" + adDomain + "\n");
        runCmd("chown root:" + ASTRAL_GROUP + " /etc/astral/ad.properties && chmod 0640 /etc/astral/ad.properties", false);
        System.out.println("[OK] Propriedades do AD configuradas: domínio=" + adDomain);
    } catch (Exception e) {
        System.err.println("[AVISO] Falha ao configurar AD: " + e.getMessage());
    }
}

private static void handleSetupAdmin(HttpExchange ex) throws IOException {
    if (!"POST".equals(ex.getRequestMethod())) { sendResponse(ex, 405, "{\"error\":\"Method not allowed\"}", "application/json"); return; }
    String body = new String(ex.getRequestBody().readAllBytes());
    String u = extractJsonValue(body, "username");
    String p = extractJsonValue(body, "password");
    if (u == null || u.isEmpty() || p == null || p.isEmpty()) { sendResponse(ex, 400, "{\"error\":\"Usuário e senha obrigatórios\"}", "application/json"); return; }
    adminUser = u; adminPass = p;
    adEnabled = "true".equals(extractJsonValue(body, "adEnabled"));
    adDomain = extractJsonValue(body, "adDomain");
    if (adDomain == null) adDomain = "";
    if (adEnabled && adDomain.isBlank()) {
        sendResponse(ex, 400, "{\"error\":\"AD habilitado exige domínio\"}", "application/json"); return;
    }
    needAdmin = false;
    sendResponse(ex, 200, "{\"success\":true}", "application/json");
}

private static String extractJsonValue(String json, String key) {
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
    return m.find() ? m.group(1) : null;
}

private static void generateCertificates() {
    Path certDir = Paths.get("/etc/astral/certs");
    try { Files.createDirectories(certDir); } catch (IOException ignored) {}
    runCmd("openssl req -new -x509 -days 3650 -nodes -out " + certDir + "/root.crt -keyout " + certDir + "/root.key -subj \"/CN=Astral-Root-CA\"", true);
    runCmd("openssl req -new -nodes -out " + certDir + "/server.csr -keyout " + certDir + "/server.key -subj \"/CN=127.0.0.1\"", true);
    runCmd("openssl x509 -req -in " + certDir + "/server.csr -days 3650 -CA " + certDir + "/root.crt -CAkey " + certDir + "/root.key -CAcreateserial -out " + certDir + "/server.crt", true);
    runCmd("openssl req -new -nodes -out " + certDir + "/client-astral.csr -keyout " + certDir + "/client-astral.key -subj \"/CN=astral\"", true);
    runCmd("openssl x509 -req -in " + certDir + "/client-astral.csr -days 3650 -CA " + certDir + "/root.crt -CAkey " + certDir + "/root.key -CAcreateserial -out " + certDir + "/client-astral.crt", true);
    runCmd("openssl pkcs8 -topk8 -inform PEM -outform DER -in " + certDir + "/client-astral.key -out " + certDir + "/client-astral.pk8 -nocrypt", true);
    runCmd("cp " + certDir + "/root.crt " + certDir + "/server.crt " + certDir + "/server.key /var/lib/pgsql/data/ 2>/dev/null || cp " + certDir + "/root.crt " + certDir + "/server.crt " + certDir + "/server.key /var/lib/postgres/data/ 2>/dev/null || true", false);
    runCmd("chown postgres:postgres /var/lib/pgsql/data/root.crt /var/lib/pgsql/data/server.crt /var/lib/pgsql/data/server.key 2>/dev/null || chown postgres:postgres /var/lib/postgres/data/root.crt /var/lib/postgres/data/server.crt /var/lib/postgres/data/server.key 2>/dev/null || true", false);
    runCmd("chmod 0600 /var/lib/pgsql/data/server.key 2>/dev/null || chmod 0600 /var/lib/postgres/data/server.key 2>/dev/null || true", false);
    runCmd("chown -R root:" + ASTRAL_GROUP + " " + certDir, false);
    runCmd("chmod 0640 " + certDir + "/*", false);
}

private static void configurePostgresSSL() {
    String pgData = runCmd("runuser -u postgres -- psql -t -c 'SHOW data_directory'", false);
    if (pgData == null || pgData.trim().isEmpty()) pgData = "/var/lib/pgsql/data";
    pgData = pgData.trim();
    Path confFile = Paths.get(pgData, "postgresql.conf");
    try {
        String conf = Files.readString(confFile);
        if (!conf.contains("ssl = on")) {
            Files.writeString(confFile, conf + "\n# mTLS Astral Platform\nssl = on\nssl_ca_file = 'root.crt'\nssl_cert_file = 'server.crt'\nssl_key_file = 'server.key'\n");
        }
    } catch (IOException ignored) {}
    Path hbaFile = Paths.get(pgData, "pg_hba.conf");
    try {
        String hba = Files.readString(hbaFile);
        String newHba = hba.lines()
                .filter(l -> !l.contains("hostssl astral") && !l.matches("^host\\s+astral\\s+" + adminUser + ".*"))
                .collect(java.util.stream.Collectors.joining("\n"));
        String rules = "# Astral Platform - mTLS para aplicações\nhostssl astral astral 127.0.0.1/32 cert\n# Astral Platform - admin via senha\nhost astral " + adminUser + " 127.0.0.1/32 md5\nhost astral " + adminUser + " ::1/128 md5\n\n";
        Files.writeString(hbaFile, rules + newHba);
    } catch (IOException ignored) {}
    runCmd("systemctl restart postgresql", true);
    sleep(3000);
}

private static void ensureDatabaseBaseMTLS() {
    configurePostgresSSL();
    String sqlAstral = "DO $$ BEGIN\nIF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'astral') THEN\nCREATE ROLE astral LOGIN SUPERUSER;\nELSE\nALTER ROLE astral WITH LOGIN SUPERUSER;\nEND IF; END $$;\n\nSELECT 'CREATE DATABASE astral OWNER astral'\nWHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'astral')\\gexec\n";
    String sqlAdmin = "DO $$ BEGIN\nIF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '" + adminUser + "') THEN\nCREATE ROLE " + adminUser + " LOGIN SUPERUSER PASSWORD '" + adminPass + "';\nELSE\nALTER ROLE " + adminUser + " WITH LOGIN SUPERUSER PASSWORD '" + adminPass + "';\nEND IF; END $$;\nGRANT ALL PRIVILEGES ON DATABASE astral TO " + adminUser + ";\n";
    try {
        Path f1 = Paths.get("/tmp/astral-init-mtls.sql");
        Files.writeString(f1, sqlAstral);
        runCmd("chmod 0644 " + f1, false);
        runCmd("runuser -u postgres -- psql -f " + f1, true);
        Path f2 = Paths.get("/tmp/astral-init-admin.sql");
        Files.writeString(f2, sqlAdmin);
        runCmd("chmod 0644 " + f2, false);
        runCmd("runuser -u postgres -- psql -f " + f2, true);
    } catch (IOException ignored) {}
}

private static boolean waitForPort(int port, int seconds) {
    for (int i = 0; i < seconds; i++) {
        try (var s = new java.net.Socket()) { s.connect(new InetSocketAddress("127.0.0.1", port), 500); return true; }
        catch (IOException e) { sleep(1000); }
    }
    return false;
}

private static void relaxSelinux() {
    runCmd("setenforce 0 2>/dev/null || true", false);
    runCmd("sed -i 's/^SELINUX=.*/SELINUX=permissive/' /etc/selinux/config 2>/dev/null || true", false);
}

private static void createAstralGroup() {
    if (runCmd("getent group " + ASTRAL_GROUP, false) == null) runCmd("groupadd " + ASTRAL_GROUP, true);
    try {
        String owner = Files.getOwner(Paths.get(System.getProperty("user.dir"))).getName();
        runCmd("usermod -aG " + ASTRAL_GROUP + " " + owner + " 2>/dev/null || true", false);
        runCmd("usermod -aG " + ASTRAL_GROUP + " root 2>/dev/null || true", false);
    } catch (IOException ignored) {}
}

private static void handleIndex(HttpExchange ex) throws IOException { sendResponse(ex, 200, "<meta http-equiv='refresh' content='0; url=/install.html'>", "text/html"); }

private static void handleInstallHTML(HttpExchange ex) throws IOException {
    String html = """
        <!DOCTYPE html><html lang="pt-br"><head><meta charset="UTF-8"><title>Instalando Astral</title>
        <style>
        body{background:#05070d;color:#fff;font-family:'Segoe UI',sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0}
        .box{width:520px;background:rgba(4,10,22,.8);border:2px solid #3fa9ff;border-radius:14px;padding:30px;text-align:center}
        h1{color:#9fd8ff;margin-top:0}
        .bar{width:100%;background:#111;height:20px;border-radius:10px;overflow:hidden;margin:20px 0;border:1px solid #333}
        .fill{width:0%;height:100%;background:linear-gradient(90deg,#3fa9ff,#1668ff);transition:width .4s}
        #status{color:#aaa;font-size:14px;margin-bottom:20px}
        #adminForm{margin-top:20px}
        #adminForm input{width:80%;padding:10px;margin:6px 0;border:1px solid #3fa9ff;border-radius:6px;background:#0b0f14;color:#fff;font-size:14px}
        #adminForm button{padding:12px 30px;background:#3fa9ff;color:#000;border:none;border-radius:6px;cursor:pointer;font-weight:bold;margin-top:10px}
        #redirectBtn{display:none;padding:12px 30px;background:#57e389;color:#000;border:none;border-radius:6px;cursor:pointer;font-weight:bold;margin-top:20px}
        .hidden{display:none}
        fieldset{border:1px solid #335;border-radius:10px;margin-top:14px;color:#9fd8ff}
        label{font-size:13px;color:#aaa}
        </style></head>
        <body>
        <div class="box">
        <h1>🚀 Instalando Astral Platform</h1>
        <div id="adminForm">
            <h2 style="color:#9fd8ff;margin-top:0">Configuração Inicial</h2>
            <p style="color:#aaa;font-size:13px">Usuário administrador do dashboard:</p>
            <input type="text" id="adminUser" placeholder="Usuário admin (ex: euripedes)" value="euripedes">
            <input type="password" id="adminPass" placeholder="Senha do banco/dashboard">
            <fieldset>
                <legend><label><input type="checkbox" id="adEnabled" onchange="toggleAd()"> Conectar ao Active Directory (UnboundID LDAP)</label></legend>
                <div id="adFields" class="hidden">
                    <input type="text" id="adDomain" placeholder="Domínio (ex: srvcloud.cloud)">
                    <p style="font-size:11px;color:#888">Admins do AD recebem privilégios de sudo/root e superuser do Postgres.</p>
                </div>
            </fieldset>
            <button onclick="setupAdmin()">Iniciar Instalação</button>
            <div id="formError" style="color:#ff5c5c;margin-top:10px"></div>
        </div>
        <div id="installProgress" class="hidden">
            <div class="bar"><div class="fill" id="fill"></div></div>
            <div id="status">Iniciando...</div>
            <button id="redirectBtn" onclick="redirectToHome()">Acessar Sistema</button>
        </div>
        </div>
        <script>
        function toggleAd(){ document.getElementById('adFields').classList.toggle('hidden', !document.getElementById('adEnabled').checked); }
        function setupAdmin() {
            var user = document.getElementById('adminUser').value.trim();
            var pass = document.getElementById('adminPass').value;
            if (!user || !pass) { document.getElementById('formError').textContent = 'Preencha usuário e senha!'; return; }
            fetch('/api/setup-admin', {
                method:'POST', headers:{'Content-Type':'application/json'},
                body: JSON.stringify({username:user, password:pass,
                    adEnabled: document.getElementById('adEnabled').checked ? 'true':'false',
                    adDomain: document.getElementById('adDomain').value.trim()})
            }).then(r => r.json()).then(data => {
                if (data.success) {
                    document.getElementById('adminForm').classList.add('hidden');
                    document.getElementById('installProgress').classList.remove('hidden');
                    startStream();
                } else { document.getElementById('formError').textContent = 'Erro: ' + (data.error || 'desconhecido'); }
            }).catch(e => document.getElementById('formError').textContent = 'Erro: ' + e);
        }
        function startStream() {
            var evt = new EventSource('/api/stream');
            evt.onmessage = function(e) {
                var d = JSON.parse(e.data);
                document.getElementById('fill').style.width = d.progress + '%';
                document.getElementById('status').textContent = d.status;
                if (d.progress >= 100) { evt.close(); document.getElementById('redirectBtn').style.display = 'inline-block'; }
            };
        }
        function redirectToHome() { window.location.href = '/'; }
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
    catch (IOException ignored) { }
}

private static void ensureProjectLayout() throws IOException {
    String app = System.getProperty("user.dir");
    Path base = Paths.get(app, "src", "main", "java", "com", "astral", "main");
    Path ctrl = base.resolve("controller");
    Path filter = base.resolve("filter");
    Path cfg = base.resolve("config");
    for (Path d : new Path[]{base, ctrl, filter, cfg}) Files.createDirectories(d);
    write(Paths.get(app, "pom.xml"), pomXml());
    write(base.resolve("AstralApplication.java"), ASTRAL_APP_JAVA);
    write(ctrl.resolve("HomeController.java"), HOME_CONTROLLER_JAVA);
    write(ctrl.resolve("LoginController.java"), LOGIN_CONTROLLER_JAVA);
    write(ctrl.resolve("ProxyController.java"), PROXY_CONTROLLER_JAVA);
    write(cfg.resolve("WebSocketConfig.java"), WS_CONFIG_JAVA);
    write(cfg.resolve("TerminalWebSocketHandler.java"), WS_HANDLER_JAVA);
    write(cfg.resolve("DatabaseBootstrap.java"), DB_BOOTSTRAP_JAVA);
    write(filter.resolve("AuthFilter.java"), AUTH_FILTER_JAVA);
}

private static String pomXml() {
    return """
    <?xml version="1.0" encoding="UTF-8"?>
    <project xmlns="http://maven.apache.org/POM/4.0.0"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
        <modelVersion>4.0.0</modelVersion>
        <parent>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-parent</artifactId>
            <version>3.2.5</version>
            <relativePath/>
        </parent>
        <groupId>com.astral</groupId>
        <artifactId>astral-platform</artifactId>
        <version>1.0.0</version>
        <name>astral-platform</name>
        <properties><java.version>21</java.version></properties>
        <dependencies>
            <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-webflux</artifactId></dependency>
            <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
            <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>runtime</scope></dependency>
            <dependency><groupId>com.unboundid</groupId><artifactId>unboundid-ldapsdk</artifactId><version>6.0.11</version></dependency>
        </dependencies>
        <build><plugins><plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build>
    </project>
    """;
}

private static void write(Path p, String content) throws IOException {
    Files.createDirectories(p.getParent());
    Files.writeString(p, content);
    System.out.println("[OK] " + p.getFileName() + " escrito/atualizado.");
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
        runCmd("chown -R " + owner + ":" + ASTRAL_GROUP + " " + System.getProperty("user.dir") + " 2>/dev/null || true", false);
        runCmd("find " + System.getProperty("user.dir") + " -type d -exec chmod 2775 {} \\; 2>/dev/null || true", false);
        runCmd("find " + System.getProperty("user.dir") + " -type f -exec chmod 0664 {} \\; 2>/dev/null || true", false);
    } catch (IOException ignored) {}
}

private static void preDownloadMavenDeps() {
    String app = System.getProperty("user.dir");
    if (Files.exists(Paths.get(app, "pom.xml"))) runCmd("cd " + app + " && mvn -B -q dependency:go-offline 2>/dev/null || true", false);
}

private static void buildAndDeploySpringBoot() {
    String app = System.getProperty("user.dir");
    runCmd("cd " + app + " && mvn -B -DskipTests clean package", true);
    String jar = app + "/target/astral-platform-1.0.0.jar";
    if (!Files.exists(Paths.get(jar))) { System.err.println("[ERRO] JAR não encontrado em " + jar); return; }
    String javaBin = detectJavaBin();
    runCmd("ln -sf " + javaBin + " /usr/bin/java", false);
    runCmd("mkdir -p /opt/astral-platform", true);
    runCmd("cp " + jar + " /opt/astral-platform/", true);
    runCmd("chown -R root:" + ASTRAL_GROUP + " /opt/astral-platform", true);
    runCmd("chmod 2770 /opt/astral-platform", true);
    runCmd("chmod 0660 /opt/astral-platform/*.jar 2>/dev/null || true", false);
    System.out.println("[DEPLOY] Integrando os arquivos estáticos de /fabric/frontend...");
    runCmd("rm -rf /opt/astral-platform/frontend", false);
    runCmd("cp -r " + app + "/fabric/frontend /opt/astral-platform/", true);
    runCmd("chown -R root:" + ASTRAL_GROUP + " /opt/astral-platform/frontend", false);
    runCmd("chmod -R 2775 /opt/astral-platform/frontend", false);
    runCmd("mkdir -p /etc/astral", false);
    runCmd("chown root:" + ASTRAL_GROUP + " /etc/astral", false);
    runCmd("chmod 2770 /etc/astral", false);
    Path props = Paths.get("/etc/astral/application.properties");
    try {
        String sslUrl = "jdbc:postgresql://127.0.0.1:5432/astral?ssl=true&sslmode=verify-ca"
                + "&sslcert=/etc/astral/certs/client-astral.crt"
                + "&sslkey=/etc/astral/certs/client-astral.pk8"
                + "&sslrootcert=/etc/astral/certs/root.crt";
        Files.writeString(props,
                "server.port=80\n"
                        + "server.address=0.0.0.0\n"
                        + "spring.datasource.url=" + sslUrl + "\n"
                        + "spring.datasource.username=astral\n"
                        + "spring.datasource.driver-class-name=org.postgresql.Driver\n"
                        + "spring.jpa.hibernate.ddl-auto=update\n"
                        + "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect\n"
                        + "spring.jackson.serialization.fail-on-empty-beans=false\n"
                        + "spring.web.resources.static-locations=file:/opt/astral-platform/frontend/\n"
                        + "spring.thymeleaf.cache=false\n");
        runCmd("chown root:" + ASTRAL_GROUP + " " + props, false);
        runCmd("chmod 0640 " + props, false);
    } catch (IOException ignored) {}
    String svc = "[Unit]\nDescription=Astral Platform (Reactor Netty)\nAfter=network.target postgresql.service\nRequires=postgresql.service\n\n"
            + "[Service]\nType=simple\nUser=root\nGroup=" + ASTRAL_GROUP + "\nWorkingDirectory=/opt/astral-platform\n"
            + "ExecStart=" + javaBin + " -jar /opt/astral-platform/astral-platform-1.0.0.jar --spring.config.location=file:/etc/astral/application.properties\n"
            + "Restart=always\nRestartSec=10\nStandardOutput=journal\nStandardError=journal\nUMask=0007\n\n"
            + "[Install]\nWantedBy=multi-user.target\n";
    try { Files.writeString(Paths.get("/etc/systemd/system/astral-platform.service"), svc); } catch (IOException ignored) {}
    runCmd("systemctl daemon-reload", false);
    runCmd("systemctl enable astral-platform.service", false);
    runCmd("systemctl restart astral-platform.service", false);
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

private static void syncRepos(String distro) {
    switch (distro) {
        case "debian": runCmd("apt-get update", true); break;
        case "rhel": runCmd("dnf makecache", true); break;
        case "arch": runCmd("pacman -Sy", true); break;
    }
}

private static void installNode(String distro) {
    if (runCmd("which node", false) != null && runCmd("which npm", false) != null) return;
    String pkg = distro.equals("rhel") ? "nodejs nodejs-npm curl" : "nodejs npm curl";
    switch (distro) {
        case "debian": runCmd("DEBIAN_FRONTEND=noninteractive apt-get install -y " + pkg, true); break;
        case "rhel": runCmd("dnf install -y " + pkg, true); break;
        case "arch": runCmd("pacman -S --noconfirm " + pkg, true); break;
    }
    runCmd("npm install -g pg express cors 2>/dev/null || true", false);
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
    String svc = "postgresql";
    if (distro.equals("rhel")) {
        svc = detectPgService();
        String pg = runCmd("ls -A /var/lib/pgsql/data 2>/dev/null", false);
        if (pg == null || pg.trim().isEmpty()) {
            runCmd("chown -R postgres:postgres /var/lib/pgsql", false);
            runCmd("/usr/bin/postgresql-setup --initdb", true);
            runCmd("chown -R postgres:postgres /var/lib/pgsql/data", false);
            runCmd("chmod 700 /var/lib/pgsql/data", false);
        }
        runCmd("grep -q '^listen_addresses' /var/lib/pgsql/data/postgresql.conf || echo \"listen_addresses = '*'\" >> /var/lib/pgsql/data/postgresql.conf", false);
        runCmd("grep -q '0.0.0.0/0' /var/lib/pgsql/data/pg_hba.conf || echo 'host all all 0.0.0.0/0 md5' >> /var/lib/pgsql/data/pg_hba.conf", false);
        runCmd("grep -q '^host.*127.0.0.1/32.*md5' /var/lib/pgsql/data/pg_hba.conf || sed -i '1i host all all 127.0.0.1/32 md5' /var/lib/pgsql/data/pg_hba.conf", false);
    } else if (distro.equals("arch")) {
        if (!Files.exists(Paths.get("/var/lib/postgres/data/PG_VERSION"))) runCmd("runuser -u postgres -- initdb -D /var/lib/postgres/data", true);
    }
    runCmd("systemctl enable " + svc, false);
    runCmd("systemctl start " + svc, false);
}

private static String detectPgService() {
    String[] names = {"postgresql", "postgresql-server", "postgresql-16", "postgresql-15", "postgresql-14"};
    for (String n : names) if (runCmd("systemctl cat " + n + " >/dev/null 2>&1", false) != null) return n;
    return "postgresql";
}

private static void configureFirewall() {
    int[] ports = {22, 80, 443, 3000, 5000, 5173, 5432, 8081, 9090, 8040};
    runCmd("systemctl stop firewalld ufw 2>/dev/null || true", false);
    runCmd("systemctl disable firewalld ufw 2>/dev/null || true", false);
    for (int p : ports) runCmd("iptables -I INPUT 1 -p tcp --dport " + p + " -j ACCEPT", false);
    runCmd("iptables-save > /etc/sysconfig/iptables 2>/dev/null || iptables-save > /etc/iptables/rules.v4 2>/dev/null || true", false);
}

private static void removeNginx() {
    runCmd("systemctl stop nginx 2>/dev/null || true", false);
    runCmd("systemctl disable nginx 2>/dev/null || true", false);
    runCmd("pkill -x nginx 2>/dev/null || true", false);
    runCmd("dnf remove -y nginx 2>/dev/null || apt-get purge -y nginx 2>/dev/null || true", true);
    runCmd("rm -f /etc/nginx/conf.d/astral.conf /etc/nginx/sites-enabled/astral.conf /etc/nginx/sites-available/astral.conf", false);
}

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

private static String getLocalIP() {
    try {
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", "ip route get 1.1.1.1 2>/dev/null | awk '/src/ {for(i=1;i<=NF;i++) if($i==\"src\") print $(i+1)}'");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) { String l; while ((l = br.readLine()) != null) out.append(l); }
        int code = p.waitFor();
        String result = out.toString().trim();
        if (code == 0 && !result.isEmpty() && result.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) return result;
    } catch (Exception ignored) {}
    try (var s = new java.net.Socket()) { s.connect(new InetSocketAddress("8.8.8.8", 80), 3000); return s.getLocalAddress().getHostAddress(); }
    catch (IOException e) { return "127.0.0.1"; }
}

private static boolean isRoot() { return System.getProperty("user.name").equals("root"); }
private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

// =========================================================================
// TEMPLATES DO BACKEND
// =========================================================================
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
    import org.springframework.stereotype.Controller;
    import org.springframework.web.bind.annotation.GetMapping;
    @Controller
    public class HomeController {
        @GetMapping("/")
        public String root() { return "redirect:/login/index.html"; }
    }
    """;

private static final String AUTH_FILTER_JAVA = """
    package com.astral.main.filter;
    import org.springframework.http.HttpCookie;
    import org.springframework.http.HttpStatus;
    import org.springframework.stereotype.Component;
    import org.springframework.web.server.ServerWebExchange;
    import org.springframework.web.server.WebFilter;
    import org.springframework.web.server.WebFilterChain;
    import reactor.core.publisher.Mono;
    import java.net.URI;
    @Component
    public class AuthFilter implements WebFilter {
        @Override
        public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
            String path = exchange.getRequest().getURI().getPath();
            if (path.equals("/") || path.startsWith("/login/") || path.startsWith("/api/auth/") ||
                path.endsWith(".css") || path.endsWith(".js") || path.endsWith(".png") || path.endsWith(".svg")) {
                return chain.filter(exchange);
            }
            HttpCookie tokenCookie = exchange.getRequest().getCookies().getFirst("astral_token");
            if (tokenCookie == null || tokenCookie.getValue().isEmpty()) {
                exchange.getResponse().setStatusCode(HttpStatus.FOUND);
                exchange.getResponse().getHeaders().setLocation(URI.create("/login/index.html"));
                return exchange.getResponse().setComplete();
            }
            return chain.filter(exchange);
        }
    }
    """;

private static final String LOGIN_CONTROLLER_JAVA = """
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
                return null;
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
        private void provisionAdmin(String user) {
            String safe = user.replaceAll("[\"'\\\\\\\\]", "");
            try {
                Properties props = new Properties();
                props.setProperty("user", "astral");
                props.setProperty("ssl", "true");
                props.setProperty("sslmode", "verify-ca");
                props.setProperty("sslcert", "/etc/astral/certs/client-astral.crt");
                props.setProperty("sslkey", "/etc/astral/certs/client-astral.pk8");
                props.setProperty("sslrootcert", "/etc/astral/certs/root.crt");
                try (Connection c = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:5432/astral", props)) {
                    c.createStatement().execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='" + safe + "') THEN CREATE ROLE \\"" + safe + "\\" LOGIN SUPERUSER; ELSE ALTER ROLE \\"" + safe + "\\" LOGIN SUPERUSER; END IF; END $$;");
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
    import org.springframework.web.reactive.function.BodyInserters;
    import org.springframework.web.server.ServerWebExchange;
    import reactor.core.publisher.Mono;
    import java.net.URI;
    import java.util.Map;
    @RestController
    public class ProxyController {
        private static final Map<String, Integer> ROUTES = Map.ofEntries(
            Map.entry("dns", 8053), Map.entry("firewall", 8040), Map.entry("proxy", 8085),
            Map.entry("domain", 8090), Map.entry("postgres", 5433), Map.entry("web", 8080),
            Map.entry("vm", 8070), Map.entry("storage", 8060), Map.entry("network", 8024),
            Map.entry("alerts", 8010), Map.entry("telemetry", 8015));
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
            return client.method(exchange.getRequest().getMethod())
                .uri(URI.create(url))
                .headers(h -> { h.putAll(exchange.getRequest().getHeaders()); h.remove("Host"); })
                .body(BodyInserters.fromDataBuffers(exchange.getRequest().getBody()))
                .exchangeToMono(resp -> resp.bodyToMono(byte[].class).defaultIfEmpty(new byte[0])
                    .map(body -> ResponseEntity.status(resp.statusCode())
                        .headers(out -> out.putAll(resp.headers().asHttpHeaders()))
                        .body(body)))
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
    import java.sql.DriverManager;
    import java.util.Properties;
    @Component
    public class DatabaseBootstrap {
        @Value("${spring.datasource.username}") private String username;
        @Value("${spring.datasource.url}") private String url;
        @EventListener(ApplicationReadyEvent.class)
        public void ensureUserAndDatabase() {
            Properties props = new Properties();
            props.setProperty("user", username);
            props.setProperty("ssl", "true");
            props.setProperty("sslmode", "verify-ca");
            props.setProperty("sslcert", "/etc/astral/certs/client-astral.crt");
            props.setProperty("sslkey", "/etc/astral/certs/client-astral.pk8");
            props.setProperty("sslrootcert", "/etc/astral/certs/root.crt");
            try (var conn = DriverManager.getConnection(url, props)) {
                System.out.println("[BOOTSTRAP] Conexão JDBC (mTLS) com o banco 'astral' OK.");
            } catch (Exception e) { System.out.println("[BOOTSTRAP] Falha JDBC (mTLS): " + e.getMessage()); }
        }
    }
    """;
}
