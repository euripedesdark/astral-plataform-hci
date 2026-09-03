package com.astral.tools;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
public class InstallerProxy {
    private static final int PORT = 5002;
    private static final AtomicInteger progress = new AtomicInteger(0);
    private static String status = "Aguardando...";
    private static volatile boolean done = false;
    private static final String ASTRAL_GROUP = "astral";
    public static void main(String[] args) throws Exception {
        if (!isRoot()) { System.err.println("ERRO: use sudo"); System.exit(1); }
        freePortIfHeldByOldInstance(PORT);
        HttpServer s = HttpServer.create(new InetSocketAddress(PORT), 0);
        s.createContext("/", e -> send(e, 200, "<meta http-equiv='refresh' content='0; url=/install.html'>", "text/html"));
        s.createContext("/install.html", InstallerProxy::ui);
        s.createContext("/api/stream", InstallerProxy::stream);
        s.setExecutor(Executors.newCachedThreadPool());
        s.start();
        System.out.println("=".repeat(60));
        System.out.println("[ASTRAL PROXY] Instalador em http://" + getLocalIP() + ":" + PORT);
        System.out.println("=".repeat(60));
        Thread t = new Thread(InstallerProxy::run);
        t.setDaemon(true);
        t.start();
        Thread.currentThread().join();
    }

    private static void run() {
        try {
            up(5, "Detectando distribuição...");
            up(8, "Liberando portas no iptables...");
            run("iptables -C INPUT -p tcp --dport " + PORT + " -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p tcp --dport " + PORT + " -j ACCEPT", false);
            run("iptables -C INPUT -p tcp --dport 8085 -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p tcp --dport 8085 -j ACCEPT", false);
            run("iptables -C INPUT -p tcp --dport 8081 -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p tcp --dport 8081 -j ACCEPT", false);
            up(12, "Verificando Apache Traffic Server...");
            String ats = run("which traffic_ctl 2>/dev/null || ls /opt/trafficserver/bin/traffic_ctl 2>/dev/null", false);
            if (ats == null || ats.isBlank()) System.err.println("[AVISO] traffic_ctl não encontrado. Rode install-base.sh ou: dnf install -y epel-release && dnf install -y trafficserver");
            else System.out.println("[OK] Traffic Server presente: " + ats.trim());
            up(20, "Validando conexão com banco via mTLS...");
            if (!validateMTLSConnection()) { up(100, "ERRO: Falha ao conectar no banco via mTLS. Execute o instalador principal primeiro."); done = true; return; }
            up(30, "Criando bancos e tabelas (astral + astral_logs + autenticacao)...");
            createTables();
            up(45, "Limpando build anterior do módulo...");
            cleanModule();
            up(50, "Escrevendo projeto do módulo...");
            writeProject();
            fixOwnership();
            up(70, "Compilando módulo (mvn package)...");
            String owner;
            try { owner = Files.getOwner(Paths.get(app())).getName(); } catch (IOException e) { owner = "root"; }
            String mvnOut = run("cd " + app() + "/fabric/proxy && runuser -u " + owner + " -- mvn -B -DskipTests clean package", true);
            fixOwnership();
            Path jarPath = Paths.get(app(), "fabric", "proxy", "target", "astral-proxy-1.0.0.jar");
            if (!Files.exists(jarPath)) {
                up(100, "ERRO CRÍTICO: mvn não gerou o JAR.");
                if (mvnOut != null) { String[] lines = mvnOut.split("\n");
                    for (int i = Math.max(0, lines.length - 30); i < lines.length; i++) System.err.println("  " + lines[i]); }
                done = true; return;
            }
            System.out.println("[OK] JAR gerado: " + jarPath);
            up(80, "Deploy em /opt/astral-proxy + systemd service...");
            deploy(jarPath);
            up(90, "Aguardando 127.0.0.1:8085...");
            boolean ok = false;
            for (int i = 0; i < 30; i++) {
                try (var sk = new java.net.Socket()) { sk.connect(new InetSocketAddress("127.0.0.1", 8085), 500); ok = true; break; }
                catch (IOException e) { sleep(1000); }
            }
            up(100, ok ? "Módulo Proxy instalado! Acesse http://" + getLocalIP() + "/proxy" : "AVISO: 8085 não respondeu. journalctl -u astral-proxy");
            done = true;
        } catch (Exception e) { e.printStackTrace(); up(100, "ERRO: " + e.getMessage()); done = true; }
    }

    private static boolean validateMTLSConnection() {
        try {
            Path propsPath = Paths.get("/etc/astral/application.properties");
            if (!Files.exists(propsPath)) { System.err.println("[ERRO] /etc/astral/application.properties não encontrado."); return false; }
            String props = Files.readString(propsPath);
            if (!props.contains("spring.datasource.username=astral")) { System.err.println("[ERRO] properties sem usuário astral."); return false; }
            String cmd = "PGSSLCERT=/etc/astral/certs/client-astral.crt PGSSLKEY=/etc/astral/certs/client-astral.pk8 PGSSLROOTCERT=/etc/astral/certs/root.crt PGSSLMODE=verify-ca psql -h 127.0.0.1 -U astral -d astral -t -c 'SELECT 1'";
            String result = run(cmd, false);
            boolean success = result != null && result.trim().contains("1");
            if (success) System.out.println("[OK] mTLS validado."); else System.err.println("[ERRO] mTLS falhou: " + result);
            return success;
        } catch (Exception e) { System.err.println("[ERRO] " + e.getMessage()); return false; }
    }

    private static String app() { return System.getProperty("user.dir"); }
    private static void fixOwnership() {
        try {
            String owner = Files.getOwner(Paths.get(app())).getName();
            Path fw = Paths.get(app(), "fabric", "proxy");
            if (!Files.exists(fw)) return;
            run("chown -R " + owner + ":" + ASTRAL_GROUP + " " + fw + " 2>/dev/null || true", false);
            run("find " + fw + " -type d -exec chmod 2775 {} \\; 2>/dev/null || true", false);
            run("find " + fw + " -type f -exec chmod 0664 {} \\; 2>/dev/null || true", false);
            Path target = fw.resolve("target");
            if (!Files.exists(target)) Files.createDirectories(target);
            run("chown -R " + owner + ":" + ASTRAL_GROUP + " " + target + " 2>/dev/null || true", false);
        } catch (IOException ignored) {}
    }

    private static void createTables() throws IOException {
        Path f = Paths.get("/tmp/astral-proxy-tables.sql");
        Files.writeString(f, TABLES_SQL);
        run("chmod 0644 " + f, false);
        run("runuser -u postgres -- psql -d astral -f " + f, true);
        Path f2 = Paths.get("/tmp/astral-proxy-logs.sql");
        Files.writeString(f2, LOGS_SQL);
        run("chmod 0644 " + f2, false);
        run("runuser -u postgres -- psql -d astral_logs -f " + f2, true);
    }

    private static void deploy(Path jarPath) {
        run("mkdir -p /opt/astral-proxy", true);
        run("cp " + jarPath.toAbsolutePath() + " /opt/astral-proxy/", true);
        run("chown -R root:" + ASTRAL_GROUP + " /opt/astral-proxy", true);
        run("chmod 2770 /opt/astral-proxy", true);
        run("chmod 0660 /opt/astral-proxy/*.jar 2>/dev/null || true", false);
        run("mkdir -p /etc/astral/certs/proxy", false);
        try {
            Files.createDirectories(Paths.get("/etc/astral"));
            Path props = Paths.get("/etc/astral/proxy.properties");
            Files.writeString(props,
                    "server.port=8085\nserver.address=127.0.0.1\n"
                            + "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/astral?ssl=true&sslmode=verify-ca&sslcert=/etc/astral/certs/client-astral.crt&sslkey=/etc/astral/certs/client-astral.pk8&sslrootcert=/etc/astral/certs/root.crt\n"
                            + "spring.datasource.username=astral\n"
                            + "spring.datasource.driver-class-name=org.postgresql.Driver\n"
                            + "astral.logs.db=astral_logs\n"
                            + "astral.elasticsearch.url=http://127.0.0.1:9200\n"
                            + "spring.thymeleaf.cache=false\n");
            run("chown root:" + ASTRAL_GROUP + " " + props, false);
            run("chmod 0640 " + props, false);
        } catch (IOException ignored) {}
        String javaBin = run("readlink -f $(which java)", false);
        if (javaBin != null) javaBin = javaBin.trim(); else javaBin = "/usr/bin/java";
        try {
            Files.writeString(Paths.get("/etc/systemd/system/astral-proxy.service"),
                    "[Unit]\nDescription=Astral Proxy Module (Apache Traffic Server)\nAfter=network.target postgresql.service astral-platform.service\n\n"
                            + "[Service]\nType=simple\nUser=root\nGroup=" + ASTRAL_GROUP + "\n"
                            + "WorkingDirectory=/opt/astral-proxy\n"
                            + "ExecStart=" + javaBin + " -jar /opt/astral-proxy/astral-proxy-1.0.0.jar --spring.config.location=file:/etc/astral/proxy.properties\n"
                            + "Restart=always\nRestartSec=10\nStandardOutput=journal\nStandardError=journal\nUMask=0007\n\n"
                            + "[Install]\nWantedBy=multi-user.target\n");
        } catch (IOException ignored) {}
        run("systemctl daemon-reload && systemctl enable astral-proxy && systemctl restart astral-proxy", false);
    }

    private static void ui(HttpExchange ex) throws IOException {
        send(ex, 200, """
<!DOCTYPE html><html><head><meta charset="UTF-8"><style>
body{background:#05070d;color:#fff;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0}
.box{width:480px;border:2px solid #ffb347;border-radius:14px;padding:26px;text-align:center;background:rgba(10,6,2,.85)}
h1{color:#ffcc80}.bar{height:18px;background:#111;border-radius:9px;overflow:hidden;margin:16px 0}
.fill{height:100%;width:0;background:linear-gradient(90deg,#ffb347,#e65100);transition:width .4s}
#status{color:#aaa;font-size:14px}
</style></head><body><div class="box">
<h1>🟠 ASTRAL PROXY — INSTALADOR</h1>
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
        } catch (IOException ignored) {} catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private static void send(HttpExchange ex, int c, String b, String t) throws IOException {
        byte[] x = b.getBytes();
        ex.getResponseHeaders().set("Content-Type", t);
        ex.sendResponseHeaders(c, x.length);
        ex.getResponseBody().write(x);
        ex.close();
    }

    private static void up(int p, String s) { progress.set(p); status = s; System.out.println("[" + p + "%] " + s); }
    private static String run(String c, boolean log) {
        if (log) System.out.println("$ " + c);
        try {
            Process p = new ProcessBuilder("bash", "-c", c).redirectErrorStream(true).start();
            String o = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            return o;
        } catch (Exception e) { return ""; }
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
        } catch (Exception e) { return "127.0.0.1"; }
    }

    private static void freePortIfHeldByOldInstance(int port) {
        String pids = run("ss -ltnp 2>/dev/null | grep ':" + port + " ' | grep -oP 'pid=\\K[0-9]+' | sort -u || fuser " + port + "/tcp 2>/dev/null", false);
        if (pids == null) return;
        long myPid = ProcessHandle.current().pid();
        for (String pidStr : pids.trim().split("\\s+")) {
            if (pidStr.isBlank()) continue;
            try { long pid = Long.parseLong(pidStr.trim()); if (pid == myPid) continue; run("kill -9 " + pid, true); }
            catch (NumberFormatException ignored) {}
        }
        sleep(500);
    }

    private static boolean isRoot() { return System.getProperty("user.name").equals("root"); }
    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (Exception ignored) {} }
    private static void cleanModule() throws IOException {
        Path fw = Paths.get(app(), "fabric", "proxy");
        if (Files.exists(fw)) { run("rm -rf " + fw, true); System.out.println("[OK] Diretório antigo removido: " + fw); }
        try {
            String owner = Files.getOwner(Paths.get(app())).getName();
            run("runuser -u " + owner + " -- rm -rf /home/" + owner + "/.m2/repository/com/astral/astral-proxy", false);
        } catch (IOException ignored) {}
    }

    private static void writeProject() throws IOException {
        String base = app() + "/fabric/proxy";
        String jbase = base + "/src/main/java/com/astral/proxy";
        for (String d : new String[]{jbase + "/service", jbase + "/api", jbase + "/web", base + "/src/main/resources/templates"})
            Files.createDirectories(Paths.get(d));
        write(base + "/pom.xml", PX_POM);
        write(base + "/src/main/resources/application.properties", PX_PROPS);
        write(base + "/src/main/resources/templates/proxy.html", PX_HTML);
        write(base + "/src/main/resources/templates/auditoria.html", PX_AUDIT);
        write(jbase + "/ProxyApplication.java", PX_APP);
        write(jbase + "/service/Store.java", PX_STORE);
        write(jbase + "/service/AuthService.java", PX_AUTH);
        write(jbase + "/service/AtsService.java", PX_ATS);
        write(jbase + "/service/LogService.java", PX_LOG);
        write(jbase + "/api/ProxyApiController.java", PX_API);
        write(jbase + "/web/PagesController.java", PX_PAGES);
    }

    private static void write(String p, String c) throws IOException {
        Files.writeString(Paths.get(p), c);
        System.out.println("[OK] " + Paths.get(p).getFileName());
    }

    private static final String TABLES_SQL =
            "CREATE TABLE IF NOT EXISTS proxy_users(id BIGSERIAL PRIMARY KEY, username VARCHAR(80) UNIQUE, pass_hash VARCHAR(128), grupo VARCHAR(80), tipo VARCHAR(10));\n" +
                    "CREATE TABLE IF NOT EXISTS auth_sources(id BIGSERIAL PRIMARY KEY, tipo VARCHAR(10), dominio VARCHAR(120), ldap_url VARCHAR(200), usar_cert BOOLEAN DEFAULT FALSE, enabled BOOLEAN DEFAULT TRUE);\n" +
                    "CREATE TABLE IF NOT EXISTS certs(id BIGSERIAL PRIMARY KEY, nome VARCHAR(120), tipo VARCHAR(10), conteudo TEXT, truststore BOOLEAN DEFAULT FALSE, added_at TIMESTAMP DEFAULT now());\n" +
                    "CREATE TABLE IF NOT EXISTS autenticacao(id BIGSERIAL PRIMARY KEY, tipo VARCHAR(20), usuario VARCHAR(120), grupo VARCHAR(120), UNIQUE(tipo,usuario,grupo));\n" +
                    "CREATE TABLE IF NOT EXISTS politicas_acessos(id BIGSERIAL PRIMARY KEY, perigosos TEXT, confiaveis TEXT, fonte VARCHAR(120), updated_at TIMESTAMP DEFAULT now());\n" +
                    "CREATE TABLE IF NOT EXISTS liberados(id BIGSERIAL PRIMARY KEY, url TEXT);\n" +
                    "CREATE TABLE IF NOT EXISTS perigosos(id BIGSERIAL PRIMARY KEY, url TEXT);\n" +
                    "CREATE TABLE IF NOT EXISTS categorias(id BIGSERIAL PRIMARY KEY, nome VARCHAR(80), descricao VARCHAR(200), formato VARCHAR(20));\n" +
                    "CREATE TABLE IF NOT EXISTS politica(id BIGSERIAL PRIMARY KEY, nome VARCHAR(80), inicio DATE DEFAULT CURRENT_DATE, fim DATE, dias INT, indeterminada BOOLEAN DEFAULT TRUE);\n" +
                    "CREATE TABLE IF NOT EXISTS enderecos(id BIGSERIAL PRIMARY KEY, politica_id BIGINT, url TEXT, categoria VARCHAR(80));\n" +
                    "CREATE TABLE IF NOT EXISTS regras_sites(id BIGSERIAL PRIMARY KEY, politica_id BIGINT, endereco TEXT, usuario VARCHAR(80), grupo VARCHAR(80), categoria_site VARCHAR(80), acao BOOLEAN);\n";
    p
    rivate static final String LOGS_SQL =
            "CREATE TABLE IF NOT EXISTS acessos(id BIGSERIAL PRIMARY KEY, usuario VARCHAR(80), hostname VARCHAR(120), ip VARCHAR(50), data_ddmmyyyy VARCHAR(8), grupo VARCHAR(80), tipo_usuario INT, tipo_label VARCHAR(10), destino TEXT, created_at TIMESTAMP DEFAULT now());\n" +
                    "CREATE TABLE IF NOT EXISTS arquivos(id BIGSERIAL PRIMARY KEY, usuario VARCHAR(80), grupo VARCHAR(80), direcao VARCHAR(10), tamanho BIGINT, endereco TEXT, data_ddmmyyyy VARCHAR(8), created_at TIMESTAMP DEFAULT now());\n";

    private static final String PX_POM = """
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
    <artifactId>astral-proxy</artifactId>
    <version>1.0.0</version>
    <properties><java.version>21</java.version></properties>
    <dependencies>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-webflux</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-thymeleaf</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
        <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>compile</scope></dependency>
    </dependencies>
    <build><plugins><plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build>
</project>
""";

    private static final String PX_PROPS = """
server.port=8085
server.address=127.0.0.1
spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/astral?ssl=true&sslmode=verify-ca&sslcert=/etc/astral/certs/client-astral.crt&sslkey=/etc/astral/certs/client-astral.pk8&sslrootcert=/etc/astral/certs/root.crt
spring.datasource.username=astral
spring.datasource.driver-class-name=org.postgresql.Driver
astral.logs.db=astral_logs
astral.elasticsearch.url=http://127.0.0.1:9200
spring.thymeleaf.cache=false
""";

    private static final String PX_APP = """
package com.astral.proxy;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import com.astral.proxy.service.AtsService;
@SpringBootApplication @EnableScheduling
public class ProxyApplication {
private final AtsService ats; public ProxyApplication(AtsService a){ats=a;}
public static void main(String[] a){ SpringApplication.run(ProxyApplication.class,a); }
@EventListener(ApplicationReadyEvent.class) public void init(){ ats.writeConfigs(); ats.reload(); }
}
""";

    private static final String PX_STORE = """
package com.astral.proxy.service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.stereotype.Service;
@Service
public class Store {
public final JdbcTemplate db;
public final JdbcTemplate logs;
public Store(JdbcTemplate db){
this.db=db;
SimpleDriverDataSource ds=new SimpleDriverDataSource();
ds.setDriverClass(org.postgresql.Driver.class);
ds.setUrl("jdbc:postgresql://127.0.0.1:5432/astral_logs?ssl=true&sslmode=verify-ca&sslcert=/etc/astral/certs/client-astral.crt&sslkey=/etc/astral/certs/client-astral.pk8&sslrootcert=/etc/astral/certs/root.crt");
ds.setUsername("astral");
this.logs=new JdbcTemplate(ds);
}
}
""";

    private static final String PX_AUTH = """
package com.astral.proxy.service;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
@Service
public class AuthService {
private final Store st;
public AuthService(Store s){st=s;}
public Map<String,Object> authenticate(String user,String pass,String mode,String certName){
Map<String,Object> r=new HashMap<>();
boolean ok=false; int tipo=1; String label="GRP"; String grupo="";
switch(mode==null?"":mode){
case "BD": ok=bd(user,pass); tipo=2; label="BD"; break;
case "LINUX": ok=linux(user,pass); tipo=1; label="GRP"; break;
case "CERT": ok=certOk(certName)&&(bd(user,pass)||linux(user,pass)); tipo=2; label="BD"; break;
case "AD": String dom=adDomain(); ok=dom!=null&&ad(user,pass,dom); tipo=3; label="AD"; grupo=dom; break;
default: ok=bd(user,pass)||linux(user,pass)||(adDomain()!=null&&ad(user,pass,adDomain()));
}
if(ok&&grupo.isEmpty()){ try{grupo=st.db.queryForObject("select grupo from autenticacao where usuario=? limit 1",String.class,user);}catch(Exception e){} }
if(ok&&grupo==null)grupo="users";
r.put("success",ok); r.put("tipoUsuario",tipo); r.put("tipoLabel",label); r.put("grupo",grupo==null?"":grupo);
return r;
}
private boolean bd(String u,String p){
try{ Integer c=st.db.queryForObject("select count(*) from proxy_users where username=? and pass_hash=?",Integer.class,u,sha(p)); return c!=null&&c>0; }catch(Exception e){return false;}
}
private boolean linux(String u,String p){
try{
String line=null;
for(String l:Files.readAllLines(Paths.get("/etc/shadow"))){ if(l.startsWith(u+":")){line=l;break;} }
if(line==null)return false;
String hash=line.split(":")[1];
if(!hash.startsWith("$6$"))return false;
String salt=hash.split("\\\\$")[3];
Process pr=new ProcessBuilder("bash","-c","openssl passwd -6 -salt "+salt+" '"+p.replace("'","'\\\\''")+"'").start();
String gen=new String(pr.getInputStream().readAllBytes()).trim(); pr.waitFor();
return gen.equals(hash);
}catch(Exception e){return false;}
}
private boolean certOk(String nome){
try{ Integer c=st.db.queryForObject("select count(*) from certs where nome=? and truststore=true",Integer.class,nome); return c!=null&&c>0; }catch(Exception e){return false;}
}
private String adDomain(){
try{ return st.db.queryForObject("select dominio from auth_sources where tipo='AD' and enabled=true limit 1",String.class); }catch(Exception e){return null;}
}
private boolean ad(String u,String p,String domain){
try{
Hashtable<String,String> env=new Hashtable<>();
env.put("java.naming.factory.initial","com.sun.jndi.ldap.LdapCtxFactory");
env.put("java.naming.provider.url","ldap://"+domain+":389");
env.put("java.naming.security.authentication","simple");
env.put("java.naming.security.principal",u+"@"+domain);
env.put("java.naming.security.credentials",p);
javax.naming.directory.DirContext ctx=new javax.naming.directory.InitialDirContext(env);
ctx.close(); return true;
}catch(Exception e){return false;}
}
public static String sha(String s){try{java.security.MessageDigest m=java.security.MessageDigest.getInstance("SHA-256");byte[] b=m.digest(s.getBytes());StringBuilder sb=new StringBuilder();for(byte x:b)sb.append(String.format("%02x",x));return sb.toString();}catch(Exception e){return "";}}
// ============ SINCRONIZAÇÃO DE CATÁLOGO ============
public int sync(String tipo,String dominio,String adminUser,String adminPass,boolean usarCert){
st.db.update("delete from autenticacao where tipo=?",tipo);
if("LINUX".equals(tipo))return syncLinux();
if("POSTGRES".equals(tipo))return syncPostgres(dominio,adminUser,adminPass);
if("AD".equals(tipo))return syncAD(dominio,adminUser,adminPass,usarCert);
return 0;
}
private int syncLinux(){
int n=0;
try{
Map<String,String> gid2name=new HashMap<>(); Map<String,List<String>> members=new HashMap<>();
for(String l:Files.readAllLines(Paths.get("/etc/group"))){
String[] p=l.split(":"); if(p.length<4)continue;
gid2name.put(p[2],p[0]);
List<String> ms=new ArrayList<>(); for(String m:p[3].split(",")) if(!m.isBlank())ms.add(m.trim());
members.put(p[0],ms);
}
for(String l:Files.readAllLines(Paths.get("/etc/passwd"))){
String[] p=l.split(":"); if(p.length<7)continue;
int uid; try{uid=Integer.parseInt(p[2]);}catch(Exception e){continue;}
if(uid<1000)continue;
if(p[6].contains("nologin")||p[6].contains("false"))continue;
String u=p[0]; boolean added=false;
for(Map.Entry<String,List<String>> e:members.entrySet()){
if(e.getValue().contains(u)){ st.db.update("insert into autenticacao(tipo,usuario,grupo) values('LINUX',?,?) on conflict do nothing",u,e.getKey()); n++; added=true; }
}
if(!added){ st.db.update("insert into autenticacao(tipo,usuario,grupo) values('LINUX',?,?) on conflict do nothing",u,gid2name.getOrDefault(p[3],"users")); n++; }
}
}catch(Exception e){ System.err.println("[SYNC LINUX] "+e); }
return n;
}
private int syncPostgres(String host,String adminUser,String adminPass){
int n=0;
String h=(host==null||host.isBlank())?"127.0.0.1:5432":host;
try(Connection c=DriverManager.getConnection("jdbc:postgresql://"+h+"/astral",adminUser,adminPass)){
try(var rs=c.createStatement().executeQuery("select u.rolname as usr, g.rolname as grp from pg_roles u left join pg_auth_members am on am.member=u.oid left join pg_roles g on g.oid=am.roleid where u.rolcanlogin order by u.rolname")){
Set<String> seen=new HashSet<>();
while(rs.next()){
String u=rs.getString("usr"); String g=rs.getString("grp");
String key=u+"|"+g; if(seen.contains(key))continue; seen.add(key);
st.db.update("insert into autenticacao(tipo,usuario,grupo) values('POSTGRES',?,?) on conflict do nothing",u,g==null?"pg_users":g); n++;
}
}
}catch(Exception e){ System.err.println("[SYNC PG] "+e); }
return n;
}
private int syncAD(String domain,String adminUser,String adminPass,boolean usarCert){
int n=0;
try{
if(usarCert){System.setProperty("javax.net.ssl.trustStore","/etc/astral/certs/proxy-truststore.jks");System.setProperty("javax.net.ssl.trustStorePassword","astral123");}
Hashtable<String,String> env=new Hashtable<>();
env.put("java.naming.factory.initial","com.sun.jndi.ldap.LdapCtxFactory");
env.put("java.naming.provider.url",(usarCert?"ldaps://":"ldap://")+domain+(usarCert?":636":":389"));
env.put("java.naming.security.authentication","simple");
env.put("java.naming.security.principal",adminUser.contains("@")?adminUser:adminUser+"@"+domain);
env.put("java.naming.security.credentials",adminPass);
env.put("com.sun.jndi.ldap.connect.timeout","5000");
javax.naming.directory.DirContext ctx=new javax.naming.directory.InitialDirContext(env);
String base="";
try{ javax.naming.directory.Attributes root=ctx.getAttributes("",new String[]{"namingContexts"});
javax.naming.NamingEnumeration<?> ne=(javax.naming.NamingEnumeration<?>)root.get("namingContexts").getAll();
if(ne.hasMore())base=String.valueOf(ne.next()); }catch(Exception e){}
javax.naming.directory.SearchControls sc=new javax.naming.directory.SearchControls();
sc.setSearchScope(javax.naming.directory.SearchControls.SUBTREE_SCOPE);
Map<String,String> dn2cn=new HashMap<>();
for(var en=ctx.search(base,"(objectClass=group)",sc);en.hasMore();){
var r=en.next(); var at=r.getAttributes().get("cn");
dn2cn.put(r.getNameInNamespace(),at!=null?String.valueOf(at.get()):"");
}
for(var en=ctx.search(base,"(&(objectClass=user)(sAMAccountName=*))",sc);en.hasMore();){
var r=en.next(); var au=r.getAttributes().get("sAMAccountName");
String u=au!=null?String.valueOf(au.get()):"";
if(u.isEmpty()||u.endsWith("$"))continue;
var mo=r.getAttributes().get("memberOf");
if(mo!=null&&mo.size()>0){
for(int i=0;i<mo.size();i++){ String dn=String.valueOf(mo.get(i));
st.db.update("insert into autenticacao(tipo,usuario,grupo) values('AD',?,?) on conflict do nothing",u,dn2cn.getOrDefault(dn,cnOf(dn))); n++; }
}else{ st.db.update("insert into autenticacao(tipo,usuario,grupo) values('AD',?,?) on conflict do nothing",u,"domain_users"); n++; }
}
ctx.close();
}catch(Exception e){ System.err.println("[SYNC AD] "+e); }
return n;
}
private String cnOf(String dn){for(String part:dn.split(",")){if(part.trim().toLowerCase().startsWith("cn="))return part.trim().substring(3);}return dn;}
public List<Map<String,Object>> catalog(String tipo,String grupo){
if(tipo!=null&&!tipo.isBlank()&&grupo!=null&&!grupo.isBlank())return st.db.queryForList("select tipo,usuario,grupo from autenticacao where tipo ilike ? and grupo ilike ? order by usuario",tipo,grupo);
if(grupo!=null&&!grupo.isBlank())return st.db.queryForList("select tipo,usuario,grupo from autenticacao where grupo ilike ? order by usuario",grupo);
if(tipo!=null&&!tipo.isBlank())return st.db.queryForList("select tipo,usuario,grupo from autenticacao where tipo ilike ? order by grupo,usuario",tipo);
return st.db.queryForList("select tipo,usuario,grupo from autenticacao order by tipo,grupo,usuario");
}
public List<String> tipos(){return st.db.queryForList("select distinct tipo from autenticacao order by tipo",String.class);}
public List<String> grupos(String tipo){if(tipo!=null&&!tipo.isBlank())return st.db.queryForList("select distinct grupo from autenticacao where tipo ilike ? order by grupo",String.class,tipo);return st.db.queryForList("select distinct grupo from autenticacao order by grupo",String.class);}
public List<String> usuarios(String grupo){if(grupo!=null&&!grupo.isBlank())return st.db.queryForList("select distinct usuario from autenticacao where grupo ilike ? order by usuario",String.class,grupo);return st.db.queryForList("select distinct usuario from autenticacao order by usuario",String.class);}
}
""";

    private static final String PX_ATS = """
package com.astral.proxy.service;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import java.nio.file.*;
import java.util.*;
@Service
public class AtsService {
private final Store st;
@Value("${astral.ats.dir:auto}") private String atsDirConfig;
public AtsService(Store s){st=s;}
public String dir(){
if(!"auto".equals(atsDirConfig)) return atsDirConfig;
if(Files.exists(Paths.get("/etc/trafficserver")))return "/etc/trafficserver";
if(Files.exists(Paths.get("/opt/trafficserver/etc/trafficserver")))return "/opt/trafficserver/etc/trafficserver";
return "/etc/trafficserver";
}
private List<String> list(String tab){
try{return st.db.queryForList("select url from "+tab,String.class);}
catch(Exception e){return List.of();}
}
public void writeConfigs(){
try{
String d=dir();
Files.createDirectories(Paths.get(d));
List<String> deny=list("perigosos");
List<String> allow=list("liberados");
List<String> doms=st.db.queryForList("select dominio from auth_sources where tipo='AD' and enabled=true",String.class);
List<String> acl=new ArrayList<>();
acl.add("# ACL Plugin - politicas tem precedencia");
for(String u:deny) acl.add("deny url regex .*" + u.replace(".","\\\\.") + ".*");
for(String u:allow) acl.add("allow url regex .*" + u.replace(".","\\\\.") + ".*");
for(String dm:doms) acl.add("allow url regex .*" + dm.replace(".","\\\\.") + ".*");
acl.add("deny all");
Files.write(Paths.get(d,"acl.config"), acl);
List<String> reg=new ArrayList<>();
reg.add("# Regex Remap Plugin - categorias de sites");
for(Map<String,Object> c:st.db.queryForList("select nome from categorias")){
String n=String.valueOf(c.get("nome"));
reg.add("regex://" + n + "/ /category/" + n + "/");
}
Files.write(Paths.get(d,"regex_remap.config"), reg);
List<String> hr=new ArrayList<>();
hr.add("# Header Rewrite Plugin - regras por usuario/grupo");
for(Map<String,Object> r:st.db.queryForList("select * from regras_sites")){
String cat=String.valueOf(r.get("categoria_site"));
String act=Boolean.TRUE.equals(r.get("acao"))?"allow":"block";
hr.add("cond %{READ_URL}");
hr.add("  set-header Host " + cat);
hr.add("  set-header X-ASTRAL-ACTION " + act);
}
Files.write(Paths.get(d,"header_rewrite.rules"), hr);
List<String> lua=new ArrayList<>();
lua.add("-- Lua Plugin: decisao em tempo real");
lua.add("function do_remap(rh)");
lua.add("  local host=rh.get_url()");
lua.add("  return 1");
lua.add("end");
Files.write(Paths.get(d,"astral_decision.lua"), lua);
List<String> fb=new ArrayList<>();
fb.add("# Filter Body Plugin - padroes maliciosos");
fb.add("<script src=");
for(String u:deny) fb.add(u);
Files.write(Paths.get(d,"filter_body_patterns.txt"), fb);
List<String> remap=new ArrayList<>();
remap.add("map / http://127.0.0.1:8081/");
Files.write(Paths.get(d,"remap.config"), remap);
List<String> plug=new ArrayList<>();
plug.add("acl.so acl.config");
plug.add("regex_remap.so regex_remap.config");
plug.add("header_rewrite.so header_rewrite.rules");
plug.add("lua.so astral_decision.lua");
plug.add("filter_body.so filter_body_patterns.txt");
Files.write(Paths.get(d,"plugin.config"), plug);
System.out.println("[OK] Configs ATS geradas em " + d);
}catch(Exception e){
System.err.println("[AVISO] ATS configs: " + e.getMessage());
}
}
public void reload(){
run("traffic_ctl config reload 2>/dev/null || /opt/trafficserver/bin/traffic_ctl config reload 2>/dev/null || systemctl restart trafficserver 2>/dev/null || true");
}
private String run(String c){
try{
Process p=new ProcessBuilder("bash","-c",c).redirectErrorStream(true).start();
String o=new String(p.getInputStream().readAllBytes());
p.waitFor();
return o;
}catch(Exception e){return "";}
}
}
""";

    private static final String PX_LOG = """
package com.astral.proxy.service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
@Service
public class LogService {
private final Store st;
private long offset=0;
public LogService(Store s){st=s;}
private String blog(){
if(Files.exists(Paths.get("/opt/trafficserver/var/log/trafficserver/squid.blog")))return "/opt/trafficserver/var/log/trafficserver/squid.blog";
return "/var/log/trafficserver/squid.blog";
}
@Scheduled(fixedDelay=15000)
public void tailSquid(){
try{
Path p=Paths.get(blog()); if(!Files.exists(p))return;
long len=Files.size(p); if(len<offset)offset=0;
byte[] all=Files.readAllBytes(p);
if(all.length<=offset)return;
String chunk=new String(all,(int)offset,all.length-(int)offset); offset=all.length;
DateTimeFormatter f=DateTimeFormatter.ofPattern("ddMMyyyy");
for(String line:chunk.split("\\\\n")){
String[] t=line.trim().split("\\\\s+");
if(t.length<7)continue;
String ip=t[2]; String url=t[6]; String user=t.length>8?t[7]:"-";
String d=LocalDate.now().format(f);
st.logs.update("insert into acessos(usuario,hostname,ip,data_ddmmyyyy,grupo,tipo_usuario,tipo_label,destino) values(?,?,?,?,?,?,?,?)",
user,ip,ip,d,grupoDe(user),tipoDe(user),labelDe(user),url);
}
}catch(Exception ignored){}
}
private int tipoDe(String u){try{Integer c=st.db.queryForObject("select count(*) from autenticacao where usuario=? and tipo='POSTGRES'",Integer.class,u);if(c!=null&&c>0)return 2;}catch(Exception e){}return 1;}
private String labelDe(String u){return tipoDe(u)==2?"BD":"GRP";}
private String grupoDe(String u){try{String g=st.db.queryForObject("select grupo from autenticacao where usuario=? limit 1",String.class,u);return g!=null?g:"users";}catch(Exception e){return "users";}}
@Scheduled(cron="0 0 * * * *")
public void migrate(){
try{
Instant cut=Instant.now().minus(Duration.ofDays(6)).minus(Duration.ofHours(23)).minus(Duration.ofMinutes(59)).minus(Duration.ofSeconds(59));
List<Map<String,Object>> rows=st.logs.queryForList("select * from acessos where created_at<=?",java.sql.Timestamp.from(cut));
for(Map<String,Object> r:rows){
String json="{\\\\"usuario\\\\":\\\\""+r.get("usuario")+"\\\\",\\\\"ip\\\\":\\\\""+r.get("ip")+"\\\\",\\\\"destino\\\\":\\\\""+r.get("destino")+"\\\\",\\\\"data\\\\":\\\\""+r.get("data_ddmmyyyy")+"\\\\",\\\\"grupo\\\\":\\\\""+r.get("grupo")+"\\\\",\\\\"tipo\\\\":\\\\""+r.get("tipo_label")+"\\\\"}";
String out=run("curl -fsSL -X POST http://127.0.0.1:9200/astral_logs/_doc -H 'Content-Type: application/json' -d '"+json.replace("'","'\\\\''")+"' 2>/dev/null");
if(out!=null&&!out.isBlank())st.logs.update("delete from acessos where id=?",r.get("id"));
}
st.logs.update("delete from acessos where created_at < now() - interval '7 days'");
st.logs.update("delete from arquivos where created_at < now() - interval '7 days'");
}catch(Exception ignored){}
}
private String run(String c){try{Process p=new ProcessBuilder("bash","-c",c).redirectErrorStream(true).start();String o=new String(p.getInputStream().readAllBytes());p.waitFor();return o;}catch(Exception e){return "";}}
}
""";

    private static final String PX_API = """
package com.astral.proxy.api;
import com.astral.proxy.service.*;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.*;
@RestController @RequestMapping({"/api","/proxy/api"})
public class ProxyApiController {
private final Store st; private final AuthService auth; private final AtsService ats;
public ProxyApiController(Store s,AuthService a,AtsService t){st=s;auth=a;ats=t;}
private <T> Mono<T> call(java.util.concurrent.Callable<T> c){return Mono.fromCallable(c).subscribeOn(Schedulers.boundedElastic());}
@GetMapping("/status") public Mono<Map<String,Object>> status(){return call(()->{Map<String,Object> m=new HashMap<>();m.put("active",true);m.put("motor","trafficserver");m.put("perigosos",st.db.queryForObject("select count(*) from perigosos",Integer.class));m.put("liberados",st.db.queryForObject("select count(*) from liberados",Integer.class));m.put("regras",st.db.queryForObject("select count(*) from regras_sites",Integer.class));return m;});}
@PostMapping("/auth/sources") public Mono<Map<String,Object>> addSource(@RequestBody Map<String,Object> b){return call(()->{
String tipo=String.valueOf(b.get("tipo")); String dom=String.valueOf(b.getOrDefault("dominio",""));
String au=String.valueOf(b.getOrDefault("admin_user","")); String ap=String.valueOf(b.getOrDefault("admin_pass",""));
boolean uc=Boolean.TRUE.equals(b.get("usar_cert"));
st.db.update("insert into auth_sources(tipo,dominio,admin_user,usar_cert) values(?,?,?,?)",tipo,dom,au,uc);
int synced=auth.sync(tipo,dom,au,ap,uc);
ats.writeConfigs();ats.reload();
return Map.of("success",true,"synced",synced);});}
@GetMapping("/auth/sources") public Mono<List<Map<String,Object>>> sources(){return call(()->st.db.queryForList("select id,tipo,dominio,admin_user,usar_cert from auth_sources order by id"));}
@DeleteMapping("/auth/sources/{id}") public Mono<Map<String,Object>> delSource(@PathVariable Long id){return call(()->{st.db.update("delete from auth_sources where id=?",id);return Map.of("success",true);});}
@GetMapping("/auth/catalog") public Mono<List<Map<String,Object>>> catalog(@RequestParam(required=false) String tipo,@RequestParam(required=false) String grupo){return call(()->auth.catalog(tipo,grupo));}
@GetMapping("/auth/tipos") public Mono<List<String>> tipos(){return call(()->auth.tipos());}
@GetMapping("/auth/grupos") public Mono<List<String>> grupos(@RequestParam(required=false) String tipo){return call(()->auth.grupos(tipo));}
@GetMapping("/auth/usuarios") public Mono<List<String>> usuarios(@RequestParam(required=false) String grupo){return call(()->auth.usuarios(grupo));}
@PostMapping("/auth/users") public Mono<Map<String,Object>> addUser(@RequestBody Map<String,String> b){return call(()->{st.db.update("insert into proxy_users(username,pass_hash,grupo,tipo) values(?,?,?,?) on conflict(username) do update set pass_hash=excluded.pass_hash",b.get("username"),AuthService.sha(b.get("password")),b.getOrDefault("grupo","users"),"BD");return Map.of("success",true);});}
@GetMapping("/auth/users") public Mono<List<Map<String,Object>>> users(){return call(()->st.db.queryForList("select id,username,grupo,tipo from proxy_users order by id"));}
@DeleteMapping("/auth/users/{id}") public Mono<Map<String,Object>> delUser(@PathVariable Long id){return call(()->{st.db.update("delete from proxy_users where id=?",id);return Map.of("success",true);});}
@PostMapping("/auth/test") public Mono<Map<String,Object>> test(@RequestBody Map<String,String> b){return call(()->auth.authenticate(b.get("username"),b.get("password"),b.get("mode"),b.get("cert")));}
@PostMapping("/certs") public Mono<Map<String,Object>> addCert(@RequestBody Map<String,String> b){return call(()->{
String nome=b.get("nome"); String conteudo=b.get("conteudo");
java.nio.file.Path f=java.nio.file.Paths.get("/etc/astral/certs/proxy/"+nome);
java.nio.file.Files.writeString(f,conteudo);
String ks="/etc/astral/certs/proxy-truststore.jks";
run("keytool -importcert -noprompt -trustcacerts -alias "+nome+" -file "+f+" -keystore "+ks+" -storepass astral123 2>/dev/null || true");
st.db.update("insert into certs(nome,tipo,conteudo,truststore) values(?,?,?,true)",nome,b.getOrDefault("tipo","crt"),conteudo);
return Map.of("success",true);});}
@GetMapping("/certs") public Mono<List<Map<String,Object>>> certs(){return call(()->st.db.queryForList("select id,nome,tipo,truststore,added_at from certs order by id"));}
@DeleteMapping("/certs/{id}") public Mono<Map<String,Object>> delCert(@PathVariable Long id){return call(()->{st.db.update("delete from certs where id=?",id);return Map.of("success",true);});}
@PostMapping("/policies/lists") public Mono<Map<String,Object>> saveLists(@RequestBody Map<String,String> b){return call(()->{
st.db.update("delete from liberados"); st.db.update("delete from perigosos");
for(String u:b.getOrDefault("seguros","").split("\\\\n"))if(!u.isBlank())st.db.update("insert into liberados(url) values(?)",u.trim());
for(String u:b.getOrDefault("inseguros","").split("\\\\n"))if(!u.isBlank())st.db.update("insert into perigosos(url) values(?)",u.trim());
st.db.update("insert into politicas_acessos(perigosos,confiaveis,fonte) values(?,?,?)",b.getOrDefault("inseguros",""),b.getOrDefault("seguros",""),"manual");
ats.writeConfigs(); ats.reload();
return Map.of("success",true);});}
@PostMapping("/policies/import") public Mono<Map<String,Object>> importList(@RequestBody Map<String,String> b){return call(()->{
String raw=b.get("content");
if(raw==null||raw.isBlank()){ raw=run("curl -fsSL '"+b.get("url")+"'"); }
StringBuilder ins=new StringBuilder();
for(String l:raw.split("\\\\n")){l=l.trim();if(l.isBlank()||l.startsWith("#"))continue;ins.append(l).append("\\\\n");}
st.db.update("insert into politicas_acessos(perigosos,confiaveis,fonte) values(?,?,?)",ins.toString(),"",String.valueOf(b.getOrDefault("url","upload")));
ats.writeConfigs(); ats.reload();
return Map.of("success",true,"lines",ins.length());});}
@GetMapping("/policies") public Mono<Map<String,Object>> policies(){return call(()->{Map<String,Object> m=new HashMap<>();m.put("liberados",st.db.queryForList("select * from liberados order by id"));m.put("perigosos",st.db.queryForList("select * from perigosos order by id"));m.put("historico",st.db.queryForList("select * from politicas_acessos order by id desc limit 20"));return m;});}
@PostMapping("/categories") public Mono<Map<String,Object>> addCat(@RequestBody Map<String,String> b){return call(()->{
st.db.update("insert into categorias(nome,descricao,formato) values(?,?,?)",b.get("nome"),b.getOrDefault("descricao",""),b.getOrDefault("formato","txt"));
String conteudo=b.getOrDefault("conteudo","");
for(String l:conteudo.split("\\\\n")){l=l.trim();if(!l.isBlank())st.db.update("insert into enderecos(politica_id,url,categoria) values(0,?,?)",l,b.get("nome"));}
ats.writeConfigs(); ats.reload();
return Map.of("success",true);});}
@GetMapping("/categories") public Mono<List<Map<String,Object>>> cats(){return call(()->st.db.queryForList("select * from categorias order by id"));}
@DeleteMapping("/categories/{id}") public Mono<Map<String,Object>> delCat(@PathVariable Long id){return call(()->{st.db.update("delete from categorias where id=?",id);return Map.of("success",true);});}
@PostMapping("/control/politica") public Mono<Map<String,Object>> addPol(@RequestBody Map<String,Object> b){return call(()->{
boolean ind=Boolean.TRUE.equals(b.get("indeterminada"));
Integer dias=b.get("dias")==null?0:Integer.parseInt(String.valueOf(b.get("dias")));
st.db.update("insert into politica(nome,dias,indeterminada,fim) values(?,?,?,?)",String.valueOf(b.get("nome")),dias,ind,ind?null:java.sql.Date.valueOf(java.time.LocalDate.now().plusDays(dias)));
return Map.of("success",true);});}
@GetMapping("/control/politica") public Mono<List<Map<String,Object>>> pols(){return call(()->st.db.queryForList("select * from politica order by id desc"));}
@PostMapping("/control/endereco") public Mono<Map<String,Object>> addEnd(@RequestBody Map<String,Object> b){return call(()->{st.db.update("insert into enderecos(politica_id,url,categoria) values(?,?,?)",Long.parseLong(String.valueOf(b.getOrDefault("politica_id",0))),String.valueOf(b.get("url")),String.valueOf(b.get("categoria")));return Map.of("success",true);});}
@PostMapping("/control/regra") public Mono<Map<String,Object>> addRegra(@RequestBody Map<String,Object> b){return call(()->{st.db.update("insert into regras_sites(politica_id,endereco,usuario,grupo,categoria_site,acao) values(?,?,?,?,?,?)",Long.parseLong(String.valueOf(b.getOrDefault("politica_id",0))),String.valueOf(b.getOrDefault("endereco","")),String.valueOf(b.getOrDefault("usuario","")),String.valueOf(b.getOrDefault("grupo","")),String.valueOf(b.getOrDefault("categoria_site","")),Boolean.TRUE.equals(b.get("acao")));ats.writeConfigs();ats.reload();return Map.of("success",true);});}
@GetMapping("/control/regras") public Mono<List<Map<String,Object>>> regras(){return call(()->st.db.queryForList("select * from regras_sites order by id desc"));}
@DeleteMapping("/control/regra/{id}") public Mono<Map<String,Object>> delRegra(@PathVariable Long id){return call(()->{st.db.update("delete from regras_sites where id=?",id);ats.writeConfigs();ats.reload();return Map.of("success",true);});}
@GetMapping("/decision") public Mono<Map<String,Object>> decision(@RequestParam String url,@RequestParam(defaultValue="") String user){return call(()->{
Map<String,Object> m=new HashMap<>();
try{if(st.db.queryForObject("select count(*) from perigosos where position(url in ?)>0",Integer.class,url)>0){m.put("action","block");m.put("reason","politica");return m;}}catch(Exception e){}
try{if(st.db.queryForObject("select count(*) from liberados where position(url in ?)>0",Integer.class,url)>0){m.put("action","allow");m.put("reason","politica");return m;}}catch(Exception e){}
try{Boolean a=st.db.queryForObject("select acao from regras_sites where (usuario=? or usuario='') and position(endereco in ?)>0 order by id desc limit 1",Boolean.class,user,url);if(a!=null){m.put("action",a?"allow":"block");m.put("reason","regra");return m;}}catch(Exception e){}
m.put("action","allow"); m.put("reason","default"); return m;});}
@GetMapping("/logs/sites") public Mono<List<Map<String,Object>>> sites(@RequestParam(defaultValue="10") int limit,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="") String q){return call(()->{
if(q.isBlank())return st.logs.queryForList("select * from acessos order by id desc limit ? offset ?",limit,page*limit);
return st.logs.queryForList("select * from acessos where usuario ilike ? or destino ilike ? order by id desc limit ? offset ?","%"+q+"%","%"+q+"%",limit,page*limit);});}
@GetMapping("/logs/files") public Mono<List<Map<String,Object>>> files(@RequestParam(defaultValue="10") int limit,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="") String q){return call(()->{
if(q.isBlank())return st.logs.queryForList("select * from arquivos order by id desc limit ? offset ?",limit,page*limit);
return st.logs.queryForList("select * from arquivos where usuario ilike ? or endereco ilike ? order by id desc limit ? offset ?","%"+q+"%","%"+q+"%",limit,page*limit);});}
@GetMapping("/logs/auditoria") public Mono<Map<String,Object>> auditoria(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="") String q){return call(()->{
Map<String,Object> m=new HashMap<>();
m.put("sites",q.isBlank()?st.logs.queryForList("select * from acessos order by id desc limit 10 offset ?",page*10):st.logs.queryForList("select * from acessos where usuario ilike ? or destino ilike ? order by id desc limit 10 offset ?","%"+q+"%","%"+q+"%",page*10));
m.put("files",st.logs.queryForList("select * from arquivos order by id desc limit 10 offset ?",page*10));
m.put("total",st.logs.queryForObject("select count(*) from acessos",Integer.class));
return m;});}
private String run(String c){try{Process p=new ProcessBuilder("bash","-c",c).redirectErrorStream(true).start();String o=new String(p.getInputStream().readAllBytes());p.waitFor();return o;}catch(Exception e){return "";}}
}
""";

    private static final String PX_PAGES = """
package com.astral.proxy.web;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
@Controller
public class PagesController {
@GetMapping({"/","/proxy","/proxy/"}) public String page(){ return "proxy"; }
@GetMapping({"/logs/auditoria","/proxy/logs/auditoria"}) public String audit(){ return "auditoria"; }
}
""";

    private static final String PX_HTML = """
<!DOCTYPE html>
<html lang="pt-br">
<head>
<meta charset="UTF-8"><title>ASTRAL PLATFORM · PROXY</title>
<style>
@font-face{font-family:'Orbitron';src:url('/fonts/orbitron-bold.woff2') format('woff2');font-weight:700}
*{box-sizing:border-box}html,body{height:100%;margin:0}
body{background:#05070d url('/images/Fundo.png') no-repeat center/cover fixed;color:#fff;font-family:'Segoe UI',sans-serif}
header{padding:20px 60px;display:flex;justify-content:space-between;align-items:center}
header h1{margin:0;font-family:'Orbitron';font-weight:900;letter-spacing:.25em;font-size:26px;color:#eef5ff;text-shadow:0 0 8px #9fd8ff,0 0 24px #1668ff}
header .sub{font-family:'Orbitron';letter-spacing:.4em;color:#ffb347;font-size:12px;margin-top:6px}
.wrap{display:flex;gap:28px;padding:20px 60px;height:calc(100% - 130px)}
.side{width:270px;border:1px solid #ffb347;border-radius:14px;background:rgba(12,7,2,.85);display:flex;flex-direction:column;padding:18px;position:sticky;top:20px}
.side .t{font-family:'Orbitron';letter-spacing:.25em;color:#ffcc80;font-size:11px;margin-bottom:14px}
.nav button{display:flex;gap:12px;align-items:center;width:100%;background:none;border:1px solid transparent;border-radius:10px;color:#ffe0b3;padding:11px 12px;font-size:14px;cursor:pointer;text-align:left}
.nav button .n{font-family:'Orbitron';color:#ffb347;font-size:11px}
.nav button.on{border-color:#ffb347;background:rgba(255,179,71,.12)}
.main{flex:1;overflow:auto}
.badge{float:right;border:1px solid #57e389;border-radius:20px;color:#b6ffd0;padding:6px 14px;font-size:12px}
h2{font-family:'Orbitron';letter-spacing:.2em;font-size:18px}
.panel{border:1px solid #ffb347;border-radius:12px;background:rgba(12,7,2,.75);padding:18px;margin-bottom:18px}
.panel h3{font-family:'Orbitron';letter-spacing:.15em;font-size:13px;color:#ffcc80;margin:0 0 14px}
table{width:100%;border-collapse:collapse;font-size:13px}
th{color:#ffcc80;text-align:left;letter-spacing:.1em;font-size:11px;border-bottom:1px solid #553a11;padding:8px}
td{border-bottom:1px solid #33230d;padding:8px}
button.act{background:#3a2410;border:1px solid #ffb347;color:#ffe0b3;border-radius:6px;padding:5px 10px;cursor:pointer}
input,select,textarea{background:#0b0f14;border:1px solid #553;color:#fff;border-radius:6px;padding:7px;margin:3px}
.row{display:flex;gap:10px;flex-wrap:wrap;align-items:flex-start}
.col{flex:1;min-width:260px}
.modal{position:fixed;inset:0;background:rgba(0,0,0,.7);display:flex;align-items:center;justify-content:center;z-index:50}
.modal.hidden{display:none}
.mbox{width:min(1100px,94vw);height:min(700px,88vh);background:#0b0f14;border:1px solid #ffb347;border-radius:10px;display:flex;flex-direction:column;box-shadow:0 0 24px #e65100}
.mhead{display:flex;justify-content:space-between;padding:10px 14px;background:#1a1208;border-bottom:1px solid #33230d}
.mbody{flex:1;overflow:auto;padding:12px}
.pill{border-radius:14px;padding:3px 12px;font-size:11px;font-family:'Orbitron'}
.pill.ok{background:#0d3a22;border:1px solid #57e389;color:#b6ffd0}.pill.no{background:#5a1414;border:1px solid #ff5c5c;color:#ffb3b3}
</style></head>
<body>
<header>
  <div><h1>ASTRAL PLATFORM</h1><div class="sub">PROXY</div></div>
  <button class="act" style="padding:10px 20px" onclick="location.href='/inicio'">🏠 VOLTAR AO INÍCIO</button>
</header>
<div class="wrap">
<aside class="side"><div class="t">MÓDULOS · PROXY</div><div class="nav" id="nav"></div></aside>
<main class="main"><span class="badge">● MOTOR ATIVO · trafficserver</span><h2 id="secTitle"></h2><div id="content"></div></main>
</div>
<div id="modal" class="modal hidden"><div class="mbox"><div class="mhead"><span id="mtitle" style="font-family:'Orbitron';color:#ffcc80"></span><span><button class="act" onclick="fullscreen()">⛶</button> <button class="act" onclick="closeModal()">X</button></span></div><div class="mbody" id="mbody"></div></div></div>
<script th:inline="none">
const SECS=[['users','Usuários e Grupos'],['policies','Políticas de Acesso'],['control','Controle de Acesso'],['logs','Logs de Acesso']];
let cur='users';
nav();show('users');
function nav(){document.getElementById('nav').innerHTML=SECS.map((s,i)=>'<button class="'+(s[0]===cur?'on':'')+'" onclick="show(\\''+s[0]+'\\')"><span class="n">0'+(i+1)+'</span>'+s[1]+'</button>').join('')}
async function api(p,o){try{const r=await fetch('/proxy/api'+p,Object.assign({headers:{'Content-Type':'application/json'}},o));let d=null;try{d=await r.json()}catch(e){}if(!r.ok){alert('Erro em '+p+': '+((d&&(d.error||d.message))||r.status));throw new Error(r.status)}return d}catch(e){alert('Falha em '+p+': '+e.message);throw e}}
function openModal(title,html){document.getElementById('mtitle').textContent=title;document.getElementById('mbody').innerHTML=html;document.getElementById('modal').classList.remove('hidden')}
function closeModal(){document.getElementById('modal').classList.add('hidden')}
function fullscreen(){const m=document.querySelector('.mbox');m.style.width='100vw';m.style.height='100vh'}
async function show(s){cur=s;nav();document.getElementById('secTitle').textContent=SECS.find(x=>x[0]===s)[1].toUpperCase();const c=document.getElementById('content');c.innerHTML='';
if(s==='users'){const[src,us]=await Promise.all([api('/auth/sources'),api('/auth/users')]);
c.innerHTML='<div class="panel"><h3>FONTES DE AUTENTICAÇÃO</h3><table><tr><th>TIPO</th><th>DOMÍNIO</th><th>ADMIN</th><th>CERT</th><th></th></tr>'+src.map(x=>'<tr><td>'+x.tipo+'</td><td>'+x.dominio+'</td><td>'+x.admin_user+'</td><td>'+(x.usar_cert?'<span class="pill ok">SIM</span>':'NÃO')+'</td><td><button class="act" onclick="api(\\'/auth/sources/'+x.id+'\\',{method:\\'DELETE\\'}).then(()=>show(\\'users\\'))">x</button></td></tr>').join('')+'</table>'+
'<div class="row"><select id="at"><option>AD</option><option>LINUX</option><option>POSTGRES</option></select>'+
'<input id="adom" placeholder="Domínio (ex: srvcloud.cloud)">'+
'<input id="aau" placeholder="Usuário Admin">'+
'<input id="aap" type="password" placeholder="Senha do Admin">'+
'<label style="color:#ffe0b3"><input type="checkbox" id="acert"> usar certificado</label>'+
'<button class="act" onclick="addSrc()">+ Sincronizar Fonte</button></div>'+
'<p style="font-size:11px;color:#ffcc80">Exige senha do admin para baixar usuários e grupos para o catálogo interno.</p></div>'+
'<div class="panel"><h3>USUÁRIOS (BANCO LOCAL)</h3><table><tr><th>USER</th><th>GRUPO</th><th>TIPO</th><th></th></tr>'+us.map(x=>'<tr><td>'+x.username+'</td><td>'+x.grupo+'</td><td>'+x.tipo+'</td><td><button class="act" onclick="api(\\'/auth/users/'+x.id+'\\',{method:\\'DELETE\\'}).then(()=>show(\\'users\\'))">x</button></td></tr>').join('')+'</table><div class="row"><input id="uu" placeholder="usuário"><input id="up" type="password" placeholder="senha"><input id="ug" placeholder="grupo"><button class="act" onclick="addUser()">+ Usuário</button></div></div>'+
'<div class="panel"><h3>CATÁLOGO SINCRONIZADO</h3><div class="row"><select id="ft" onchange="fillCat()"><option value="">Tipo...</option></select><select id="fg" onchange="fillCat()"><option value="">Grupo...</option></select></div><div id="catBody" style="margin-top:10px"></div></div>';
api('/auth/tipos').then(t=>{document.getElementById('ft').innerHTML='<option value="">Tipo...</option>'+t.map(x=>'<option>'+x+'</option>').join('')});
fillCat();}
if(s==='policies'){const p=await api('/policies');
c.innerHTML='<div class="panel"><h3>LISTAS (Seguros × Inseguros)</h3><div class="row"><div class="col"><textarea id="seg" rows="10" style="width:100%" placeholder="Seguros (um por linha)">'+p.liberados.map(x=>x.url).join('\\n')+'</textarea></div><div class="col"><textarea id="ins" rows="10" style="width:100%" placeholder="Inseguros (um por linha)">'+p.perigosos.map(x=>x.url).join('\\n')+'</textarea></div></div><button class="act" onclick="saveLists()">Salvar Políticas</button> <span style="font-size:11px;color:#ffcc80">Políticas têm precedência sobre Controle de Acesso. Aplicado via ACL Plugin + Lua.</span></div>'+
'<div class="panel"><h3>IMPORTAR LISTA (.txt)</h3><div class="row"><input id="iurl" placeholder="https://.../lista.txt" style="flex:2"><input type="file" id="ifile"><button class="act" onclick="importUrl()">Via URL</button><button class="act" onclick="importFile()">Via Arquivo</button></div></div>'+
'<div class="panel"><h3>HISTÓRICO politicas_acessos</h3><table><tr><th>ID</th><th>FONTE</th><th>ATUALIZADO</th></tr>'+p.historico.map(x=>'<tr><td>'+x.id+'</td><td>'+x.fonte+'</td><td>'+x.updated_at+'</td></tr>').join('')+'</table></div>';}
if(s==='control'){const[ca,re,po,ti]=await Promise.all([api('/categories'),api('/control/regras'),api('/control/politica'),api('/auth/tipos')]);
c.innerHTML='<div class="panel"><h3>CATEGORIAS (xml,yaml,txt,tar.gz,rar,zip)</h3><table><tr><th>NOME</th><th>FORMATO</th><th></th></tr>'+ca.map(x=>'<tr><td>'+x.nome+'</td><td>'+x.formato+'</td><td><button class="act" onclick="api(\\'/categories/'+x.id+'\\',{method:\\'DELETE\\'}).then(()=>show(\\'control\\'))">x</button></td></tr>').join('')+'</table><div class="row"><input id="kan" placeholder="nome (ex: redes_sociais)"><select id="kfo"><option>txt</option><option>xml</option><option>yaml</option><option>tar.gz</option><option>rar</option><option>zip</option></select><input type="file" id="kfi"><button class="act" onclick="addCat()">+ Categoria</button></div></div>'+
'<div class="panel"><h3>POLÍTICAS (período)</h3><table><tr><th>NOME</th><th>DIAS</th><th>INDETERMINADA</th><th>FIM</th></tr>'+po.map(x=>'<tr><td>'+x.nome+'</td><td>'+x.dias+'</td><td>'+(x.indeterminada?'<span class="pill ok">SIM</span>':'NÃO')+'</td><td>'+(x.fim||'-')+'</td></tr>').join('')+'</table><div class="row"><input id="pn" placeholder="nome"><input id="pd" placeholder="dias" style="max-width:80px"><label style="color:#ffe0b3"><input type="checkbox" id="pi" checked> indeterminada</label><button class="act" onclick="addPol()">+ Política</button></div></div>'+
'<div class="panel"><h3>REGRAS POR USUÁRIO/GRUPO (Header Rewrite + Lua)</h3><table><tr><th>ENDEREÇO</th><th>USUÁRIO</th><th>GRUPO</th><th>CATEGORIA</th><th>AÇÃO</th><th></th></tr>'+re.map(x=>'<tr><td>'+x.endereco+'</td><td>'+x.usuario+'</td><td>'+x.grupo+'</td><td>'+x.categoria_site+'</td><td>'+(x.acao?'<span class="pill ok">PERMITIR</span>':'<span class="pill no">BLOQUEAR</span>')+'</td><td><button class="act" onclick="api(\\'/control/regra/'+x.id+'\\',{method:\\'DELETE\\'}).then(()=>show(\\'control\\'))">x</button></td></tr>').join('')+'</table>'+
'<div class="row"><input id="re" placeholder="endereco">'+
'<select id="rt" onchange="loadGrupos()"><option value="">Tipo...</option>'+ti.map(t=>'<option>'+t+'</option>').join('')+'</select>'+
'<select id="rg" onchange="loadUsuarios()"><option value="">Grupo...</option></select>'+
'<select id="ru"><option value="">Usuário...</option></select>'+
'<input id="rc" placeholder="categoria"><select id="ra"><option value="1">PERMITIR</option><option value="0">BLOQUEAR</option></select>'+
'<button class="act" onclick="addRegra()">+ Regra</button></div></div>';}
if(s==='logs'){c.innerHTML='<div class="panel"><h3>ÚLTIMOS 10 SITES</h3><div id="st10"></div><button class="act" onclick="expand(\\'sites\\')">Expandir (7 dias)</button></div><div class="panel"><h3>ÚLTIMOS 10 ARQUIVOS</h3><div id="ft10"></div><button class="act" onclick="expand(\\'files\\')">Expandir (7 dias)</button></div><div class="panel"><h3>AUDITORIA</h3><a class="act" style="text-decoration:none;display:inline-block" href="/proxy/logs/auditoria" target="_blank">/proxy/logs/auditoria (grupo auditoria · PDF)</a></div>';
api('/logs/sites?limit=10').then(l=>{document.getElementById('st10').innerHTML=tblSites(l)});
api('/logs/files?limit=10').then(l=>{document.getElementById('ft10').innerHTML=tblFiles(l)});}
}
async function fillCat(){const t=document.getElementById('ft').value,g=document.getElementById('fg').value;
const l=await api('/auth/catalog?tipo='+encodeURIComponent(t)+'&grupo='+encodeURIComponent(g));
document.getElementById('catBody').innerHTML='<table><tr><th>TIPO</th><th>USUÁRIO</th><th>GRUPO</th></tr>'+l.map(x=>'<tr><td>'+x.tipo+'</td><td>'+x.usuario+'</td><td>'+x.grupo+'</td></tr>').join('')+'</table>';
const fg=document.getElementById('fg'); if(fg&&t){api('/auth/grupos?tipo='+encodeURIComponent(t)).then(gs=>{fg.innerHTML='<option value="">Grupo...</option>'+gs.map(x=>'<option>'+x+'</option>').join('')})}}
async function loadGrupos(){const t=document.getElementById('rt').value;const gs=await api('/auth/grupos?tipo='+encodeURIComponent(t));document.getElementById('rg').innerHTML='<option value="">Grupo...</option>'+gs.map(x=>'<option>'+x+'</option>').join('');document.getElementById('ru').innerHTML='<option value="">Usuário...</option>'}
async function loadUsuarios(){const g=document.getElementById('rg').value;const us=await api('/auth/usuarios?grupo='+encodeURIComponent(g));document.getElementById('ru').innerHTML='<option value="">Usuário...</option>'+us.map(x=>'<option>'+x+'</option>').join('')}
function tblSites(l){return '<table><tr><th>USER</th><th>IP</th><th>DATA</th><th>GRUPO</th><th>TIPO</th><th>DESTINO</th></tr>'+l.map(x=>'<tr><td>'+x.usuario+'</td><td>'+x.ip+'</td><td>'+x.data_ddmmyyyy+'</td><td>'+x.grupo+'</td><td>'+x.tipo_label+'</td><td>'+x.destino+'</td></tr>').join('')+'</table>'}
function tblFiles(l){return '<table><tr><th>USER</th><th>GRUPO</th><th>DIREÇÃO</th><th>TAMANHO</th><th>DATA</th><th>ENDEREÇO</th></tr>'+l.map(x=>'<tr><td>'+x.usuario+'</td><td>'+x.grupo+'</td><td>'+x.direcao+'</td><td>'+x.tamanho+'</td><td>'+x.data_ddmmyyyy+'</td><td>'+x.endereco+'</td></tr>').join('')+'</table>'}
let pg=0,kind='sites';
async function expand(k){kind=k;pg=0;const l=await api('/logs/'+k+'?limit=10&page=0');openModal('LOGS · '+k,('<div id="pgbody">'+(k==='sites'?tblSites(l):tblFiles(l))+'</div><div style="margin-top:10px"><button class="act" onclick="page(-1)">‹</button> <span id="pgn">pág 1</span> <button class="act" onclick="page(1)">›</button> <input id="pq" placeholder="pesquisar" onkeydown="if(event.key===\\'Enter\\')search()"> <button class="act" onclick="search()">🔍</button></div>'))}
async function page(d){pg=Math.max(0,pg+d);const q=document.getElementById('pq')?document.getElementById('pq').value:'';const l=await api('/logs/'+kind+'?limit=10&page='+pg+(q?'&q='+encodeURIComponent(q):''));document.getElementById('pgbody').innerHTML=kind==='sites'?tblSites(l):tblFiles(l);document.getElementById('pgn').textContent='pág '+(pg+1)}
async function search(){pg=0;const q=document.getElementById('pq').value;const l=await api('/logs/'+kind+'?limit=10&page=0&q='+encodeURIComponent(q));document.getElementById('pgbody').innerHTML=kind==='sites'?tblSites(l):tblFiles(l)}
function addSrc(){api('/auth/sources',{method:'POST',body:JSON.stringify({tipo:at.value,dominio:adom.value,admin_user:aau.value,admin_pass:aap.value,usar_cert:acert.checked})}).then(r=>{alert('Sincronizado: '+r.synced+' usuários/grupos');show('users')})}
function addUser(){api('/auth/users',{method:'POST',body:JSON.stringify({username:uu.value,password:up.value,grupo:ug.value})}).then(()=>show('users'))}
function addCert(){const f=cf.files[0];if(!f)return;const r=new FileReader();r.onload=()=>api('/certs',{method:'POST',body:JSON.stringify({nome:cn.value,tipo:ct.value,conteudo:r.result})}).then(()=>show('users'));r.readAsText(f)}
function testAuth(){api('/auth/test',{method:'POST',body:JSON.stringify({username:tu.value,password:tp.value,mode:tm.value,cert:tc.value})}).then(d=>{tres.innerHTML=d.success?'<span class="pill ok">OK · '+d.tipoLabel+' · '+d.grupo+'</span>':'<span class="pill no">FALHA</span>'})}
function saveLists(){api('/policies/lists',{method:'POST',body:JSON.stringify({seguros:seg.value,inseguros:ins.value})}).then(()=>show('policies'))}
function importUrl(){api('/policies/import',{method:'POST',body:JSON.stringify({url:iurl.value})}).then(()=>show('policies'))}
function importFile(){const f=ifile.files[0];if(!f)return;const r=new FileReader();r.onload=()=>api('/policies/import',{method:'POST',body:JSON.stringify({content:r.result})}).then(()=>show('policies'));r.readAsText(f)}
function addCat(){const f=kfi.files[0];const done=(txt)=>api('/categories',{method:'POST',body:JSON.stringify({nome:kan.value,formato:kfo.value,conteudo:txt})}).then(()=>show('control'));if(f){const r=new FileReader();r.onload=()=>done(r.result);r.readAsText(f)}else done('')}
function addPol(){api('/control/politica',{method:'POST',body:JSON.stringify({nome:pn.value,dias:pd.value||0,indeterminada:pi.checked})}).then(()=>show('control'))}
function addRegra(){api('/control/regra',{method:'POST',body:JSON.stringify({endereco:re.value,usuario:ru.value,grupo:rg.value,categoria_site:rc.value,acao:ra.value==='1'})}).then(()=>show('control'))}
</script></body></html>
""";

    private static final String PX_AUDIT = """
<!DOCTYPE html>
<html lang="pt-br"><head><meta charset="UTF-8"><title>ASTRAL · AUDITORIA</title>
<style>body{background:#05070d;color:#fff;font-family:'Segoe UI',sans-serif;margin:0;padding:30px}
h1{font-family:'Orbitron',sans-serif;color:#ffb347;letter-spacing:.2em}
table{width:100%;border-collapse:collapse;font-size:12px;margin-bottom:20px}
th{color:#ffcc80;text-align:left;border-bottom:1px solid #553a11;padding:6px}
td{border-bottom:1px solid #33230d;padding:6px}
button{background:#3a2410;border:1px solid #ffb347;color:#ffe0b3;border-radius:6px;padding:6px 12px;cursor:pointer}
input{background:#0b0f14;border:1px solid #553;color:#fff;border-radius:6px;padding:6px}
@media print{button,input{display:none}}
</style></head><body>
<h1>AUDITORIA · PROXY</h1>
<div><input id="q" placeholder="pesquisar usuário/URL"><button onclick="load(0)">🔍</button> <button onclick="window.print()">Exportar PDF</button> <button onclick="p(-1)">‹</button><span id="pgn">pág 1</span><button onclick="p(1)">›</button></div>
<h2 style="color:#ffcc80">Últimos acessos</h2><div id="s"></div>
<h2 style="color:#ffcc80">Arquivos</h2><div id="f"></div>
<script th:inline="none">
let pg=0;
async function load(p){pg=Math.max(0,p);const q=document.getElementById('q').value;
const d=await(await fetch('/proxy/api/logs/auditoria?page='+pg+(q?'&q='+encodeURIComponent(q):''))).json();
document.getElementById('s').innerHTML='<table><tr><th>USER</th><th>IP</th><th>DATA</th><th>GRUPO</th><th>TIPO</th><th>DESTINO</th></tr>'+d.sites.map(x=>'<tr><td>'+x.usuario+'</td><td>'+x.ip+'</td><td>'+x.data_ddmmyyyy+'</td><td>'+x.grupo+'</td><td>'+x.tipo_label+'</td><td>'+x.destino+'</td></tr>').join('')+'</table>';
document.getElementById('f').innerHTML='<table><tr><th>USER</th><th>GRUPO</th><th>DIREÇÃO</th><th>TAMANHO</th><th>ENDEREÇO</th></tr>'+d.files.map(x=>'<tr><td>'+x.usuario+'</td><td>'+x.grupo+'</td><td>'+x.direcao+'</td><td>'+x.tamanho+'</td><td>'+x.endereco+'</td></tr>').join('')+'</table>';
document.getElementById('pgn').textContent='pág '+(pg+1)+' / '+Math.max(1,Math.ceil((d.total||0)/10));}
function p(d){load(pg+d)}
load(0);
</script></body></html>
""";
}