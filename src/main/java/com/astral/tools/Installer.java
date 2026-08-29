package com.astral.tools;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Instalador Standalone - Astral Platform
 * Uso: sudo java -jar installer.jar
 *
 * Serve UI web na porta 5000 durante instalação
 * Executa todas as etapas do sistema
 */
public class Installer {

    private static final int PORT = 5000;
    private static final AtomicInteger progress = new AtomicInteger(0);
    private static String status = "Aguardando conexão...";
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    public static void main(String[] args) throws Exception {
        if (!isRoot()) {
            System.err.println("ERRO: Execute com sudo");
            System.exit(1);
        }

        System.out.println("=".repeat(60));
        System.out.println("[ASTRAL PLATFORM] INSTALADOR JAVA");
        System.out.println("=".repeat(60));
        System.out.println("Acesse: http://" + getLocalIP() + ":" + PORT);
        System.out.println("=".repeat(60));

        // Inicia servidor web para UI
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", Installer::handleIndex);
        server.createContext("/api/stream", Installer::handleStream);
        server.createContext("/install.html", Installer::handleInstallHTML);
        server.setExecutor(executor);
        server.start();

        // Inicia thread de instalação
        executor.submit(Installer::runInstallation);

        // Mantém rodando
        Thread.currentThread().join();
    }

    private static void runInstallation() {
        try {
            updateProgress(5, "Detectando distribuição Linux...");
            String distro = detectDistro();
            sleep(1000);

            updateProgress(10, "Configurando firewall (iptables)...");
            configureFirewall();
            sleep(500);

            updateProgress(20, "Instalando dependências do sistema...");
            installSystemDependencies(distro);
            sleep(500);

            updateProgress(40, "Instalando Oracle JDK 21...");
            installJava(distro);
            sleep(500);

            updateProgress(55, "Instalando Maven...");
            installMaven(distro);
            sleep(500);

            updateProgress(65, "Instalando PostgreSQL...");
            installPostgreSQL(distro);
            sleep(500);

            updateProgress(75, "Configurando PostgreSQL...");
            configurePostgreSQL(distro);
            sleep(500);

            updateProgress(85, "Compilando projeto Spring Boot...");
            buildProject();
            sleep(500);

            updateProgress(95, "Criando systemd service...");
            createSystemdService();
            sleep(500);

            updateProgress(100, "Instalação concluída!");

            // Aguarda 10s e encerra
            Thread.sleep(10000);
            System.exit(0);

        } catch (Exception e) {
            e.printStackTrace();
            updateProgress(100, "ERRO: " + e.getMessage());
        }
    }

    // ========== ETAPAS DE INSTALAÇÃO ==========

    private static void configureFirewall() {
        int[] ports = {22, 80, 443, 5432, 8081, 5000};
        runCmd("systemctl stop firewalld ufw 2>/dev/null || true", false);
        runCmd("systemctl disable firewalld ufw 2>/dev/null || true", false);

        for (int port : ports) {
            runCmd("iptables -I INPUT 1 -p tcp --dport " + port + " -j ACCEPT", false);
        }

        // Persistir regras
        if (Files.exists(Paths.get("/etc/init.d/iptables-persistent"))) {
            runCmd("iptables-save > /etc/iptables/rules.v4", false);
        } else if (Files.exists(Paths.get("/etc/sysconfig/iptables"))) {
            runCmd("iptables-save > /etc/sysconfig/iptables", false);
        }
    }

    private static void installSystemDependencies(String distro) {
        switch (distro) {
            case "debian":
                runCmd("apt-get update", true);
                runCmd("DEBIAN_FRONTEND=noninteractive apt-get install -y curl git", true);
                break;
            case "rhel":
                runCmd("dnf makecache", true);
                runCmd("dnf install -y curl git", true);
                break;
            case "arch":
                runCmd("pacman -Sy --noconfirm curl git", true);
                break;
        }
    }

    private static void installJava(String distro) {
        String javaCheck = runCmd("java -version 2>&1", false);
        if (javaCheck != null && javaCheck.contains("Oracle")) {
            return; // Já instalado
        }

        switch (distro) {
            case "debian":
                runCmd("curl -s -L -o /tmp/jdk.deb https://download.oracle.com/java/21/latest/jdk-21_linux-x64_bin.deb", true);
                runCmd("DEBIAN_FRONTEND=noninteractive dpkg -i /tmp/jdk.deb", true);
                break;
            case "rhel":
                runCmd("dnf install -y https://download.oracle.com/java/21/latest/jdk-21_linux-x64_bin.rpm", true);
                break;
            case "arch":
                runCmd("curl -s -L -o /tmp/jdk.tar.gz https://download.oracle.com/java/21/latest/jdk-21_linux-x64_bin.tar.gz", true);
                runCmd("tar -xzf /tmp/jdk.tar.gz -C /opt/", true);
                runCmd("ln -sf /opt/jdk-21*/bin/java /usr/bin/java", false);
                break;
        }
    }

    private static void installMaven(String distro) {
        if (runCmd("which mvn", false) != null) return;

        switch (distro) {
            case "debian":
                runCmd("DEBIAN_FRONTEND=noninteractive apt-get install -y maven", true);
                break;
            case "rhel":
                runCmd("dnf install -y maven", true);
                break;
            case "arch":
                runCmd("pacman -S --noconfirm maven", true);
                break;
        }
    }

    private static void installPostgreSQL(String distro) {
        String pkg = distro.equals("rhel") ?
            "postgresql postgresql-server postgresql-contrib" :
            "postgresql postgresql-contrib";

        switch (distro) {
            case "debian":
                runCmd("DEBIAN_FRONTEND=noninteractive apt-get install -y " + pkg, true);
                break;
            case "rhel":
                runCmd("dnf install -y " + pkg, true);
                break;
            case "arch":
                runCmd("pacman -S --noconfirm " + pkg, true);
                break;
        }
    }

    private static void configurePostgreSQL(String distro) {
        String svc = distro.equals("rhel") ? "postgresql-server" : "postgresql";

        if (distro.equals("rhel")) {
            String pgdata = runCmd("ls -A /var/lib/pgsql/data 2>/dev/null", false);
            if (pgdata == null || pgdata.trim().isEmpty()) {
                runCmd("chown -R postgres:postgres /var/lib/pgsql", false);
                runCmd("/usr/bin/postgresql-setup --initdb", true);
            }
        } else if (distro.equals("arch")) {
            if (!Files.exists(Paths.get("/var/lib/postgres/data/PG_VERSION"))) {
                runCmd("sudo -u postgres initdb -D /var/lib/postgres/data", true);
            }
        }

        runCmd("systemctl enable " + svc, false);
        runCmd("systemctl start " + svc, false);

        // Configurar autenticação
        String pgHba = distro.equals("rhel") ? "/var/lib/pgsql/data/pg_hba.conf" :
                       distro.equals("arch") ? "/var/lib/postgres/data/pg_hba.conf" :
                       "/etc/postgresql/*/main/pg_hba.conf";

        runCmd("grep -q '0.0.0.0/0' " + pgHba + " || echo 'host all all 0.0.0.0/0 md5' >> " + pgHba, false);
        runCmd("systemctl restart " + svc, false);
    }

    private static void buildProject() {
        String appDir = System.getProperty("user.dir");
        runCmd("cd " + appDir + " && mvn -B -DskipTests clean package", true);
        // DEVOLVE o target/ ao dono do projeto (evita o "error while writing .class")
        try {
            String owner = Files.getOwner(Paths.get(appDir)).getName();
            runCmd("chown -R " + owner + ":" + owner + " " + appDir + "/target", false);
        } catch (IOException ignored) {}
    }

    private static void createSystemdService() {
        String appDir = System.getProperty("user.dir");
        String jarPath = appDir + "/target/astral-platform-1.0.0.jar";

        String serviceContent = """
            [Unit]
            Description=Astral Platform Spring Boot Application
            After=network.target postgresql.service
            Requires=postgresql.service

            [Service]
            Type=simple
            User=root
            WorkingDirectory=%s
            ExecStart=/usr/bin/java -jar %s
            Restart=always
            RestartSec=10
            StandardOutput=journal
            StandardError=journal

            [Install]
            WantedBy=multi-user.target
            """.formatted(appDir, jarPath);

        try {
            Files.writeString(Paths.get("/etc/systemd/system/astral-platform.service"), serviceContent);
            runCmd("systemctl daemon-reload", false);
            runCmd("systemctl enable astral-platform.service", false);
            runCmd("systemctl start astral-platform.service", false);
        } catch (IOException e) {
            throw new RuntimeException("Falha ao criar systemd service", e);
        }
    }

    // ========== HANDLERS HTTP ==========

    private static void handleIndex(HttpExchange exchange) throws IOException {
        String response = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>Astral Platform - Instalação</title>
                <meta http-equiv="refresh" content="0; url=/install.html">
            </head>
            <body>Redirecionando...</body>
            </html>
            """;
        sendResponse(exchange, 200, response, "text/html");
    }

    private static void handleInstallHTML(HttpExchange exchange) throws IOException {
        String html = """
            <!DOCTYPE html>
            <html lang="pt-br">
            <head>
                <meta charset="UTF-8">
                <title>Instalando Astral Platform</title>
                <style>
                    body { background: #05070d; color: #fff; font-family: 'Segoe UI', sans-serif;
                           display: flex; flex-direction: column; align-items: center;
                           justify-content: center; height: 100vh; margin: 0; }
                    .box { width: 500px; background: rgba(4,10,22,.8); border: 2px solid #3fa9ff;
                           border-radius: 14px; padding: 30px; text-align: center; }
                    h1 { color: #9fd8ff; font-family: 'Orbitron', sans-serif; }
                    .bar { width: 100%; background: #111; height: 20px; border-radius: 10px;
                           overflow: hidden; margin: 20px 0; border: 1px solid #333; }
                    .fill { width: 0%; height: 100%; background: linear-gradient(90deg, #3fa9ff, #1668ff);
                            transition: width 0.4s; }
                    #status { font-size: 14px; color: #aaa; }
                </style>
            </head>
            <body>
                <div class="box">
                    <h1>🚀 Instalando Astral Platform</h1>
                    <div class="bar"><div class="fill" id="fill"></div></div>
                    <div id="status">Iniciando...</div>
                </div>
                <script>
                    const evt = new EventSource('/api/stream');
                    evt.onmessage = (e) => {
                        const data = JSON.parse(e.data);
                        document.getElementById('fill').style.width = data.progress + '%';
                        document.getElementById('status').textContent = data.status;
                        if (data.progress >= 100) {
                            evt.close();
                            setTimeout(() => {
                                alert('Instalação concluída! Acesse: http://' + location.hostname);
                                window.location.href = '/';
                            }, 1000);
                        }
                    };
                </script>
            </body>
            </html>
            """;
        sendResponse(exchange, 200, html, "text/html");
    }

    private static void handleStream(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);

        try (OutputStream os = exchange.getResponseBody()) {
            while (true) {
                String data = "data: {\"progress\": " + progress.get() +
                             ", \"status\": \"" + status + "\"}\n\n";
                os.write(data.getBytes());
                os.flush();

                if (progress.get() >= 100) break;
                Thread.sleep(500);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ========== UTILITÁRIOS ==========
    // ========== HELPER DE RESPOSTA HTTP ==========
    private static void sendResponse(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=UTF-8");
        if (bytes.length == 0) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
    private static void updateProgress(int p, String s) {
        progress.set(p);
        status = s;
        System.out.println("[" + p + "%] " + s);
    }

    private static String runCmd(String cmd, boolean log) {
        if (log) System.out.println("$ " + cmd);
        try {
            Process p = new ProcessBuilder("bash", "-c", cmd)
                .redirectErrorStream(true)
                .start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (log) System.out.println("  " + line);
                    output.append(line).append("\n");
                }
            }

            int exitCode = p.waitFor();
            if (exitCode != 0 && log) {
                System.err.println("Comando falhou com código: " + exitCode);
            }
            return output.toString();
        } catch (Exception e) {
            if (log) e.printStackTrace();
            return null;
        }
    }

    private static String detectDistro() {
        try {
            String content = Files.readString(Paths.get("/etc/os-release")).toLowerCase();
            if (content.contains("debian") || content.contains("ubuntu")) return "debian";
            if (content.contains("rhel") || content.contains("fedora") ||
                content.contains("centos") || content.contains("rocky")) return "rhel";
            if (content.contains("arch")) return "arch";
        } catch (IOException e) {
            // Ignora
        }
        return "unknown";
    }

    private static String getLocalIP() {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new InetSocketAddress("8.8.8.8", 80));
            return s.getLocalAddress().getHostAddress();
        } catch (IOException e) {
            return "127.0.0.1";
        }
    }

    private static boolean isRoot() {
        return System.getProperty("user.name").equals("root") ||
               ProcessHandle.current().info().user().orElse("").equals("root");
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
