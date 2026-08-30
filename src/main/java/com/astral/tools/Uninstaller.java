package com.astral.tools;

import java.io.*;
import java.nio.file.*;

public class Uninstaller {

    public static void main(String[] args) throws Exception {
        if (!isRoot()) { System.err.println("ERRO: Execute com sudo"); System.exit(1); }

        System.out.println("=".repeat(60));
        System.out.println("[ASTRAL PLATFORM] DESINSTALADOR JAVA");
        System.out.println("=".repeat(60));

        step(10, "Parando e removendo o service do Spring Boot...");
        run("systemctl stop astral-platform.service", true);
        run("systemctl disable astral-platform.service", true);
        Files.deleteIfExists(Paths.get("/etc/systemd/system/astral-platform.service"));
        run("systemctl daemon-reload", false);

        step(20, "Removendo artefatos de produção (/opt, /etc/astral)...");
        run("rm -rf /opt/astral-platform /etc/astral", true);
        Files.deleteIfExists(Paths.get("/etc/profile.d/java_home.sh"));

        step(30, "Removendo configurações do Nginx...");
        run("rm -f /etc/nginx/conf.d/astral.conf /etc/nginx/sites-available/astral.conf /etc/nginx/sites-enabled/astral.conf", true);
        run("systemctl restart nginx 2>/dev/null || true", false);

        step(45, "Removendo base de dados e usuário 'astral'...");
        run("sudo -i -u postgres psql -c \"DROP DATABASE IF EXISTS astral;\"", false);
        run("sudo -i -u postgres psql -c \"DROP USER IF EXISTS astral;\"", false);

        step(60, "Removendo PostgreSQL, Maven, JDK e Node.js...");
        run("systemctl stop postgresql postgresql-server 2>/dev/null || true", false);
        run("dnf remove -y postgresql postgresql-server postgresql-contrib maven nodejs npm 2>/dev/null || " +
            "apt-get purge -y postgresql postgresql-client postgresql-contrib maven nodejs npm 2>/dev/null || true", true);
        // Extermina todos os diretórios possíveis do PostgreSQL (Debian, RHEL, Arch)
        run("rm -rf /var/lib/pgsql /var/lib/postgres /var/lib/postgresql /etc/postgresql /var/log/postgresql /run/postgresql /opt/jdk-21*", true);

        step(75, "Limpando regras de firewall injetadas...");
        int[] ports = {22, 80, 443, 3000, 5000, 5173, 5432, 8081, 9090};
        for (int p : ports) {
            while (run("iptables -D INPUT -p tcp --dport " + p + " -j ACCEPT", false) == 0) { /* repete até acabar */ }
        }

        step(85, "Limpando artefatos de build locais...");
        String appDir = System.getProperty("user.dir");
        run("rm -rf " + appDir + "/target " + appDir + "/tools-classes " +
            appDir + "/installer.jar " + appDir + "/uninstaller.jar", true);

        step(100, "Desinstalação concluída!");
        System.out.println("=".repeat(60));
        System.out.println("Ambiente limpo. Para reinstalar: sudo /usr/lib/jvm/jdk-21.0.12.1-oracle-x64/bin/java -jar installer.jar");
        System.out.println("=".repeat(60));
    }

    private static void step(int pct, String msg) {
        System.out.println("[" + pct + "%] " + msg);
    }

    private static int run(String cmd, boolean log) {
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

    private static boolean isRoot() {
        return ProcessHandle.current().info().user().orElse("").equals("root") ||
               System.getProperty("user.name").equals("root");
    }
}
