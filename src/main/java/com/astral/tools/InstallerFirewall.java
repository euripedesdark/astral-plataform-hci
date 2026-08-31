package com.astral.tools;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class InstallerFirewall {
    private static final int PORT = 5001;
    private static final AtomicInteger progress = new AtomicInteger(0);
    private static String status = "Aguardando...";
    private static volatile boolean done = false;
    private static final String ASTRAL_GROUP = "astral";

    public static void main(String[] args) throws Exception {
        if (!isRoot()) { System.err.println("ERRO: use sudo"); System.exit(1); }
        freePortIfHeldByOldInstance(PORT);
        HttpServer s;
        try {
            s = HttpServer.create(new InetSocketAddress(PORT), 0);
        } catch (java.net.BindException e) {
            System.err.println("ERRO: porta " + PORT + " já está em uso e não foi possível liberá-la automaticamente.");
            System.err.println("Quem está segurando a porta:");
            System.err.println(run("ss -ltnp 2>/dev/null | grep ':" + PORT + "' || lsof -i:" + PORT + " 2>/dev/null", false));
            System.err.println("Finalize esse processo manualmente (kill <PID>) e rode o instalador de novo.");
            System.exit(1);
            return;
        }
        s.createContext("/", e -> send(e, 200, "<meta http-equiv='refresh' content='0; url=/install.html'>", "text/html"));
        s.createContext("/install.html", InstallerFirewall::ui);
        s.createContext("/api/stream", InstallerFirewall::stream);
        s.setExecutor(Executors.newCachedThreadPool());
        s.start();
        System.out.println("=".repeat(60));
        System.out.println("[ASTRAL FIREWALL] Instalador em http://" + getLocalIP() + ":" + PORT);
        System.out.println("=".repeat(60));
        Thread t = new Thread(InstallerFirewall::run);
        t.setDaemon(true);
        t.start();
        Thread.currentThread().join();
    }

    private static void run() {
        try {
            up(5, "Detectando distribuição...");
            String distro = detectDistro();
            up(8, "Liberando porta da UI no iptables (sem apagar regras existentes)...");
            run("iptables -C INPUT -p tcp --dport " + PORT + " -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p tcp --dport " + PORT + " -j ACCEPT", false);
            run("iptables -C INPUT -p tcp --dport 8040 -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p tcp --dport 8040 -j ACCEPT", false);
            up(10, "Garantindo iptables/ipset persistentes...");
            if (distro.equals("debian")) run("DEBIAN_FRONTEND=noninteractive apt-get install -y iptables-persistent ipset", true);
            else if (distro.equals("arch")) run("pacman -S --noconfirm iptables-nft ipset", true);
            else run("dnf install -y iptables-services ipset", true);
            up(20, "Validando conexão com banco via mTLS...");
            if (!validateMTLSConnection()) {
                up(100, "ERRO: Falha ao conectar no banco via mTLS. Execute o instalador principal primeiro.");
                done = true;
                return;
            }
            up(30, "Criando tabelas do firewall no banco...");
            createTables();
            up(48, "Limpando build anterior do módulo (evita lixo de execuções antigas)...");
            cleanModule();
            up(50, "Escrevendo projeto do módulo...");
            writeProject();
            fixOwnership();
            verifyGeneratedSources();
            up(70, "Compilando módulo (mvn package)...");
            String owner;
            try { owner = Files.getOwner(Paths.get(app())).getName(); } catch (IOException e) { owner = "root"; }
            String mvnCmd = "cd " + app() + "/fabric/firewall && runuser -u " + owner + " -- mvn -B -DskipTests clean package";
            String mvnOut = run(mvnCmd, true);
            fixOwnership();
            Path jarPath = Paths.get(app(), "fabric", "firewall", "target", "astral-firewall-1.0.0.jar");
            if (!Files.exists(jarPath)) {
                up(100, "ERRO CRÍTICO: mvn não gerou o JAR. Verifique saída acima.");
                System.err.println("[FALHA] JAR esperado em " + jarPath + " não existe.");
                if (mvnOut != null) {
                    String[] lines = mvnOut.split("\n");
                    for (int i = Math.max(0, lines.length - 30); i < lines.length; i++)
                        System.err.println("  " + lines[i]);
                }
                done = true;
                return;
            }
            System.out.println("[OK] JAR gerado: " + jarPath + " (" + Files.size(jarPath) + " bytes)");
            up(80, "Deploy em /opt/astral-firewall + systemd service...");
            deploy(jarPath);
            up(90, "Aguardando 127.0.0.1:8040...");
            boolean ok = false;
            for (int i = 0; i < 30; i++) {
                try (var sk = new java.net.Socket()) {
                    sk.connect(new InetSocketAddress("127.0.0.1", 8040), 500);
                    ok = true;
                    break;
                } catch (IOException e) { sleep(1000); }
            }
            up(100, ok ? "Módulo Firewall instalado! Acesse http://" + getLocalIP() + "/firewall"
                    : "AVISO: 8040 não respondeu. journalctl -u astral-firewall");
            done = true;
        } catch (Exception e) {
            e.printStackTrace();
            up(100, "ERRO: " + e.getMessage());
            done = true;
        }
    }

    private static boolean validateMTLSConnection() {
        try {
            Path propsPath = Paths.get("/etc/astral/application.properties");
            if (!Files.exists(propsPath)) {
                System.err.println("[ERRO] /etc/astral/application.properties não encontrado.");
                return false;
            }
            String props = Files.readString(propsPath);
            if (!props.contains("spring.datasource.username=astral")) {
                System.err.println("[ERRO] application.properties não configurado para usuário astral.");
                return false;
            }
            String cmd = "PGSSLCERT=/etc/astral/certs/client-astral.crt " +
                    "PGSSLKEY=/etc/astral/certs/client-astral.pk8 " +
                    "PGSSLROOTCERT=/etc/astral/certs/root.crt " +
                    "PGSSLMODE=verify-ca " +
                    "psql -h 127.0.0.1 -U astral -d astral -t -c 'SELECT 1'";
            String result = run(cmd, false);
            boolean success = result != null && result.trim().contains("1");
            if (success) System.out.println("[OK] Conexão mTLS validada com sucesso.");
            else System.err.println("[ERRO] Falha na conexão mTLS. Saída: " + result);
            return success;
        } catch (Exception e) {
            System.err.println("[ERRO] " + e.getMessage());
            return false;
        }
    }

    private static String app() { return System.getProperty("user.dir"); }

    private static void fixOwnership() {
        try {
            String owner = Files.getOwner(Paths.get(app())).getName();
            Path fw = Paths.get(app(), "fabric", "firewall");
            if (!Files.exists(fw)) return;
            run("chown -R " + owner + ":" + ASTRAL_GROUP + " " + fw + " 2>/dev/null || true", false);
            run("find " + fw + " -type d -exec chmod 2775 {} \\; 2>/dev/null || true", false);
            run("find " + fw + " -type f -exec chmod 0664 {} \\; 2>/dev/null || true", false);
            Path target = fw.resolve("target");
            if (!Files.exists(target)) Files.createDirectories(target);
            run("chown -R " + owner + ":" + ASTRAL_GROUP + " " + target + " 2>/dev/null || true", false);
            run("chmod 2775 " + target + " 2>/dev/null || true", false);
            System.out.println("[OK] Ownership: " + owner + ":" + ASTRAL_GROUP);
        } catch (IOException ignored) {}
    }

    private static void createTables() throws IOException {
        Path f = Paths.get("/tmp/astral-fw-tables.sql");
        Files.writeString(f, TABLES_SQL);
        run("chmod 0644 " + f, false);
        run("runuser -u postgres -- psql -d astral -f " + f, true);
    }

    private static void deploy(Path jarPath) {
        run("mkdir -p /opt/astral-firewall", true);
        run("cp " + jarPath.toAbsolutePath() + " /opt/astral-firewall/", true);
        run("chown -R root:" + ASTRAL_GROUP + " /opt/astral-firewall", true);
        run("chmod 2770 /opt/astral-firewall", true);
        run("chmod 0660 /opt/astral-firewall/*.jar 2>/dev/null || true", false);
        try {
            Files.createDirectories(Paths.get("/etc/astral"));
            Path props = Paths.get("/etc/astral/firewall.properties");
            Files.writeString(props,
                    "server.port=8040\nserver.address=127.0.0.1\n"
                            + "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/astral?ssl=true&sslmode=verify-ca&sslcert=/etc/astral/certs/client-astral.crt&sslkey=/etc/astral/certs/client-astral.pk8&sslrootcert=/etc/astral/certs/root.crt\n"
                            + "spring.datasource.username=astral\n"
                            + "spring.datasource.driver-class-name=org.postgresql.Driver\n"
                            + "spring.jpa.hibernate.ddl-auto=update\n"
                            + "spring.thymeleaf.cache=false\n");
            run("chown root:" + ASTRAL_GROUP + " " + props, false);
            run("chmod 0640 " + props, false);
            System.out.println("[OK] " + props + " com 0640 root:" + ASTRAL_GROUP);
        } catch (IOException ignored) {}
        String javaBin = run("readlink -f $(which java)", false);
        if (javaBin != null) javaBin = javaBin.trim(); else javaBin = "/usr/bin/java";
        try {
            Files.writeString(Paths.get("/etc/systemd/system/astral-firewall.service"),
                    "[Unit]\nDescription=Astral Firewall Module\nAfter=network.target postgresql.service astral-platform.service\n\n"
                            + "[Service]\nType=simple\nUser=root\nGroup=" + ASTRAL_GROUP + "\n"
                            + "WorkingDirectory=/opt/astral-firewall\n"
                            + "ExecStart=" + javaBin + " -jar /opt/astral-firewall/astral-firewall-1.0.0.jar --spring.config.location=file:/etc/astral/firewall.properties\n"
                            + "Restart=always\nRestartSec=10\nStandardOutput=journal\nStandardError=journal\nUMask=0007\n\n"
                            + "[Install]\nWantedBy=multi-user.target\n");
        } catch (IOException ignored) {}
        run("systemctl daemon-reload && systemctl enable astral-firewall && systemctl restart astral-firewall", false);
    }

    private static void ui(HttpExchange ex) throws IOException {
        send(ex, 200, """
            <!DOCTYPE html><html><head><meta charset="UTF-8"><style>
            body{background:#05070d;color:#fff;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0}
            .box{width:480px;border:2px solid #ff5c5c;border-radius:14px;padding:26px;text-align:center;background:rgba(10,4,6,.85)}
            h1{color:#ff8888}.bar{height:18px;background:#111;border-radius:9px;overflow:hidden;margin:16px 0}
            .fill{height:100%;width:0;background:linear-gradient(90deg,#ff5c5c,#c22);transition:width .4s}
            #status{color:#aaa;font-size:14px}
            </style></head><body><div class="box">
            <h1>🔥 ASTRAL FIREWALL — INSTALADOR</h1>
            <div class="bar"><div class="fill" id="fill"></div></div><div id="status">...</div>
            </div>
            <script>var e=new EventSource('/api/stream');e.onmessage=function(ev){var d=JSON.parse(ev.data);
            document.getElementById('fill').style.width=d.progress+'%';document.getElementById('status').textContent=d.status;
            if(d.progress>=100)e.close();}</script></body></html>""", "text/html");
    }

    private static void stream(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream os = ex.getResponseBody()) {
            while (true) {
                os.write(("data: {\"progress\": " + progress.get() + ", \"status\": \"" + status + "\"}\n\n").getBytes());
                os.flush();
                if (done) break;
                Thread.sleep(500);
            }
        } catch (IOException ignored) {
        } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private static void send(HttpExchange ex, int c, String b, String t) throws IOException {
        byte[] x = b.getBytes();
        ex.getResponseHeaders().set("Content-Type", t);
        ex.sendResponseHeaders(c, x.length);
        ex.getResponseBody().write(x);
        ex.close();
    }

    private static void up(int p, String s) {
        progress.set(p);
        status = s;
        System.out.println("[" + p + "%] " + s);
    }

    private static String run(String c, boolean log) {
        if (log) System.out.println("$ " + c);
        try {
            Process p = new ProcessBuilder("bash", "-c", c).redirectErrorStream(true).start();
            String o = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            return o;
        } catch (Exception e) {
            return "";
        }
    }

    private static String detectDistro() {
        try {
            String c = Files.readString(Paths.get("/etc/os-release")).toLowerCase();
            if (c.contains("debian") || c.contains("ubuntu")) return "debian";
            if (c.contains("arch")) return "arch";
        } catch (Exception ignored) {}
        return "rhel";
    }

    private static String getLocalIP() {
        try {
            Process p = new ProcessBuilder("bash", "-c", "ip route get 1.1.1.1 | awk '/src/{for(i=1;i<=NF;i++)if($i==\"src\")print $(i+1)}'").start();
            return new String(p.getInputStream().readAllBytes()).trim();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    private static void freePortIfHeldByOldInstance(int port) {
        // Mata qualquer processo java segurando essa porta que NÃO seja este processo atual
        // (cobre instâncias soltas em background que 'pkill -f installer-firewall.jar' não pegou,
        // por exemplo se o jar foi renomeado ou rodado com outro classpath).
        String pids = run("ss -ltnp 2>/dev/null | grep ':" + port + " ' | grep -oP 'pid=\\K[0-9]+' | sort -u"
                + " || fuser " + port + "/tcp 2>/dev/null", false);
        if (pids == null) return;
        long myPid = ProcessHandle.current().pid();
        for (String pidStr : pids.trim().split("\\s+")) {
            if (pidStr.isBlank()) continue;
            try {
                long pid = Long.parseLong(pidStr.trim());
                if (pid == myPid) continue;
                System.out.println("[AVISO] Porta " + port + " ocupada pelo PID " + pid + " — finalizando instância antiga...");
                run("kill -9 " + pid, true);
            } catch (NumberFormatException ignored) {}
        }
        if (pids.trim().isEmpty()) return;
        sleep(500);
    }

    private static boolean isRoot() { return System.getProperty("user.name").equals("root"); }
    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (Exception ignored) {} }

    private static void cleanModule() throws IOException {
        // Remove qualquer resquício de execuções anteriores do instalador (fontes antigas,
        // target/ com dono diferente, pom cacheado etc.) para garantir build 100% determinístico.
        Path fw = Paths.get(app(), "fabric", "firewall");
        if (Files.exists(fw)) {
            run("rm -rf " + fw, true);
            System.out.println("[OK] Diretório antigo removido: " + fw);
        }
        // Também limpa qualquer artefato antigo do módulo que possa ter sido instalado
        // no repositório local Maven do usuário dono do projeto (evita reaproveitar .class velho).
        try {
            String owner = Files.getOwner(Paths.get(app())).getName();
            run("runuser -u " + owner + " -- rm -rf /home/" + owner + "/.m2/repository/com/astral/astral-firewall", false);
        } catch (IOException ignored) {}
    }

    private static void verifyGeneratedSources() {
        // Sanity check pós-escrita: se algum arquivo-chave não bateu com o esperado,
        // falha cedo com uma mensagem clara em vez de deixar o mvn dar um erro genérico.
        String jbase = app() + "/fabric/firewall/src/main/java/com/astral/firewall";
        checkContains(jbase + "/model/FirewallRule.java", "public String rawRule");
        checkContains(jbase + "/service/IptablesService.java", "r.rawRule");
        checkContains(jbase + "/api/FirewallApiController.java", "rawRule");
    }

    private static void checkContains(String path, String needle) {
        try {
            String content = Files.readString(Paths.get(path));
            if (!content.contains(needle)) {
                System.err.println("[AVISO] " + path + " não contém \"" + needle + "\" após a escrita — "
                        + "possível falha de I/O ou processo concorrente sobrescrevendo o arquivo.");
            }
        } catch (IOException e) {
            System.err.println("[AVISO] Não foi possível ler " + path + " para verificação: " + e.getMessage());
        }
    }

    private static void writeProject() throws IOException {
        String base = app() + "/fabric/firewall";
        String jbase = base + "/src/main/java/com/astral/firewall";
        for (String d : new String[]{jbase + "/model", jbase + "/repo", jbase + "/service",
                jbase + "/api", jbase + "/ws", jbase + "/web",
                base + "/src/main/resources/templates"})
            Files.createDirectories(Paths.get(d));
        write(base + "/pom.xml", FW_POM);
        write(base + "/src/main/resources/application.properties", FW_PROPS);
        write(base + "/src/main/resources/templates/firewall.html", FW_HTML);
        write(jbase + "/FirewallApplication.java", FW_APP);
        write(jbase + "/model/FirewallRule.java", FW_RULE);
        write(jbase + "/model/PortForward.java", FW_PF);
        write(jbase + "/model/MasqueradeRule.java", FW_MQ);
        write(jbase + "/model/Zone.java", FW_ZONE);
        write(jbase + "/model/ZoneInterface.java", FW_ZI);
        write(jbase + "/model/HostGroup.java", FW_HG);
        write(jbase + "/model/PortGroup.java", FW_PG);
        write(jbase + "/model/Schedule.java", FW_SC);
        write(jbase + "/model/RateLimitPolicy.java", FW_RL);
        write(jbase + "/model/AutoBanRule.java", FW_AB);
        write(jbase + "/model/ThreatList.java", FW_TL);
        write(jbase + "/model/AuditLog.java", FW_AL);
        write(jbase + "/model/FirewallSnapshot.java", FW_SNAP);
        write(jbase + "/model/FirewallState.java", FW_STATE);
        write(jbase + "/repo/FirewallRuleRepo.java", "package com.astral.firewall.repo;\nimport com.astral.firewall.model.*;\nimport org.springframework.data.jpa.repository.JpaRepository;\npublic interface FirewallRuleRepo extends JpaRepository<FirewallRule,Long> { java.util.List<FirewallRule> findByChainOrderByPriority(String chain); }\n");
        write(jbase + "/repo/PortForwardRepo.java", "package com.astral.firewall.repo;\nimport com.astral.firewall.model.*;\nimport org.springframework.data.jpa.repository.JpaRepository;\npublic interface PortForwardRepo extends JpaRepository<PortForward,Long> {}\n");
        write(jbase + "/repo/MasqueradeRuleRepo.java", "package com.astral.firewall.repo;\nimport com.astral.firewall.model.*;\nimport org.springframework.data.jpa.repository.JpaRepository;\npublic interface MasqueradeRuleRepo extends JpaRepository<MasqueradeRule,Long> {}\n");
        write(jbase + "/repo/ZoneRepo.java", "package com.astral.firewall.repo;\nimport com.astral.firewall.model.*;\nimport org.springframework.data.jpa.repository.JpaRepository;\npublic interface ZoneRepo extends JpaRepository<Zone,Long> {}\n");
        write(jbase + "/repo/ZoneInterfaceRepo.java", "package com.astral.firewall.repo;\nimport com.astral.firewall.model.*;\nimport org.springframework.data.jpa.repository.JpaRepository;\npublic interface ZoneInterfaceRepo extends JpaRepository<ZoneInterface,Long> { void deleteByZoneId(Long z); }\n");
        write(jbase + "/repo/HostGroupRepo.java", "package com.astral.firewall.repo;\nimport com.astral.firewall.model.*;\nimport org.springframework.data.jpa.repository.JpaRepository;\npublic interface HostGroupRepo extends JpaRepository<HostGroup,Long> {}\n");
        write(jbase + "/repo/PortGroupRepo.java", "package com.astral.firewall.repo;\nimport com.astral.firewall.model.*;\nimport org.springframework.data.jpa.repository.JpaRepository;\npublic interface PortGroupRepo extends JpaRepository<PortGroup,Long> {}\n");
        write(jbase + "/repo/ScheduleRepo.java", "package com.astral.firewall.repo;\nimport com.astral.firewall.model.*;\nimport org.springframework.data.jpa.repository.JpaRepository;\npublic interface ScheduleRepo extends JpaRepository<Schedule,Long> {}\n");
        write(jbase + "/repo/RateLimitPolicyRepo.java", "package com.astral.firewall.repo;\nimport com.astral.firewall.model.*;\nimport org.springframework.data.jpa.repository.JpaRepository;\npublic interface RateLimitPolicyRepo extends JpaRepository<RateLimitPolicy,Long> {}\n");
        write(jbase + "/repo/AutoBanRuleRepo.java", "package com.astral.firewall.repo;\nimport com.astral.firewall.model.*;\nimport org.springframework.data.jpa.repository.JpaRepository;\npublic interface AutoBanRuleRepo extends JpaRepository<AutoBanRule,Long> {}\n");
        write(jbase + "/repo/ThreatListRepo.java", "package com.astral.firewall.repo;\nimport com.astral.firewall.model.*;\nimport org.springframework.data.jpa.repository.JpaRepository;\npublic interface ThreatListRepo extends JpaRepository<ThreatList,Long> {}\n");
        write(jbase + "/repo/AuditLogRepo.java", "package com.astral.firewall.repo;\nimport com.astral.firewall.model.*;\nimport org.springframework.data.jpa.repository.JpaRepository;\npublic interface AuditLogRepo extends JpaRepository<AuditLog,Long> { java.util.List<AuditLog> findTop200ByOrderByTimestampDesc(); }\n");
        write(jbase + "/repo/FirewallSnapshotRepo.java", "package com.astral.firewall.repo;\nimport com.astral.firewall.model.*;\nimport org.springframework.data.jpa.repository.JpaRepository;\npublic interface FirewallSnapshotRepo extends JpaRepository<FirewallSnapshot,Long> { java.util.List<FirewallSnapshot> findAllByOrderByCreatedAtDesc(); }\n");
        write(jbase + "/repo/FirewallStateRepo.java", "package com.astral.firewall.repo;\nimport com.astral.firewall.model.*;\nimport org.springframework.data.jpa.repository.JpaRepository;\npublic interface FirewallStateRepo extends JpaRepository<FirewallState,String> {}\n");
        write(jbase + "/service/AuditService.java", FW_AUDIT);
        write(jbase + "/service/IptablesService.java", FW_IPT);
        write(jbase + "/service/StatsService.java", FW_STATS);
        write(jbase + "/service/BackupService.java", FW_BK);
        write(jbase + "/api/FirewallApiController.java", FW_API);
        write(jbase + "/ws/FirewallLogsWebSocketHandler.java", FW_WS);
        write(jbase + "/ws/WsConfig.java", FW_WSC);
        write(jbase + "/web/PagesController.java", FW_PAGES);
    }

    private static void write(String p, String c) throws IOException {
        Files.writeString(Paths.get(p), c);
        System.out.println("[OK] " + Paths.get(p).getFileName());
    }

    private static final String TABLES_SQL =
            "CREATE TABLE IF NOT EXISTS firewall_rule (id BIGSERIAL PRIMARY KEY, chain VARCHAR(20), priority INT, protocol VARCHAR(10), src_cidr VARCHAR(50), dst_cidr VARCHAR(50), port VARCHAR(30), iface VARCHAR(30), action VARCHAR(30), enabled BOOLEAN, comment VARCHAR(512), raw_rule VARCHAR(1024), applied_at TIMESTAMP);\n" +
                    "CREATE TABLE IF NOT EXISTS port_forward (id BIGSERIAL PRIMARY KEY, iface VARCHAR(30), external_port VARCHAR(30), protocol VARCHAR(10), internal_ip VARCHAR(50), internal_port VARCHAR(30), enabled BOOLEAN, description VARCHAR(512));\n" +
                    "CREATE TABLE IF NOT EXISTS masquerade_rule (id BIGSERIAL PRIMARY KEY, iface VARCHAR(30), enabled BOOLEAN);\n" +
                    "CREATE TABLE IF NOT EXISTS zone (id BIGSERIAL PRIMARY KEY, name VARCHAR(80), trust_level VARCHAR(20), default_policy VARCHAR(10), color VARCHAR(20));\n" +
                    "CREATE TABLE IF NOT EXISTS zone_interface (id BIGSERIAL PRIMARY KEY, zone_id BIGINT, iface_name VARCHAR(30));\n" +
                    "CREATE TABLE IF NOT EXISTS host_group (id BIGSERIAL PRIMARY KEY, name VARCHAR(80));\n" +
                    "CREATE TABLE IF NOT EXISTS host_group_cidrs (host_group_id BIGINT NOT NULL, cidrs VARCHAR(50));\n" +
                    "CREATE TABLE IF NOT EXISTS port_group (id BIGSERIAL PRIMARY KEY, name VARCHAR(80));\n" +
                    "CREATE TABLE IF NOT EXISTS port_group_ports (port_group_id BIGINT NOT NULL, ports VARCHAR(30));\n" +
                    "CREATE TABLE IF NOT EXISTS schedule (id BIGSERIAL PRIMARY KEY, name VARCHAR(80), days_of_week VARCHAR(60), start_time TIME, end_time TIME);\n" +
                    "CREATE TABLE IF NOT EXISTS rate_limit_policy (id BIGSERIAL PRIMARY KEY, port VARCHAR(30), protocol VARCHAR(10), rate_per_second INT, enabled BOOLEAN);\n" +
                    "CREATE TABLE IF NOT EXISTS auto_ban_rule (id BIGSERIAL PRIMARY KEY, max_attempts INT, window_minutes INT, ban_minutes INT, target_port VARCHAR(30), enabled BOOLEAN);\n" +
                    "CREATE TABLE IF NOT EXISTS threat_list (id BIGSERIAL PRIMARY KEY, name VARCHAR(80), source_url VARCHAR(255), last_updated TIMESTAMP, ip_count INT, enabled BOOLEAN);\n" +
                    "CREATE TABLE IF NOT EXISTS audit_log (id BIGSERIAL PRIMARY KEY, username VARCHAR(80), entity_type VARCHAR(40), entity_id VARCHAR(40), action VARCHAR(40), diff_json VARCHAR(8192), timestamp TIMESTAMP);\n" +
                    "CREATE TABLE IF NOT EXISTS firewall_snapshot (id BIGSERIAL PRIMARY KEY, created_at TIMESTAMP, label VARCHAR(80), dump_text TEXT);\n" +
                    "CREATE TABLE IF NOT EXISTS firewall_state (key VARCHAR(40) PRIMARY KEY, value VARCHAR(80));\n";

    private static final String FW_POM =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n" +
                    "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                    "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n" +
                    "    <modelVersion>4.0.0</modelVersion>\n" +
                    "    <parent>\n" +
                    "        <groupId>org.springframework.boot</groupId>\n" +
                    "        <artifactId>spring-boot-starter-parent</artifactId>\n" +
                    "        <version>3.2.0</version>\n" +
                    "        <relativePath/>\n" +
                    "    </parent>\n" +
                    "    <groupId>com.astral</groupId>\n" +
                    "    <artifactId>astral-firewall</artifactId>\n" +
                    "    <version>1.0.0</version>\n" +
                    "    <properties><java.version>21</java.version></properties>\n" +
                    "    <dependencies>\n" +
                    "        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-webflux</artifactId></dependency>\n" +
                    "        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-thymeleaf</artifactId></dependency>\n" +
                    "        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>\n" +
                    "        <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>runtime</scope></dependency>\n" +
                    "    </dependencies>\n" +
                    "    <build>\n" +
                    "        <plugins>\n" +
                    "            <plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin>\n" +
                    "        </plugins>\n" +
                    "    </build>\n" +
                    "</project>\n";

    private static final String FW_PROPS =
            "server.port=8040\n" +
                    "server.address=127.0.0.1\n" +
                    "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/astral?ssl=true&sslmode=verify-ca&sslcert=/etc/astral/certs/client-astral.crt&sslkey=/etc/astral/certs/client-astral.pk8&sslrootcert=/etc/astral/certs/root.crt\n" +
                    "spring.datasource.username=astral\n" +
                    "spring.datasource.driver-class-name=org.postgresql.Driver\n" +
                    "spring.jpa.hibernate.ddl-auto=update\n" +
                    "spring.thymeleaf.cache=false\n";

    private static final String FW_APP =
            "package com.astral.firewall;\n" +
                    "import org.springframework.boot.SpringApplication;\n" +
                    "import org.springframework.boot.autoconfigure.SpringBootApplication;\n" +
                    "import org.springframework.scheduling.annotation.EnableScheduling;\n" +
                    "import org.springframework.boot.context.event.ApplicationReadyEvent;\n" +
                    "import org.springframework.context.event.EventListener;\n" +
                    "import com.astral.firewall.service.IptablesService;\n" +
                    "@SpringBootApplication @EnableScheduling\n" +
                    "public class FirewallApplication {\n" +
                    "private final IptablesService ipt; public FirewallApplication(IptablesService i){ipt=i;}\n" +
                    "public static void main(String[] a){ SpringApplication.run(FirewallApplication.class,a); }\n" +
                    "@EventListener(ApplicationReadyEvent.class) public void init(){ ipt.syncFromRuntime(); }\n" +
                    "}\n";

    private static final String FW_RULE =
            "package com.astral.firewall.model;\n" +
                    "import jakarta.persistence.*; import java.time.Instant;\n" +
                    "@Entity public class FirewallRule {\n" +
                    "@Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;\n" +
                    "public String chain=\"INPUT\"; public int priority; public String protocol=\"TCP\";\n" +
                    "public String srcCidr=\"\"; public String dstCidr=\"\"; public String port=\"\";\n" +
                    "public String iface=\"\"; public String action=\"ACCEPT\"; public boolean enabled=true;\n" +
                    "@Column(length=512) public String comment=\"\";\n" +
                    "@Column(length=1024) public String rawRule=\"\";\n" +
                    "public Instant appliedAt;\n" +
                    "}\n";

    private static final String FW_PF =
            "package com.astral.firewall.model;\n" +
                    "import jakarta.persistence.*;\n" +
                    "@Entity public class PortForward {\n" +
                    "@Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;\n" +
                    "public String iface; public String externalPort; public String protocol=\"TCP\";\n" +
                    "public String internalIp; public String internalPort; public boolean enabled=true;\n" +
                    "@Column(length=512) public String description=\"\";\n" +
                    "}\n";

    private static final String FW_MQ =
            "package com.astral.firewall.model;\n" +
                    "import jakarta.persistence.*;\n" +
                    "@Entity public class MasqueradeRule { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id; public String iface; public boolean enabled=true; }\n";

    private static final String FW_ZONE =
            "package com.astral.firewall.model;\n" +
                    "import jakarta.persistence.*;\n" +
                    "@Entity public class Zone { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id; public String name; public String trustLevel=\"LAN\"; public String defaultPolicy=\"ACCEPT\"; public String color=\"#57e389\"; }\n";

    private static final String FW_ZI =
            "package com.astral.firewall.model;\n" +
                    "import jakarta.persistence.*;\n" +
                    "@Entity public class ZoneInterface { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id; public Long zoneId; public String ifaceName; }\n";

    private static final String FW_HG =
            "package com.astral.firewall.model;\n" +
                    "import jakarta.persistence.*;\n" +
                    "@Entity public class HostGroup { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id; public String name; @ElementCollection(fetch=FetchType.EAGER) public java.util.List<String> cidrs=new java.util.ArrayList<>(); }\n";

    private static final String FW_PG =
            "package com.astral.firewall.model;\n" +
                    "import jakarta.persistence.*;\n" +
                    "@Entity public class PortGroup { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id; public String name; @ElementCollection(fetch=FetchType.EAGER) public java.util.List<String> ports=new java.util.ArrayList<>(); }\n";

    private static final String FW_SC =
            "package com.astral.firewall.model;\n" +
                    "import jakarta.persistence.*;\n" +
                    "@Entity public class Schedule { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id; public String name; public String daysOfWeek=\"SEG,TER,QUA,QUI,SEX\"; public java.time.LocalTime startTime=java.time.LocalTime.of(8,0); public java.time.LocalTime endTime=java.time.LocalTime.of(18,0); }\n";

    private static final String FW_RL =
            "package com.astral.firewall.model;\n" +
                    "import jakarta.persistence.*;\n" +
                    "@Entity public class RateLimitPolicy { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id; public String port; public String protocol=\"TCP\"; public int ratePerSecond=20; public boolean enabled=true; }\n";

    private static final String FW_AB =
            "package com.astral.firewall.model;\n" +
                    "import jakarta.persistence.*;\n" +
                    "@Entity public class AutoBanRule { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id; public int maxAttempts=5; public int windowMinutes=5; public int banMinutes=30; public String targetPort=\"22\"; public boolean enabled=true; }\n";

    private static final String FW_TL =
            "package com.astral.firewall.model;\n" +
                    "import jakarta.persistence.*;\n" +
                    "@Entity public class ThreatList { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id; public String name; public String sourceUrl; public java.time.Instant lastUpdated; public int ipCount; public boolean enabled=true; }\n";

    private static final String FW_AL =
            "package com.astral.firewall.model;\n" +
                    "import jakarta.persistence.*;\n" +
                    "@Entity public class AuditLog { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id; public String username; public String entityType; public String entityId; public String action; @Column(length=8192) public String diffJson; public java.time.Instant timestamp=java.time.Instant.now(); }\n";

    private static final String FW_SNAP =
            "package com.astral.firewall.model;\n" +
                    "import jakarta.persistence.*; import java.time.Instant;\n" +
                    "@Entity public class FirewallSnapshot {\n" +
                    "@Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;\n" +
                    "public Instant createdAt=Instant.now(); public String label;\n" +
                    "@Lob @Column(columnDefinition=\"text\") public String dumpText;\n" +
                    "public FirewallSnapshot(){} public FirewallSnapshot(String l,String d){label=l;dumpText=d;}\n" +
                    "}\n";

    private static final String FW_STATE =
            "package com.astral.firewall.model;\n" +
                    "import jakarta.persistence.*;\n" +
                    "@Entity public class FirewallState { @Id public String key; public String value; }\n";

    private static final String FW_AUDIT =
            "package com.astral.firewall.service;\n" +
                    "import com.astral.firewall.model.AuditLog; import com.astral.firewall.repo.AuditLogRepo;\n" +
                    "import org.springframework.stereotype.Service;\n" +
                    "@Service\n" +
                    "public class AuditService {\n" +
                    "private final AuditLogRepo repo; public AuditService(AuditLogRepo r){repo=r;}\n" +
                    "public void log(String user,String type,String id,String action,String diff){\n" +
                    "AuditLog a=new AuditLog(); a.username=user==null?\"admin\":user; a.entityType=type; a.entityId=id; a.action=action; a.diffJson=diff; repo.save(a); }\n" +
                    "}\n";

    private static final String FW_IPT =
            "package com.astral.firewall.service;\n" +
                    "import com.astral.firewall.model.*; import com.astral.firewall.repo.*;\n" +
                    "import org.springframework.stereotype.Service;\n" +
                    "import org.springframework.transaction.annotation.Transactional;\n" +
                    "import java.nio.file.*; import java.time.Instant; import java.util.*;\n" +
                    "@Service\n" +
                    "public class IptablesService {\n" +
                    "private static final String NL=String.valueOf((char)10);\n" +
                    "private static final String Q=String.valueOf((char)34);\n" +
                    "private final FirewallRuleRepo rules; private final PortForwardRepo forwards; private final MasqueradeRuleRepo masq;\n" +
                    "private final RateLimitPolicyRepo rates; private final FirewallSnapshotRepo snaps; private final FirewallStateRepo state;\n" +
                    "private final AuditService audit;\n" +
                    "public IptablesService(FirewallRuleRepo r,PortForwardRepo f,MasqueradeRuleRepo m,RateLimitPolicyRepo rl,FirewallSnapshotRepo s,FirewallStateRepo st,AuditService a){rules=r;forwards=f;masq=m;rates=rl;snaps=s;state=st;audit=a;}\n" +
                    "public String run(List<String> cmd,String stdin){try{Process p=new ProcessBuilder(cmd).redirectErrorStream(true).start();\n" +
                    "if(stdin!=null){p.getOutputStream().write(stdin.getBytes());p.getOutputStream().close();}\n" +
                    "String out=new String(p.getInputStream().readAllBytes());p.waitFor();return out;}catch(Exception e){return \"ERR:\"+e.getMessage();}}\n" +
                    "public String sh(String c){return run(List.of(\"bash\",\"-c\",c),null);}\n" +
                    "public void persist(){sh(\"mkdir -p /etc/iptables && iptables-save > /etc/iptables/rules.v4 || iptables-save > /etc/sysconfig/iptables\");}\n" +
                    "@Transactional public synchronized void syncFromRuntime(){\n" +
                    "rules.deleteAll(); String dump=sh(\"iptables-save\"); int p=1;\n" +
                    "for(String l:dump.split(NL)){\n" +
                    "if(l.startsWith(\"-A INPUT\") || l.startsWith(\"-A FORWARD\") || l.startsWith(\"-A OUTPUT\")){\n" +
                    "if(l.contains(\"ASTRAL_BANNED\") || l.contains(\"hashlimit\")) continue;\n" +
                    "FirewallRule r=new FirewallRule(); r.rawRule=l.substring(3).trim();\n" +
                    "r.chain=r.rawRule.split(\" \")[0]; r.priority=p++;\n" +
                    "r.action=extract(l,\" -j ([A-Z_]+)\",1,\"UNKNOWN\");\n" +
                    "r.protocol=extract(l,\" -p ([a-z0-9]+)\",1,\"ALL\");\n" +
                    "r.port=extract(l,\" --dport ([0-9:]+)\",1,\"\");\n" +
                    "r.srcCidr=extract(l,\" -s ([0-9\\\\./]+)\",1,\"\");\n" +
                    "r.dstCidr=extract(l,\" -d ([0-9\\\\./]+)\",1,\"\");\n" +
                    "r.comment=extract(l,\" --comment \\\"([^\\\"]+)\\\"\",1,\"\");\n" +
                    "r.enabled=true; r.appliedAt=Instant.now();\n" +
                    "rules.save(r); } } }\n" +
                    "private String extract(String s,String p,int g,String d){java.util.regex.Matcher m=java.util.regex.Pattern.compile(p).matcher(s); return m.find()?m.group(g):d;}\n" +
                    "public void executeAndSync(String cmd){ sh(\"iptables \"+cmd); persist(); syncFromRuntime(); }\n" +
                    "private void guards(List<String> o){o.add(\"-A INPUT -i lo -j ACCEPT\");o.add(\"-A INPUT -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT\");\n" +
                    "o.add(\"-A INPUT -p tcp --dport 80 -j ACCEPT\");o.add(\"-A INPUT -s 127.0.0.1/32 -p tcp --dport 8040 -j ACCEPT\");o.add(\"-A INPUT -j ASTRAL_BANNED\");}\n" +
                    "private String args(FirewallRule r){ if(r.rawRule!=null&&!r.rawRule.isBlank()) return \"-A \"+r.chain+\" \"+r.rawRule.substring(r.chain.length()).trim();\n" +
                    "StringBuilder b=new StringBuilder(\"-A \"+r.chain);\n" +
                    "if(!r.iface.isBlank())b.append(\" -i \").append(r.iface);\n" +
                    "if(!r.protocol.equals(\"ALL\"))b.append(\" -p \").append(r.protocol.toLowerCase());\n" +
                    "if(!r.port.isBlank())b.append(\" --dport \").append(r.port);\n" +
                    "if(!r.srcCidr.isBlank())b.append(\" -s \").append(r.srcCidr);\n" +
                    "if(!r.dstCidr.isBlank())b.append(\" -d \").append(r.dstCidr);return b.toString();}\n" +
                    "public String buildFromDb(boolean panic){\n" +
                    "List<String> f=new ArrayList<>(),n=new ArrayList<>();\n" +
                    "f.add(\"*filter\");f.add(\":INPUT DROP [0:0]\");f.add(\":FORWARD DROP [0:0]\");f.add(\":OUTPUT ACCEPT [0:0]\");f.add(\":ASTRAL_BANNED - [0:0]\");guards(f);\n" +
                    "if(!panic){\n" +
                    "for(RateLimitPolicy rl:rates.findAll())if(rl.enabled){\n" +
                    "f.add(\"-A INPUT -p \"+rl.protocol.toLowerCase()+\" --dport \"+rl.port+\" -m hashlimit --hashlimit-mode srcip --hashlimit-rate \"+rl.ratePerSecond+\"/second --hashlimit-burst 5 -j ACCEPT\");\n" +
                    "f.add(\"-A INPUT -p \"+rl.protocol.toLowerCase()+\" --dport \"+rl.port+\" -m limit --limit 3/minute -j LOG --log-prefix \"+Q+\"ASTRAL-FW-DROP: \"+Q);\n" +
                    "f.add(\"-A INPUT -p \"+rl.protocol.toLowerCase()+\" --dport \"+rl.port+\" -j DROP\");}\n" +
                    "for(String ch:List.of(\"INPUT\",\"OUTPUT\",\"FORWARD\"))\n" +
                    "for(FirewallRule r:rules.findByChainOrderByPriority(ch))if(r.enabled){\n" +
                    "if(r.action.equals(\"DROP\")||r.action.equals(\"REJECT\"))f.add(args(r)+\" -m limit --limit 6/minute -j LOG --log-prefix \"+Q+\"ASTRAL-FW-\"+r.action+\": \"+Q);\n" +
                    "else if(r.action.equals(\"LOG\"))f.add(args(r)+\" -j LOG --log-prefix \"+Q+\"ASTRAL-FW: \"+Q);else f.add(args(r)+\" -j \"+r.action);}\n" +
                    "for(PortForward pf:forwards.findAll())if(pf.enabled)f.add(\"-A FORWARD -i \"+pf.iface+\" -p \"+pf.protocol.toLowerCase()+\" --dport \"+pf.externalPort+\" -j ACCEPT\");\n" +
                    "f.add(\"-A FORWARD -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT\");\n" +
                    "} else f.add(\"-A INPUT -p tcp --dport 22 -j ACCEPT\");\n" +
                    "f.add(\"COMMIT\");\n" +
                    "n.add(\"*nat\");n.add(\":PREROUTING ACCEPT [0:0]\");n.add(\":POSTROUTING ACCEPT [0:0]\");\n" +
                    "if(!panic)for(PortForward pf:forwards.findAll())if(pf.enabled)\n" +
                    "n.add(\"-A PREROUTING -i \"+pf.iface+\" -p \"+pf.protocol.toLowerCase()+\" --dport \"+pf.externalPort+\" -j DNAT --to-destination \"+pf.internalIp+\":\"+pf.internalPort);\n" +
                    "for(MasqueradeRule m:masq.findAll())if(m.enabled)n.add(\"-A POSTROUTING -o \"+m.iface+\" -j MASQUERADE\");\n" +
                    "n.add(\"COMMIT\");return String.join(NL,f)+NL+String.join(NL,n)+NL;}\n" +
                    "public boolean validate(String script){try{Path t=Files.createTempFile(\"fw\",\".rules\");Files.writeString(t,script);\n" +
                    "String out=run(List.of(\"bash\",\"-c\",\"iptables-restore --test \"+t.toAbsolutePath()),null);Files.deleteIfExists(t);return out.isBlank();}catch(Exception e){return false;}}\n" +
                    "public FirewallSnapshot saveSnapshot(String label){return snaps.save(new FirewallSnapshot(label,sh(\"iptables-save\")));}\n" +
                    "public void restoreDump(String dump){run(List.of(\"iptables-restore\"),dump);}\n" +
                    "public Map<String,Object> applyFromDb(String user){\n" +
                    "syncFromRuntime(); return Map.of(\"success\",true);\n" +
                    "}\n" +
                    "public Map<String,Object> panic(String user){\n" +
                    "saveSnapshot(\"pre-panic\");\n" +
                    "sh(\"iptables -P INPUT DROP; iptables -P FORWARD DROP; iptables -F INPUT; iptables -A INPUT -i lo -j ACCEPT; iptables -A INPUT -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT; iptables -A INPUT -p tcp --dport 22 -j ACCEPT; iptables -A INPUT -s 127.0.0.1/32 -p tcp --dport 8040 -j ACCEPT; iptables -A INPUT -p tcp --dport 5001 -j ACCEPT\");\n" +
                    "persist(); syncFromRuntime(); setState(\"panic\",\"ON\"); audit.log(user,\"FIREWALL\",\"*\",\"PANIC\",\"\");\n" +
                    "return Map.of(\"success\",true);\n" +
                    "}\n" +
                    "public Map<String,Object> revert(String user){Map<String,Object> res=new HashMap<>();\n" +
                    "Optional<FirewallSnapshot> s=snaps.findAllByOrderByCreatedAtDesc().stream().filter(x->x.label.startsWith(\"pre-\")).findFirst();\n" +
                    "if(s.isEmpty()){res.put(\"success\",false);res.put(\"error\",\"Sem snapshot no banco.\");return res;}\n" +
                    "restoreDump(s.get().dumpText);persist(); syncFromRuntime(); setState(\"panic\",\"OFF\"); audit.log(user,\"FIREWALL\",\"*\",\"REVERT\",\"snapshot=\"+s.get().id);res.put(\"success\",true);return res;}\n" +
                    "public boolean panicActive(){return state.findById(\"panic\").map(x->\"ON\".equals(x.value)).orElse(false);}\n" +
                    "private void setState(String k,String v){FirewallState st=state.findById(k).orElse(new FirewallState());st.key=k;st.value=v;state.save(st);}\n" +
                    "}\n";

    private static final String FW_STATS =
            "package com.astral.firewall.service;\n" +
                    "import org.springframework.stereotype.Service;\n" +
                    "import java.util.*;\n" +
                    "@Service\n" +
                    "public class StatsService {\n" +
                    "private static final String NL=String.valueOf((char)10);\n" +
                    "private final IptablesService ipt;\n" +
                    "public StatsService(IptablesService i){ipt=i;}\n" +
                    "public Map<String,Object> stats(){\n" +
                    "Map<String,Object> m=new HashMap<>();\n" +
                    "String log=ipt.sh(\"journalctl -k -o short-unix --since '24 hours ago' 2>/dev/null | grep 'ASTRAL-FW' || true\");\n" +
                    "int blocked=0,rejected=0,ssh=0; Map<String,int[]> top=new HashMap<>(); int[] hourB=new int[24]; int[] hourA=new int[24];\n" +
                    "long now=System.currentTimeMillis()/1000;\n" +
                    "for(String line:log.split(NL)){ if(line.isBlank())continue;\n" +
                    "try{ long ts=Long.parseLong(line.trim().split(\" \")[0]); int h=(int)((now-ts)/3600); if(h<0||h>23)continue;\n" +
                    "int si=line.indexOf(\"SRC=\"); String src=si>=0?line.substring(si+4).split(\" \")[0]:\"\";\n" +
                    "int di=line.indexOf(\"DPT=\"); String dpt=di>=0?line.substring(di+4).split(\" \")[0]:\"\";\n" +
                    "if(line.contains(\"ASTRAL-FW-DROP\")||line.contains(\"ASTRAL-FW-PANIC\")){blocked++;hourB[h]++;\n" +
                    "if(!src.isEmpty()){String key=src+\"|\"+dpt; top.computeIfAbsent(key,k->new int[]{0})[0]++; if(\"22\".equals(dpt))ssh++;}}\n" +
                    "else if(line.contains(\"ASTRAL-FW-REJECT\")){rejected++;}\n" +
                    "}catch(Exception ignored){} }\n" +
                    "long accepted=0; String l=ipt.sh(\"iptables -L INPUT -v -n 2>/dev/null\");\n" +
                    "for(String line:l.split(NL)) if(line.contains(\"ACCEPT\")){ String[] c=line.trim().split(\" \"); try{accepted+=Long.parseLong(c[0].replaceAll(\"[^0-9]\",\"\"));}catch(Exception ignored){} }\n" +
                    "for(int i=0;i<24;i++) hourA[i]=(int)(accepted/24);\n" +
                    "m.put(\"blocked24\",blocked); m.put(\"rejected24\",rejected); m.put(\"accepted24\",accepted); m.put(\"sshAttempts\",ssh);\n" +
                    "List<Map<String,Object>> topList=new ArrayList<>();\n" +
                    "top.entrySet().stream().sorted((a,b)->b.getValue()[0]-a.getValue()[0]).limit(6).forEach(e->{\n" +
                    "String[] p=e.getKey().split(\"\\\\|\"); Map<String,Object> row=new HashMap<>(); row.put(\"ip\",p[0]); row.put(\"port\",p.length>1?p[1]:\"\"); row.put(\"action\",\"DROP\"); row.put(\"count\",e.getValue()[0]); topList.add(row);});\n" +
                    "m.put(\"topBlocked\",topList);\n" +
                    "List<Map<String,Object>> series=new ArrayList<>(); for(int i=23;i>=0;i--){Map<String,Object> b=new HashMap<>();b.put(\"h\",i);b.put(\"blocked\",hourB[i]);b.put(\"allowed\",hourA[i]);series.add(b);}\n" +
                    "m.put(\"series\",series); return m;\n" +
                    "}\n" +
                    "}\n";

    private static final String FW_BK =
            "package com.astral.firewall.service;\n" +
                    "import org.springframework.beans.factory.annotation.Value; import org.springframework.stereotype.Service;\n" +
                    "import java.nio.file.*; import java.util.*;\n" +
                    "@Service\n" +
                    "public class BackupService {\n" +
                    "@Value(\"${spring.datasource.username}\") private String user;\n" +
                    "@Value(\"${spring.datasource.password:}\") private String pass;\n" +
                    "private static final String TABLES=\"-t 'firewall_%' -t 'port_forward' -t 'masquerade_rule' -t 'zone*' -t 'host_group*' -t 'port_group*' -t 'schedule' -t 'rate_limit_policy' -t 'auto_ban_rule' -t 'threat_list' -t 'audit_log'\";\n" +
                    "public String exportSql(){try{ProcessBuilder pb=new ProcessBuilder(\"bash\",\"-c\",\n" +
                    "\"PGSSLCERT=/etc/astral/certs/client-astral.crt PGSSLKEY=/etc/astral/certs/client-astral.pk8 PGSSLROOTCERT=/etc/astral/certs/root.crt PGSSLMODE=verify-ca pg_dump -h 127.0.0.1 -U \"+user+\" -d astral --clean --if-exists --no-owner \"+TABLES);\n" +
                    "Process p=pb.start();String out=new String(p.getInputStream().readAllBytes());p.waitFor();return out;}catch(Exception e){return \"-- ERRO: \"+e.getMessage();}}\n" +
                    "public boolean restoreSql(String sql){try{Path t=Files.createTempFile(\"fwrestore\",\".sql\");Files.writeString(t,sql);\n" +
                    "ProcessBuilder pb=new ProcessBuilder(\"bash\",\"-c\",\"PGSSLCERT=/etc/astral/certs/client-astral.crt PGSSLKEY=/etc/astral/certs/client-astral.pk8 PGSSLROOTCERT=/etc/astral/certs/root.crt PGSSLMODE=verify-ca psql -h 127.0.0.1 -U \"+user+\" -d astral --single-transaction -v ON_ERROR_STOP=1 -f \"+t.toAbsolutePath());\n" +
                    "Process p=pb.start();p.getInputStream().readAllBytes();int c=p.waitFor();Files.deleteIfExists(t);\n" +
                    "return c==0;}catch(Exception e){return false;}}\n" +
                    "}\n";

    private static final String FW_API =
            "package com.astral.firewall.api;\n" +
                    "import com.astral.firewall.model.*; import com.astral.firewall.repo.*; import com.astral.firewall.service.*;\n" +
                    "import org.springframework.http.MediaType; import org.springframework.web.bind.annotation.*;\n" +
                    "import reactor.core.publisher.Mono; import reactor.core.scheduler.Schedulers;\n" +
                    "import java.net.InetAddress; import java.util.*;\n" +
                    "@RestController @RequestMapping({\"/api\", \"/firewall/api\"})\n" +
                    "public class FirewallApiController {\n" +
                    "private static final String NL=String.valueOf((char)10);\n" +
                    "private final IptablesService ipt; private final StatsService stats; private final AuditService audit; private final BackupService backup;\n" +
                    "private final FirewallRuleRepo rules; private final PortForwardRepo forwards; private final MasqueradeRuleRepo masq;\n" +
                    "private final ZoneRepo zones; private final ZoneInterfaceRepo zifaces; private final HostGroupRepo hgroups; private final PortGroupRepo pgroups;\n" +
                    "private final ScheduleRepo schedules; private final RateLimitPolicyRepo rates; private final AutoBanRuleRepo bans; private final ThreatListRepo threats;\n" +
                    "private final AuditLogRepo audits; private final FirewallSnapshotRepo snaps;\n" +
                    "public FirewallApiController(IptablesService i,StatsService s,AuditService a,BackupService b,FirewallRuleRepo r,PortForwardRepo f,MasqueradeRuleRepo m,ZoneRepo z,ZoneInterfaceRepo zi,HostGroupRepo hg,PortGroupRepo pg,ScheduleRepo sc,RateLimitPolicyRepo rl,AutoBanRuleRepo ab,ThreatListRepo tl,AuditLogRepo al,FirewallSnapshotRepo sn){ipt=i;stats=s;audit=a;backup=b;rules=r;forwards=f;masq=m;zones=z;zifaces=zi;hgroups=hg;pgroups=pg;schedules=sc;rates=rl;bans=ab;threats=tl;audits=al;snaps=sn;}\n" +
                    "private <T> Mono<T> call(java.util.concurrent.Callable<T> c){return Mono.fromCallable(c).subscribeOn(Schedulers.boundedElastic());}\n" +
                    "@GetMapping(\"/status\") public Mono<Map<String,Object>> status(){return call(()->{Map<String,Object> m=new HashMap<>();m.put(\"active\",true);m.put(\"motor\",\"iptables\");m.put(\"panicActive\",ipt.panicActive());m.put(\"rulesActive\",rules.count());m.put(\"pending\",0);return m;});}\n" +
                    "@GetMapping(\"/stats\") public Mono<Map<String,Object>> stats(){return call(() -> stats.stats());}\n" +
                    "@PostMapping(\"/panic\") public Mono<Map<String,Object>> panic(){return call(() -> ipt.panic(\"admin\"));}\n" +
                    "@PostMapping(\"/panic/revert\") public Mono<Map<String,Object>> revert(){return call(() -> ipt.revert(\"admin\"));}\n" +
                    "@GetMapping(\"/rules\") public Mono<List<FirewallRule>> rules(){return call(() -> { ipt.syncFromRuntime(); return rules.findAll(); });}\n" +
                    "@PostMapping(\"/rules\") public Mono<?> saveRule(@RequestBody FirewallRule r){return call(()->{\n" +
                    "String cmd = \"-I \" + r.chain + \" 1 \";\n" +
                    "if(r.protocol!=null&&!r.protocol.equals(\"ALL\")) cmd += \"-p \" + r.protocol.toLowerCase() + \" \";\n" +
                    "if(r.port!=null&&!r.port.isBlank()) cmd += \"--dport \" + r.port + \" \";\n" +
                    "if(r.srcCidr!=null&&!r.srcCidr.isBlank()) cmd += \"-s \" + r.srcCidr + \" \";\n" +
                    "cmd += \"-j \" + (r.action!=null&&!r.action.isBlank()?r.action:\"ACCEPT\");\n" +
                    "ipt.executeAndSync(cmd); audit.log(\"admin\",\"RULE\",\"*\",\"SAVE\",cmd); return Map.of(\"success\",true);\n" +
                    "});}\n" +
                    "@DeleteMapping(\"/rules/{id}\") public Mono<Map<String,Object>> delRule(@PathVariable Long id){return call(()->{\n" +
                    "rules.findById(id).ifPresent(r->{ ipt.executeAndSync(\"-D \" + r.rawRule); });\n" +
                    "audit.log(\"admin\",\"RULE\",id.toString(),\"DELETE\",\"\"); return Map.of(\"success\",true);\n" +
                    "});}\n" +
                    "@PostMapping(\"/rules/apply\") public Mono<Map<String,Object>> apply(){return call(() -> { ipt.syncFromRuntime(); return Map.of(\"success\",true); });}\n" +
                    "@PostMapping(\"/rules/reorder\") public Mono<Map<String,Object>> reorder(@RequestBody Map<String,Object> body){return call(()->{ ipt.syncFromRuntime(); return Map.of(\"success\",true); });}\n" +
                    "@GetMapping(\"/simulate\") public Mono<Map<String,Object>> sim(@RequestParam String proto,@RequestParam String port,@RequestParam(defaultValue=\"0.0.0.0\") String src,@RequestParam(defaultValue=\"0.0.0.0\") String dst){return call(()->{for(FirewallRule r:rules.findByChainOrderByPriority(\"INPUT\"))if(r.enabled&&match(r,proto,port,src,dst))return Map.of(\"match\",true,\"rule\",r);return Map.<String,Object>of(\"match\",false,\"policy\",\"DROP\");});}\n" +
                    "private boolean match(FirewallRule r,String proto,String port,String src,String dst){if(!r.protocol.equals(\"ALL\")&&!r.protocol.equalsIgnoreCase(proto))return false;if(!r.port.isBlank()&&!r.port.equals(port))return false;if(!r.srcCidr.isBlank()&&!inCidr(src,r.srcCidr))return false;if(!r.dstCidr.isBlank()&&!inCidr(dst,r.dstCidr))return false;return true;}\n" +
                    "private boolean inCidr(String ip,String cidr){try{String[] c=cidr.split(\"/\");byte[] a=InetAddress.getByName(c[0]).getAddress();byte[] b=InetAddress.getByName(ip).getAddress();int bits=c.length>1?Integer.parseInt(c[1]):32,full=bits/8,rem=bits%8;for(int i=0;i<full;i++)if(a[i]!=b[i])return false;if(rem>0){int m=(0xFF00>>rem)&0xFF;if((a[full]&m)!=(b[full]&m))return false;}return true;}catch(Exception e){return false;}}\n" +
                    "@GetMapping(\"/forwards\") public Mono<List<PortForward>> fw(){return call(() -> forwards.findAll());}\n" +
                    "@PostMapping(\"/forwards\") public Mono<?> saveFw(@RequestBody PortForward f){return call(()->{boolean clash=forwards.findAll().stream().anyMatch(o->o.enabled&&o.iface.equals(f.iface)&&o.externalPort.equals(f.externalPort)&&o.protocol.equals(f.protocol)&&!o.id.equals(f.id));if(clash)return Map.of(\"success\",false,\"error\",\"Conflito de porta na mesma interface.\");return Map.of(\"saved\",forwards.save(f),\"sync\",ipt.applyFromDb(\"admin\"));});}\n" +
                    "@DeleteMapping(\"/forwards/{id}\") public Mono<Map<String,Object>> delFw(@PathVariable Long id){return call(()->{forwards.deleteById(id);return ipt.applyFromDb(\"admin\");});}\n" +
                    "@GetMapping(\"/masquerade\") public Mono<List<MasqueradeRule>> mq(){return call(() -> masq.findAll());}\n" +
                    "@PostMapping(\"/masquerade\") public Mono<?> saveMq(@RequestBody MasqueradeRule m){return call(()->Map.of(\"saved\",masq.save(m),\"sync\",ipt.applyFromDb(\"admin\")));}\n" +
                    "@DeleteMapping(\"/masquerade/{id}\") public Mono<Map<String,Object>> delMq(@PathVariable Long id){return call(()->{masq.deleteById(id);return ipt.applyFromDb(\"admin\");});}\n" +
                    "@GetMapping(\"/zones\") public Mono<List<Zone>> z(){return call(() -> zones.findAll());}\n" +
                    "@PostMapping(\"/zones\") public Mono<Zone> saveZ(@RequestBody Zone z){return call(() -> zones.save(z));}\n" +
                    "@DeleteMapping(\"/zones/{id}\") public Mono<Map<String,String>> delZ(@PathVariable Long id){return call(()->{zones.deleteById(id);zifaces.deleteByZoneId(id);return Map.of(\"success\",\"true\");});}\n" +
                    "@GetMapping(\"/hostgroups\") public Mono<List<HostGroup>> hg(){return call(() -> hgroups.findAll());}\n" +
                    "@PostMapping(\"/hostgroups\") public Mono<HostGroup> saveHg(@RequestBody HostGroup g){return call(() -> hgroups.save(g));}\n" +
                    "@DeleteMapping(\"/hostgroups/{id}\") public Mono<Map<String,String>> delHg(@PathVariable Long id){return call(()->{hgroups.deleteById(id);return Map.of(\"success\",\"true\");});}\n" +
                    "@GetMapping(\"/portgroups\") public Mono<List<PortGroup>> pg(){return call(() -> pgroups.findAll());}\n" +
                    "@PostMapping(\"/portgroups\") public Mono<PortGroup> savePg(@RequestBody PortGroup g){return call(() -> pgroups.save(g));}\n" +
                    "@DeleteMapping(\"/portgroups/{id}\") public Mono<Map<String,String>> delPg(@PathVariable Long id){return call(()->{pgroups.deleteById(id);return Map.of(\"success\",\"true\");});}\n" +
                    "@GetMapping(\"/schedules\") public Mono<List<Schedule>> sc(){return call(() -> schedules.findAll());}\n" +
                    "@PostMapping(\"/schedules\") public Mono<Schedule> saveSc(@RequestBody Schedule s){return call(() -> schedules.save(s));}\n" +
                    "@DeleteMapping(\"/schedules/{id}\") public Mono<Map<String,String>> delSc(@PathVariable Long id){return call(()->{schedules.deleteById(id);return Map.of(\"success\",\"true\");});}\n" +
                    "@GetMapping(\"/ratelimits\") public Mono<List<RateLimitPolicy>> rl(){return call(() -> rates.findAll());}\n" +
                    "@PostMapping(\"/ratelimits\") public Mono<?> saveRl(@RequestBody RateLimitPolicy r){return call(()->Map.of(\"saved\",rates.save(r),\"sync\",ipt.applyFromDb(\"admin\")));}\n" +
                    "@DeleteMapping(\"/ratelimits/{id}\") public Mono<Map<String,Object>> delRl(@PathVariable Long id){return call(()->{rates.deleteById(id);return ipt.applyFromDb(\"admin\");});}\n" +
                    "@GetMapping(\"/autoban\") public Mono<List<AutoBanRule>> ab(){return call(() -> bans.findAll());}\n" +
                    "@PostMapping(\"/autoban\") public Mono<AutoBanRule> saveAb(@RequestBody AutoBanRule b){return call(() -> bans.save(b));}\n" +
                    "@DeleteMapping(\"/autoban/{id}\") public Mono<Map<String,String>> delAb(@PathVariable Long id){return call(()->{bans.deleteById(id);return Map.of(\"success\",\"true\");});}\n" +
                    "@GetMapping(\"/threatlists\") public Mono<List<ThreatList>> tl(){return call(() -> threats.findAll());}\n" +
                    "@PostMapping(\"/threatlists\") public Mono<ThreatList> saveTl(@RequestBody ThreatList t){return call(() -> threats.save(t));}\n" +
                    "@PostMapping(\"/threatlists/{id}/refresh\") public Mono<Map<String,Object>> refreshTl(@PathVariable Long id){return call(()->{ThreatList t=threats.findById(id).orElseThrow();ipt.sh(\"ipset create astral-threats hash:net -!\");String raw=ipt.sh(\"curl -fsSL \"+t.sourceUrl);int[] n={0};for(String line:raw.split(NL)){String ip=line.trim().split(\" \")[0];if(isIpish(ip)){ipt.sh(\"ipset add astral-threats \"+ip+\" -!\");n[0]++;}}t.ipCount=n[0];t.lastUpdated=java.time.Instant.now();threats.save(t);return Map.of(\"success\",true,\"ipCount\",n[0]);});}\n" +
                    "private boolean isIpish(String s){if(s.isEmpty())return false;int dots=0;for(char c:s.toCharArray()){if(c=='.')dots++;else if(!Character.isDigit(c)&&c!='/'&&c!='-')return false;}return dots>=1;}\n" +
                    "@GetMapping(\"/logs\") public Mono<List<Map<String,String>>> logs(@RequestParam(required=false) String ip,@RequestParam(required=false) String action){return call(()->{List<Map<String,String>> out=new ArrayList<>();for(String line:ipt.sh(\"journalctl -k -o short-unix --since '24 hours ago' 2>/dev/null | grep 'ASTRAL-FW' | tail -200\").split(NL)){if(line.isBlank())continue;if(ip!=null&&!line.contains(\"SRC=\"+ip))continue;if(action!=null&&!line.contains(\"ASTRAL-FW-\"+action))continue;out.add(Map.of(\"raw\",line));}return out;});}\n" +
                    "@GetMapping(\"/logs/export\") public Mono<String> exportLogs(@RequestParam(defaultValue=\"json\") String format){return call(()->{String raw=ipt.sh(\"journalctl -k --since '24 hours ago' 2>/dev/null | grep 'ASTRAL-FW' || true\");if(format.equals(\"csv\")){StringBuilder b=new StringBuilder(\"line\\n\");for(String l:raw.split(\"\\n\"))b.append(l.replace(\",\",\";\")).append(\"\\n\");return b.toString();}return raw;});}\n" +
                    "@GetMapping(\"/snapshots\") public Mono<List<FirewallSnapshot>> snapList(){return call(()->snaps.findAllByOrderByCreatedAtDesc());}\n" +
                    "@PostMapping(\"/snapshots/revert/{id}\") public Mono<Map<String,Object>> snapRevert(@PathVariable Long id){return call(()->{Optional<FirewallSnapshot> s=snaps.findById(id);if(s.isEmpty())return Map.of(\"success\",false);ipt.restoreDump(s.get().dumpText);ipt.persist();ipt.syncFromRuntime();audit.log(\"admin\",\"SNAPSHOT\",id.toString(),\"REVERT\",\"\");return Map.of(\"success\",true);});}\n" +
                    "@GetMapping(value=\"/backup\",produces=MediaType.TEXT_PLAIN_VALUE) public Mono<String> backup(){return call(() -> backup.exportSql());}\n" +
                    "@PostMapping(value=\"/backup/restore\",consumes=MediaType.TEXT_PLAIN_VALUE) public Mono<Map<String,Object>> restore(@RequestBody String sql){return call(()->{boolean ok=backup.restoreSql(sql);if(ok){ipt.applyFromDb(\"admin\");ipt.syncFromRuntime();}audit.log(\"admin\",\"BACKUP\",\"*\",\"RESTORE\",\"ok=\"+ok);return Map.of(\"success\",ok);});}\n" +
                    "@GetMapping(\"/audit\") public Mono<List<AuditLog>> auditList(){return call(()->audits.findTop200ByOrderByTimestampDesc());}\n" +
                    "}\n";

    private static final String FW_WS =
            "package com.astral.firewall.ws;\n" +
                    "import org.springframework.stereotype.Component;\n" +
                    "import org.springframework.web.reactive.socket.*;\n" +
                    "import reactor.core.publisher.Flux; import reactor.core.publisher.Mono;\n" +
                    "import java.io.InputStream; import java.nio.charset.StandardCharsets;\n" +
                    "@Component\n" +
                    "public class FirewallLogsWebSocketHandler implements WebSocketHandler {\n" +
                    "@Override public Mono<Void> handle(WebSocketSession session){\n" +
                    "final Process proc; try{ proc=new ProcessBuilder(\"bash\",\"-c\",\"journalctl -k -f --output=cat 2>/dev/null | grep --line-buffered 'ASTRAL-FW'\").start(); }\n" +
                    "catch(Exception e){ return session.close(); }\n" +
                    "Flux<WebSocketMessage> out=Flux.create(sink->{ Thread t=new Thread(()->{ try(InputStream in=proc.getInputStream()){\n" +
                    "byte[] buf=new byte[4096]; int n; while((n=in.read(buf))!=-1) sink.next(session.textMessage(new String(buf,0,n,StandardCharsets.UTF_8))); }catch(Exception ignored){} sink.complete(); }); t.setDaemon(true); t.start(); });\n" +
                    "return session.send(out).then(Mono.<Void>never()).onErrorResume(e->Mono.empty()).doFinally(s->proc.destroyForcibly());\n" +
                    "}\n" +
                    "}\n";

    private static final String FW_WSC =
            "package com.astral.firewall.ws;\n" +
                    "import org.springframework.context.annotation.Bean; import org.springframework.context.annotation.Configuration;\n" +
                    "import org.springframework.web.reactive.HandlerMapping; import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;\n" +
                    "import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;\n" +
                    "import java.util.Map;\n" +
                    "@Configuration\n" +
                    "public class WsConfig {\n" +
                    "@Bean public HandlerMapping fwLogsMapping(FirewallLogsWebSocketHandler h){ SimpleUrlHandlerMapping m=new SimpleUrlHandlerMapping(); m.setUrlMap(Map.of(\"/ws/firewall-logs\",h)); m.setOrder(-1); return m; }\n" +
                    "@Bean public WebSocketHandlerAdapter wsAdapter(){ return new WebSocketHandlerAdapter(); }\n" +
                    "}\n";

    private static final String FW_PAGES =
            "package com.astral.firewall.web;\n" +
                    "import org.springframework.stereotype.Controller; import org.springframework.web.bind.annotation.GetMapping;\n" +
                    "@Controller public class PagesController { @GetMapping({\"/\", \"/firewall\", \"/firewall/\"}) public String page(){ return \"firewall\"; } }\n";

    private static final String FW_HTML =
            "<!DOCTYPE html>\n" +
                    "<html lang=\"pt-br\">\n" +
                    "<head>\n" +
                    "<meta charset=\"UTF-8\"><title>ASTRAL PLATFORM · FIREWALL</title>\n" +
                    "<style>\n" +
                    "@font-face{font-family:'Orbitron';src:url('/fonts/orbitron-bold.woff2') format('woff2');font-weight:700}\n" +
                    "*{box-sizing:border-box}html,body{height:100%;margin:0}\n" +
                    "body{background:#05070d url('/images/Fundo.png') no-repeat center/cover fixed;color:#fff;font-family:'Segoe UI',sans-serif}\n" +
                    "header{padding:28px 60px}header h1{margin:0;font-family:'Orbitron';font-weight:900;letter-spacing:.25em;font-size:26px;color:#eef5ff;text-shadow:0 0 8px #9fd8ff,0 0 24px #1668ff}\n" +
                    "header .sub{font-family:'Orbitron';letter-spacing:.4em;color:#ff5c5c;font-size:12px;margin-top:6px}\n" +
                    ".wrap{display:flex;gap:28px;padding:20px 60px;height:calc(100% - 130px)}\n" +
                    ".side{width:270px;border:1px solid #ff5c5c;border-radius:14px;background:rgba(10,4,6,.8);display:flex;flex-direction:column;padding:18px}\n" +
                    ".side .t{font-family:'Orbitron';letter-spacing:.25em;color:#ff8888;font-size:11px;margin-bottom:14px}\n" +
                    ".nav button{display:flex;gap:12px;align-items:center;width:100%;background:none;border:1px solid transparent;border-radius:10px;color:#ffd7d7;padding:11px 12px;font-size:14px;cursor:pointer;text-align:left}\n" +
                    ".nav button .n{font-family:'Orbitron';color:#ff5c5c;font-size:11px}\n" +
                    ".nav button.on{border-color:#ff5c5c;background:rgba(255,92,92,.12)}\n" +
                    ".panic{margin-top:auto;background:linear-gradient(180deg,#ff5c5c,#c22);border:1px solid #ff8888;border-radius:10px;padding:12px;color:#fff;cursor:pointer;font-family:'Orbitron';letter-spacing:.15em}\n" +
                    ".panic small{display:block;font-family:'Segoe UI';letter-spacing:0;opacity:.8}\n" +
                    ".main{flex:1;overflow:auto}\n" +
                    ".badge{float:right;border:1px solid #57e389;border-radius:20px;color:#b6ffd0;padding:6px 14px;font-size:12px}\n" +
                    "h2{font-family:'Orbitron';letter-spacing:.2em;font-size:18px}\n" +
                    ".cards{display:grid;grid-template-columns:repeat(4,1fr);gap:18px;margin:18px 0}\n" +
                    ".card{border:1px solid #ff5c5c;border-radius:12px;background:rgba(10,4,6,.75);padding:16px}\n" +
                    ".card .k{font-size:11px;letter-spacing:.15em;color:#ff9a9a}.card .v{font-family:'Orbitron';font-size:30px;margin:8px 0 4px}.card .s{font-size:11px;color:#888}\n" +
                    ".v.amber{color:#ffb347}.v.green{color:#57e389}.v.white{color:#fff}\n" +
                    ".panel{border:1px solid #ff5c5c;border-radius:12px;background:rgba(10,4,6,.75);padding:18px;margin-bottom:18px}\n" +
                    ".panel h3{font-family:'Orbitron';letter-spacing:.15em;font-size:13px;color:#ff9a9a;margin:0 0 14px}\n" +
                    ".bars{display:flex;gap:10px;align-items:flex-end;height:260px}\n" +
                    ".bars .col{flex:1;display:flex;gap:4px;align-items:flex-end;height:100%}\n" +
                    ".bars .a{background:linear-gradient(180deg,#57e389,#1d7a44);width:50%}.bars .b{background:linear-gradient(180deg,#ff5c5c,#8c1f1f);width:50%}\n" +
                    "table{width:100%;border-collapse:collapse;font-size:13px}\n" +
                    "th{color:#ff9a9a;text-align:left;letter-spacing:.1em;font-size:11px;border-bottom:1px solid #552222;padding:8px}\n" +
                    "td{border-bottom:1px solid #331414;padding:8px}\n" +
                    ".pill{border-radius:14px;padding:3px 12px;font-size:11px;font-family:'Orbitron'}\n" +
                    ".pill.drop{background:#5a1414;border:1px solid #ff5c5c;color:#ffb3b3}.pill.accept{background:#0d3a22;border:1px solid #57e389;color:#b6ffd0}\n" +
                    "button.act{background:#16324a;border:1px solid #3fa9ff;color:#cfe9ff;border-radius:6px;padding:5px 10px;cursor:pointer}\n" +
                    "input,select{background:#0b0f14;border:1px solid #553;color:#fff;border-radius:6px;padding:7px;margin:3px}\n" +
                    "#live{background:#000;color:#d7ffd7;font:12px/1.4 Consolas,monospace;height:300px;overflow:auto;padding:10px;white-space:pre-wrap}\n" +
                    "</style></head>\n" +
                    "<body>\n" +
                    "<header><h1>ASTRAL PLATFORM</h1><div class=\"sub\">FIREWALL</div></header>\n" +
                    "<div class=\"wrap\">\n" +
                    "<aside class=\"side\"><div class=\"t\">MÓDULOS · FIREWALL</div><div class=\"nav\" id=\"nav\"></div>\n" +
                    "<button class=\"panic\" onclick=\"panic()\">MODO PÂNICO<small>bloquear tudo, exceto admin</small></button></aside>\n" +
                    "<main class=\"main\"><span class=\"badge\">● MOTOR ATIVO · iptables</span><h2 id=\"secTitle\"></h2><div id=\"content\"></div></main>\n" +
                    "</div>\n" +
                    "<script th:inline=\"none\">\n" +
                    "const SECS=[['dashboard','Dashboard'],['rules','Regras'],['nat','Port Forwarding'],['zones','Zonas'],['groups','Grupos'],['protections','Proteções'],['logs','Logs'],['backup','Backup & Auditoria']];\n" +
                    "let cur='dashboard';nav();show('dashboard');\n" +
                    "function nav(){document.getElementById('nav').innerHTML=SECS.map((s,i)=>'<button class=\"'+(s[0]===cur?'on':'')+'\" data-s=\"'+s[0]+'\" onclick=\"show(this.dataset.s)\"><span class=\"n\">0'+(i+1)+'</span>'+s[1]+'</button>').join('')}\n" +
                    "async function api(p,o){const r=await fetch('/firewall/api'+p,Object.assign({headers:{'Content-Type':'application/json'}},o));return r.json()}\n" +
                    "function panic(){if(!confirm('Ativar MODO PÂNICO?'))return;api('/panic',{method:'POST'}).then(()=>show('dashboard'))}\n" +
                    "async function show(s){cur=s;if(window.liveTimer){clearInterval(window.liveTimer);window.liveTimer=null}nav();document.getElementById('secTitle').textContent=SECS.find(x=>x[0]===s)[1].toUpperCase();const c=document.getElementById('content');c.innerHTML='';\n" +
                    "if(s==='dashboard'){const[st,sm]=await Promise.all([api('/status'),api('/stats')]);\n" +
                    "c.innerHTML='<div class=\"cards\"><div class=\"card\"><div class=\"k\">BLOQUEADOS (24H)</div><div class=\"v amber\">'+sm.blocked24+'</div><div class=\"s\">+'+sm.rejected24+' rejeitados</div></div><div class=\"card\"><div class=\"k\">PERMITIDOS (24H)</div><div class=\"v green\">'+sm.accepted24+'</div><div class=\"s\">tráfego normal</div></div><div class=\"card\"><div class=\"k\">REGRAS ATIVAS</div><div class=\"v white\">'+st.rulesActive+'</div><div class=\"s\">'+st.pending+' pendentes</div></div><div class=\"card\"><div class=\"k\">TENTATIVAS SSH</div><div class=\"v amber\">'+sm.sshAttempts+'</div><div class=\"s\">bloqueadas</div></div></div><div class=\"panel\"><h3>TRÁFEGO — BLOQUEADO x PERMITIDO</h3><div class=\"bars\">'+sm.series.map(b=>'<div class=\"col\"><div class=\"a\" style=\"height:'+Math.min(b.allowed/10,100)+'%\"></div><div class=\"b\" style=\"height:'+Math.min(b.blocked*3,100)+'%\"></div></div>').join('')+'</div></div><div class=\"panel\"><h3>TOP IPs BLOQUEADOS</h3><table><tr><th>ORIGEM</th><th>PORTA</th><th>AÇÃO</th></tr>'+sm.topBlocked.map(t=>'<tr><td>'+t.ip+'</td><td>'+t.port+'</td><td><span class=\"pill drop\">'+t.action+'</span></td></tr>').join('')+'</table></div>'}\n" +
                    "if(s==='rules'){const rs=await api('/rules');c.innerHTML='<div class=\"panel\"><h3>REGRAS</h3><button class=\"act\" onclick=\"api(&#39;/rules/apply&#39;,{method:&#39;POST&#39;}).then(()=>show(&#39;rules&#39;))\">Aplicar</button> <button class=\"act\" onclick=\"newRule()\">+ Nova</button><table><tr><th>#</th><th>CHAIN</th><th>PROTO</th><th>PORTA</th><th>ORIGEM</th><th>AÇÃO</th><th>STATUS</th><th></th></tr>'+rs.sort((a,b)=>a.priority-b.priority).map(r=>'<tr><td>'+r.priority+'</td><td>'+r.chain+'</td><td>'+r.protocol+'</td><td>'+r.port+'</td><td>'+(r.srcCidr||'any')+'</td><td><span class=\"pill '+(r.action==='ACCEPT'?'accept':'drop')+'\">'+r.action+'</span></td><td>'+(r.appliedAt?'aplicada':'pendente')+'</td><td><button class=\"act\" onclick=\"api(&#39;/rules/'+r.id+'&#39;,{method:&#39;DELETE&#39;}).then(()=>show(&#39;rules&#39;))\">x</button></td></tr>').join('')+'</table></div>'}\n" +
                    "if(s==='nat'){const[fs,ms]=await Promise.all([api('/forwards'),api('/masquerade')]);c.innerHTML='<div class=\"panel\"><h3>PORT FORWARDING</h3><table><tr><th>IFACE</th><th>EXT</th><th>INTERNO</th><th></th></tr>'+fs.map(f=>'<tr><td>'+f.iface+'</td><td>'+f.externalPort+'</td><td>'+f.internalIp+':'+f.internalPort+'</td><td><button class=\"act\" onclick=\"api(&#39;/forwards/'+f.id+'&#39;,{method:&#39;DELETE&#39;}).then(()=>show(&#39;nat&#39;))\">x</button></td></tr>').join('')+'</table><input id=\"fi\" placeholder=\"iface\"><input id=\"fe\" placeholder=\"porta ext\"><input id=\"fip\" placeholder=\"ip int\"><input id=\"fpo\" placeholder=\"porta int\"><button class=\"act\" onclick=\"api(&#39;/forwards&#39;,{method:&#39;POST&#39;,body:JSON.stringify({iface:fi.value,externalPort:fe.value,protocol:&#39;TCP&#39;,internalIp:fip.value,internalPort:fpo.value,enabled:true})}).then(()=>show(&#39;nat&#39;))\">+ Forward</button></div><div class=\"panel\"><h3>MASQUERADE</h3>'+ms.map(m=>'<div>'+m.iface+' <button class=\"act\" onclick=\"api(&#39;/masquerade/'+m.id+'&#39;,{method:&#39;DELETE&#39;}).then(()=>show(&#39;nat&#39;))\">x</button></div>').join('')+'<input id=\"mi\" placeholder=\"iface\"><button class=\"act\" onclick=\"api(&#39;/masquerade&#39;,{method:&#39;POST&#39;,body:JSON.stringify({iface:mi.value,enabled:true})}).then(()=>show(&#39;nat&#39;))\">+ Masquerade</button></div>'}\n" +
                    "if(s==='zones'){const z=await api('/zones');c.innerHTML='<div class=\"panel\"><h3>ZONAS</h3>'+z.map(x=>'<div>'+x.name+' ('+x.trustLevel+'/'+x.defaultPolicy+')</div>').join('')+'<input id=\"zn\" placeholder=\"nome\"><select id=\"zt\"><option>WAN</option><option>LAN</option><option>DMZ</option></select><button class=\"act\" onclick=\"api(&#39;/zones&#39;,{method:&#39;POST&#39;,body:JSON.stringify({name:zn.value,trustLevel:zt.value,defaultPolicy:zt.value===&#39;LAN&#39;?&#39;ACCEPT&#39;:&#39;DROP&#39;})}).then(()=>show(&#39;zones&#39;))\">+ Zona</button></div>'}\n" +
                    "if(s==='groups'){const[h,p]=await Promise.all([api('/hostgroups'),api('/portgroups')]);c.innerHTML='<div class=\"panel\"><h3>GRUPOS DE HOSTS</h3>'+h.map(g=>'<div>'+g.name+': '+g.cidrs.join(', ')+'</div>').join('')+'<input id=\"hn\" placeholder=\"nome\"><input id=\"hc\" placeholder=\"cidrs separados por vírgula\"><button class=\"act\" onclick=\"api(&#39;/hostgroups&#39;,{method:&#39;POST&#39;,body:JSON.stringify({name:hn.value,cidrs:hc.value?hc.value.split(&#39;,&#39;):[]})}).then(()=>show(&#39;groups&#39;))\">+</button></div><div class=\"panel\"><h3>GRUPOS DE PORTAS</h3>'+p.map(g=>'<div>'+g.name+': '+g.ports.join(', ')+'</div>').join('')+'<input id=\"pn\" placeholder=\"nome\"><input id=\"pc\" placeholder=\"portas\"><button class=\"act\" onclick=\"api(&#39;/portgroups&#39;,{method:&#39;POST&#39;,body:JSON.stringify({name:pn.value,ports:pc.value?pc.value.split(&#39;,&#39;):[]})}).then(()=>show(&#39;groups&#39;))\">+</button></div>'}\n" +
                    "if(s==='protections'){const[r,a,t]=await Promise.all([api('/ratelimits'),api('/autoban'),api('/threatlists')]);c.innerHTML='<div class=\"panel\"><h3>RATE LIMIT</h3>'+r.map(x=>'<div>porta '+x.port+': '+x.ratePerSecond+'/s</div>').join('')+'<input id=\"rp\" placeholder=\"porta\"><input id=\"rr\" value=\"20\"><button class=\"act\" onclick=\"api(&#39;/ratelimits&#39;,{method:&#39;POST&#39;,body:JSON.stringify({port:rp.value,ratePerSecond:+rr.value,enabled:true})}).then(()=>show(&#39;protections&#39;))\">+</button></div><div class=\"panel\"><h3>AUTO-BAN</h3>'+a.map(x=>'<div>'+x.maxAttempts+' tentativas / '+x.windowMinutes+'min</div>').join('')+'<button class=\"act\" onclick=\"api(&#39;/autoban&#39;,{method:&#39;POST&#39;,body:JSON.stringify({maxAttempts:5,windowMinutes:5,banMinutes:30,targetPort:&#39;22&#39;,enabled:true})}).then(()=>show(&#39;protections&#39;))\">+ SSH</button></div><div class=\"panel\"><h3>BLOCKLISTS</h3>'+t.map(x=>'<div>'+x.name+' ('+x.ipCount+') <button class=\"act\" onclick=\"api(&#39;/threatlists/'+x.id+'/refresh&#39;,{method:&#39;POST&#39;}).then(()=>show(&#39;protections&#39;))\">atualizar</button></div>').join('')+'<input id=\"tn\" placeholder=\"nome\"><input id=\"tu\" placeholder=\"url\"><button class=\"act\" onclick=\"api(&#39;/threatlists&#39;,{method:&#39;POST&#39;,body:JSON.stringify({name:tn.value,sourceUrl:tu.value,enabled:true})}).then(()=>show(&#39;protections&#39;))\">+</button></div>'}\n" +
                    "if(s==='logs'){c.innerHTML='<div class=\"panel\"><h3>LOGS (tempo real)</h3><div id=\"live\"></div><button class=\"act\" onclick=\"location.href=&#39;/firewall/api/logs/export?format=csv&#39;\">Exportar CSV</button></div>';window.liveTimer=setInterval(function(){api('/logs').then(function(l){var el=document.getElementById('live');if(el){el.textContent=l.map(function(x){return x.raw}).join('\\n');el.scrollTop=el.scrollHeight}}).catch(function(){})},2000)}\n" +
                    "if(s==='backup'){c.innerHTML='<div class=\"panel\"><h3>BACKUP & RESTORE (BANCO)</h3><button class=\"act\" onclick=\"location.href=&#39;/firewall/api/backup&#39;\">Exportar dump SQL</button> <input type=\"file\" id=\"bkf\"><button class=\"act\" onclick=\"restoreBk()\">Restaurar</button><h3>SNAPSHOTS</h3><div id=\"snaps\"></div></div>';api('/snapshots').then(l=>snaps.innerHTML=l.map(x=>'<div>'+x.createdAt+' — '+x.label+' <button class=\"act\" onclick=\"api(&#39;/snapshots/revert/'+x.id+'&#39;,{method:&#39;POST&#39;}).then(()=>show(&#39;backup&#39;))\">reverter</button></div>').join(''))}\n" +
                    "}\n" +
                    "async function restoreBk(){const f=bkf.files[0];if(!f)return;const txt=await f.text();await api('/backup/restore',{method:'POST',headers:{'Content-Type':'text/plain'},body:txt});show('backup')}\n" +
                    "function newRule(){const r={chain:prompt('Chain','INPUT'),protocol:prompt('Protocolo','TCP'),port:prompt('Porta',''),srcCidr:prompt('Origem CIDR',''),action:prompt('Ação','ACCEPT'),enabled:true,comment:''};api('/rules',{method:'POST',body:JSON.stringify(r)}).then(()=>show('rules'))}\n" +
                    "</script></body></html>\n";
}