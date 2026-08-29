package com.astral.tools;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Desinstalador Standalone - Astral Platform
 * Uso: sudo java -cp target/classes com.astral.tools.Uninstaller
 */
public class Uninstaller {

    private static final AtomicInteger progress = new AtomicInteger(0);

    public static void main(String[] args) throws Exception {
        if (!isRoot()) {
            System.err.println("ERRO: Execute com sudo");
            System.exit(1);
        }

        System.out.println("=".repeat(60));
        System.out.println("[ASTRAL PLATFORM] DESINSTALADOR");
        System.out.println("=".repeat(60));

        runUninstallation();

        System.out.println("=".repeat(60));
        System.out.println("✅ Desinstalação concluída!");
        System.out.println("=".repeat(60));
    }

    private static void runUninstallation() {
        try {
            updateProgress(10, "Parando serviço astral-platform...");
            runCmd("systemctl stop astral-platform.service", false);
            runCmd("systemctl disable astral-platform.service", false);

            updateProgress(20, "Removendo systemd service...");
            Files.deleteIfExists(Paths.get("/etc/systemd/system/astral-platform.service"));
            runCmd("systemctl daemon-reload", false);

            updateProgress(30, "Removendo Nginx (se instalado)...");
            runCmd("systemctl stop nginx 2>/dev/null || true", false);
            runCmd("systemctl disable nginx 2>/dev/null || true", false);
            runCmd("dnf remove -y nginx 2>/dev/null || apt-get purge -y nginx 2>/dev/null || true", false);
            runCmd("rm -f /etc/nginx/conf.d/astral.conf", false);
            runCmd("rm -f /etc/nginx/sites-available/astral.conf", false);
            runCmd("rm -f /etc/nginx/sites-enabled/astral.conf", false);

            updateProgress(50, "Removendo base de dados 'astral'...");
            runCmd("sudo -u postgres psql -c \"DROP DATABASE IF EXISTS astral;\"", false);
            runCmd("sudo -u postgres psql -c \"DROP USER IF EXISTS astral;\"", false);

            updateProgress(70, "Limpando configurações...");
            runCmd("rm -rf /opt/astral-platform", false);
            runCmd("rm -rf /etc/astral", false);
            Files.deleteIfExists(Paths.get("/etc/profile.d/java_home.sh"));

            updateProgress(80, "Limpando regras de firewall...");
            int[] ports = {22, 80, 443, 5432, 8081, 5000};
            for (int port : ports) {
                while (runCmd("iptables -D INPUT -p tcp --dport " + port + " -j ACCEPT", false) == 0) {
                    // Continua removendo até não existir mais
                }
            }

            updateProgress(90, "Limpando artefatos de build...");
            String appDir = System.getProperty("user.dir");
            runCmd("rm -rf " + appDir + "/target", false);
            runCmd("rm -rf " + appDir + "/.mvn", false);
            Files.deleteIfExists(Paths.get(appDir, "mvnw"));
            Files.deleteIfExists(Paths.get(appDir, "mvnw.cmd"));

            updateProgress(100, "Desinstalação concluída!");

        } catch (Exception e) {
            e.printStackTrace();
            updateProgress(100, "ERRO: " + e.getMessage());
        }
    }

    private static void updateProgress(int p, String s) {
        progress.set(p);
        System.out.println("[" + p + "%] " + s);
    }

    private static int runCmd(String cmd, boolean log) {
        if (log) System.out.println("$ " + cmd);
        try {
            Process p = new ProcessBuilder("bash", "-c", cmd)
                .redirectErrorStream(true)
                .start();

            if (log) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        System.out.println("  " + line);
                    }
                }
            }

            return p.waitFor();
        } catch (Exception e) {
            if (log) e.printStackTrace();
            return -1;
        }
    }

    private static boolean isRoot() {
        return System.getProperty("user.name").equals("root") ||
               ProcessHandle.current().info().user().orElse("").equals("root");
    }
}
