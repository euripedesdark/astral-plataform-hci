package com.astral.tools;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Uninstaller {

    private static final int PORT = 5000;
    private static final AtomicInteger progress = new AtomicInteger(0);
    private static String status = "Aguardando conexão...";

    public static void main(String[] args) throws Exception {
        if (!isRoot()) { System.err.println("ERRO: Execute com sudo"); System.exit(1); }

        System.out.println("=".repeat(60));
        System.out.println("[ASTRAL PLATFORM] DESINSTALADOR JAVA (Web UI)");
        System.out.println("=".repeat(60));
        System.out.println("Acesse: http://" + getLocalIP() + ":" + PORT);
        System.out.println("=".repeat(60));

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", Uninstaller::handleIndex);
        server.createContext("/api/stream", Uninstaller::handleStream);
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();

        Thread t = new Thread(Uninstaller::runUninstallation, "uninstaller");
        t.setDaemon(true);
        t.start();

        Thread.currentThread().join();
    }

    private static void runUninstallation() {
        try {
            sleep(2000); // Aguarda conexão da UI

            updateProgress(10, "Parando e removendo o service do Spring Boot...");
            runCmd("systemctl stop astral-platform.service", true);
            runCmd("systemctl disable astral-platform.service", true);
            Files.deleteIfExists(Paths.get("/etc/systemd/system/astral-platform.service"));
            runCmd("systemctl daemon-reload", false);

            updateProgress(25, "Removendo artefatos de produção (/opt, /etc/astral)...");
            runCmd("rm -rf /opt/astral-platform /etc/astral", true);
            Files.deleteIfExists(Paths.get("/etc/profile.d/java_home.sh"));

            updateProgress(35, "Removendo base de dados e usuário 'astral'...");
            runCmd("sudo -i -u postgres psql -c \"DROP DATABASE IF EXISTS astral;\"", false);
            runCmd("sudo -i -u postgres psql -c \"DROP USER IF EXISTS astral;\"", false);

            updateProgress(50, "Removendo PostgreSQL, Maven, JDK, Nginx e Node.js...");
            runCmd("systemctl stop postgresql postgresql-server nginx 2>/dev/null || true", false);
            runCmd("dnf remove -y postgresql postgresql-server postgresql-contrib maven nodejs npm nginx 2>/dev/null || " +
                   "apt-get purge -y postgresql postgresql-client postgresql-contrib maven nodejs npm nginx 2>/dev/null || true", true);
            runCmd("rm -rf /var/lib/pgsql /var/lib/postgres /var/lib/postgresql /etc/postgresql /var/log/postgresql /run/postgresql /opt/jdk-21*", true);

            updateProgress(65, "Removendo grupo 'astral'...");
            runCmd("groupdel astral 2>/dev/null || true", false);

            updateProgress(75, "Limpando regras de firewall injetadas...");
            int[] ports = {22, 80, 443, 3000, 5000, 5173, 5432, 8081, 9090};
            for (int p : ports) {
                while (runCmd("iptables -D INPUT -p tcp --dport " + p + " -j ACCEPT", false) == 0) { }
            }

            updateProgress(90, "Limpando artefatos de build locais...");
            String appDir = System.getProperty("user.dir");
            runCmd("rm -rf " + appDir + "/target " + appDir + "/tools-classes " +
                   appDir + "/installer.jar " + appDir + "/uninstaller.jar", true);

            updateProgress(100, "Desinstalação concluída com sucesso!");
            sleep(3000);
            System.exit(0);

        } catch (Exception e) {
            e.printStackTrace();
            updateProgress(100, "ERRO: " + e.getMessage());
        }
    }

    // ============================================================
    // HANDLERS HTTP (Web UI)
    // ============================================================
    private static void handleIndex(HttpExchange ex) throws IOException {
        String html = """
            <!DOCTYPE html>
            <html lang="pt-br">
            <head>
                <meta charset="UTF-8">
                <title>ASTRAL PLATFORM - Desinstalação</title>
                <style>
                    body { background: #05070d; color: #fff; font-family: 'Segoe UI', sans-serif; display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; }
                    .box { width: 500px; background: rgba(4,10,22,.8); border: 2px solid #ff5c5c; border-radius: 14px; padding: 30px; text-align: center; }
                    h1 { color: #ff8888; margin-top: 0; }
                    .bar { width: 100%; background: #111; height: 20px; border-radius: 10px; overflow: hidden; margin: 20px 0; border: 1px solid #333; }
                    .fill { width: 0%; height: 100%; background: linear-gradient(90deg, #ff5c5c, #cc0000); transition: width 0.4s; }
                    #status { color: #aaa; font-size: 14px; }
                </style>
            </head>
            <body>
                <div class="box">
                    <h1>🗑️ Desinstalando Astral</h1>
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
                            setTimeout(() => alert('Desinstalação concluída com sucesso! O terminal será encerrado.'), 1000);
                        }
                    };
                </script>
            </body>
            </html>
            """;
        sendResponse(ex, 200, html, "text/html");
    }

    private static void handleStream(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream os = ex.getResponseBody()) {
            while (true) {
                String data = "data: {\"progress\": " + progress.get() + ", \"status\": \"" + status + "\"}\n\n";
                os.write(data.getBytes());
                os.flush();
                if (progress.get() >= 100) break;
                Thread.sleep(500);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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

    private static void updateProgress(int p, String s) {
        progress.set(p);
        status = s;
        System.out.println("[" + p + "%] " + s);
    }

    private static int runCmd(String cmd, boolean log) {
        if (log) System.out.println("$ " + cmd);
        try {
            Process p = new ProcessBuilder("bash", "-c", cmd).redirectErrorStream(true).start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) if (log) System.out.println("  " + line);
            }
            return p.waitFor();
        } catch (Exception e) {
            return -1;
        }
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
