#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Instalador Web Unificado - Astral Platform HCI
Uso: sudo python3 install.py

Fluxo completo:
 1. Sobe Flask na porta 5000 servindo fabric/frontend/install.html
 2. Thread paralela instala: firewall, Node.js, Oracle JDK 21, Maven,
    PostgreSQL e Nginx (proxy reverso)
 3. DEPOIS de instalar tudo: organiza o projeto no layout Maven
    (java, templates, imagens), injeta pom.xml com Thymeleaf e
    devolve o ownership (chown) dos arquivos criados pelo root
 4. COMPILA o projeto com Maven e cria systemd service do Spring Boot
 5. Frontend (SSE) mostra progresso; em 100% exibe o form do banco
 6. POST /api/setup-db cria user+db 'astral' e injeta application.properties
 7. Botão "Concluir" mata o Flask e redireciona para o Nginx (porta 80)
"""

import os
import sys
import socket
import subprocess
import time
import json
import threading
import re
import shutil

# ============================================================
# CONFIGURAÇÕES GLOBAIS
# ============================================================
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
        s.connect(('8.8.8.8', 80))
        ip = s.getsockname()[0]
    except Exception:
        ip = '127.0.0.1'
    finally:
        s.close()
    return ip

def detect_distro():
    try:
        with open('/etc/os-release', 'r') as f:
            content = f.read().lower()
            if 'debian' in content or 'ubuntu' in content:
                return 'debian'
            elif 'rhel' in content or 'fedora' in content or 'almalinux' in content \
                 or 'centos' in content or 'rocky' in content:
                return 'rhel'
            elif 'arch' in content or 'manjaro' in content:
                return 'arch'
    except FileNotFoundError:
        pass
    return 'unknown'

def detect_pg_service():
    candidates = ["postgresql", "postgresql-server",
                  "postgresql-16", "postgresql-15", "postgresql-14",
                  "postgresql-13", "postgresql-12"]
    for name in candidates:
        r = subprocess.run(["systemctl", "cat", name], capture_output=True)
        if r.returncode == 0:
            return name
    return "postgresql"

# ============================================================
# FIREWALL (iptables exclusivo)
# ============================================================
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
                d = subprocess.run(['iptables', '-D', 'INPUT', '-p', 'tcp',
                                    '--dport', str(p), '-j', 'ACCEPT'], capture_output=True)
                if d.returncode != 0:
                    break
            subprocess.run(['iptables', '-I', 'INPUT', '1', '-p', 'tcp',
                            '--dport', str(p), '-j', 'ACCEPT'], check=True, capture_output=True)
            rules_changed = True

        if rules_changed:
            if os.path.exists('/etc/init.d/iptables-persistent') or \
               os.path.exists('/usr/sbin/netfilter-persistent'):
                subprocess.run(['sh', '-c', 'iptables-save > /etc/iptables/rules.v4'],
                               check=True, capture_output=True)
            elif os.path.exists('/etc/sysconfig/iptables'):
                subprocess.run(['sh', '-c', 'iptables-save > /etc/sysconfig/iptables'],
                               check=True, capture_output=True)
        print("[OK] Portas da aplicação liberadas no iptables.")
    except Exception as e:
        print(f"[AVISO] Falha ao injetar portas no iptables: {e}")

# ============================================================
# DEPENDÊNCIAS PYTHON (Flask/Psycopg2) — antes de importar Flask
# ============================================================
def ensure_dependencies_installed():
    print("[INFO] Assegurando dependências globais de Python (Flask, Psycopg2)...")
    distro = detect_distro()

    pip_install_cmd = ""
    if distro == 'debian':
        pip_install_cmd = "apt-get update && apt-get install -y python3-pip python3-psycopg2"
    elif distro == 'rhel':
        pip_install_cmd = "dnf install -y python3-pip python3-psycopg2"
    elif distro == 'arch':
        pip_install_cmd = "pacman -Sy --noconfirm python-pip python-psycopg2"

    if pip_install_cmd:
        subprocess.run(pip_install_cmd, shell=True, capture_output=True)

    res = subprocess.run("pip3 install flask psycopg2-binary --break-system-packages",
                         shell=True, capture_output=True, text=True)
    if res.returncode != 0:
        print("[AVISO] Tentando instalação pip sem flag --break-system-packages...")
        subprocess.run("pip3 install flask psycopg2-binary", shell=True, capture_output=True)

# ============================================================
# POM.XML (Spring Boot + Thymeleaf + JPA + PostgreSQL JDBC)
# ============================================================
def inject_java_pom_template():
    pom_path = os.path.join(APP_DIR, "pom.xml")

    if not os.path.exists(pom_path):
        print("[INFO] Injetando template de dependências Maven (pom.xml)...")
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
    <description>Astral Platform Backend</description>
    <properties>
        <java.version>21</java.version>
    </properties>
    <dependencies>
        <!-- Spring Boot Web para APIs REST -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <!-- Thymeleaf: renderiza o dashboard (templates/home.html) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-thymeleaf</artifactId>
        </dependency>
        <!-- Spring Data JPA para Banco de Dados -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <!-- Driver PostgreSQL JDBC -->
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
"""
        try:
            with open(pom_path, "w") as f:
                f.write(pom_content)
            print("[OK] pom.xml injetado na raiz do projeto.")
        except Exception as e:
            print(f"[AVISO] Não foi possível criar o pom.xml: {e}")
    else:
        # pom.xml já existe: garante que o Thymeleaf não ficou de fora
        try:
            with open(pom_path, "r") as f:
                content = f.read()
            if "spring-boot-starter-thymeleaf" not in content:
                dep = """        <!-- Thymeleaf: renderiza o dashboard (templates/home.html) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-thymeleaf</artifactId>
        </dependency>
    </dependencies>"""
                content = content.replace("    </dependencies>", dep, 1)
                with open(pom_path, "w") as f:
                    f.write(content)
                print("[OK] Dependência do Thymeleaf adicionada ao pom.xml existente.")
        except Exception as e:
            print(f"[AVISO] Não foi possível atualizar o pom.xml: {e}")

# ============================================================
# ORGANIZAÇÃO DO PROJETO (layout Maven) — DEPOIS de instalar tudo
# ============================================================
def inject_spring_sources():
    """Migra .java/templates/imagens do layout antigo para o layout Maven."""
    base_src  = os.path.join(APP_DIR, "src", "main", "java", "com", "astral", "main")
    ctrl_dir  = os.path.join(base_src, "controller")
    model_dir = os.path.join(base_src, "model")
    tpl_dir   = os.path.join(APP_DIR, "src", "main", "resources", "templates")
    imgs_dir  = os.path.join(APP_DIR, "src", "main", "resources", "static", "images")
    for d in (base_src, ctrl_dir, model_dir, tpl_dir, imgs_dir):
        os.makedirs(d, exist_ok=True)

    # ---- 1) Migra os .java do layout antigo para o pacote certo ----
    legacy_java = os.path.join(APP_DIR, "fabric", "frontend", "main", "java")
    if os.path.isdir(legacy_java):
        print("[INFO] Migrando .java do local antigo para o layout Maven...")
        for root, _, files in os.walk(legacy_java):
            for fn in files:
                if not fn.endswith(".java"):
                    continue
                src_file = os.path.join(root, fn)
                with open(src_file, "r", errors="ignore") as f:
                    head = f.read(600)
                m = re.search(r"package\s+([A-Za-z0-9_\.]+)\s*;", head)
                pkg = m.group(1) if m else "com.astral.main"
                dest_dir = os.path.join(APP_DIR, "src", "main", "java", *pkg.split("."))
                os.makedirs(dest_dir, exist_ok=True)
                dest_file = os.path.join(dest_dir, fn)
                shutil.copy2(src_file, dest_file)   # sobrescreve sem perguntar
                # limpa espaços presos nas strings ("dns " -> "dns", ",  " -> ",")
                subprocess.run(
                    f"sed -i -E 's/ +\",/\",/g; s/ +\"\\)/\")/g; s/, +\"/,\"/g' {dest_file}",
                    shell=True, capture_output=True)
                print(f"[OK] {fn} -> {os.path.relpath(dest_file, APP_DIR)}")

    # ---- 2) Classe main do Spring Boot (sem ela o jar não sobe) ----
    main_class = os.path.join(base_src, "AstralApplication.java")
    if not os.path.exists(main_class):
        with open(main_class, "w") as f:
            f.write("""package com.astral.main;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AstralApplication {
    public static void main(String[] args) {
        SpringApplication.run(AstralApplication.class, args);
    }
}
""")
        print("[OK] AstralApplication.java criado.")

    # ---- 3) Template Thymeleaf: prefere o home.html do usuário ----
    legacy_tpl = os.path.join(APP_DIR, "fabric", "frontend", "main",
                              "resources", "templates", "home.html")
    target_tpl = os.path.join(tpl_dir, "home.html")
    if os.path.exists(legacy_tpl):
        shutil.copy2(legacy_tpl, target_tpl)
        print("[OK] home.html do usuário copiado para src/main/resources/templates/.")
    elif not os.path.exists(target_tpl):
        with open(target_tpl, "w") as f:
            f.write("""<!DOCTYPE html>
<html lang="pt-br" xmlns:th="http://www.thymeleaf.org">
<head>
<meta charset="UTF-8">
<title th:text="${pageTitle}">ASTRAL PLATFORM</title>
<style>
 body{background:#12161f;color:#fff;font-family:'Segoe UI',sans-serif;margin:0;padding:40px}
 h1{text-align:center;color:#4facfe}
 .grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(190px,1fr));gap:20px;max-width:1100px;margin:0 auto}
 .card{background:#1c2331;border:1px solid #2a3550;border-radius:12px;padding:24px;text-align:center;color:#fff;text-decoration:none;transition:.2s}
 .card:hover{transform:translateY(-4px);border-color:#4facfe}
 .card img{width:64px;height:64px;margin-bottom:12px}
</style>
</head>
<body>
<h1 th:text="${pageTitle}">ASTRAL PLATFORM</h1>
<div class="grid">
  <a class="card" th:each="btn : ${buttons}" th:href="@{${btn.route}}" th:title="${btn.label}">
    <img th:src="@{'/images/' + ${btn.image}}" th:alt="${btn.label}" onerror="this.style.display='none'">
    <div th:text="${btn.label}">Card</div>
  </a>
</div>
</body>
</html>
""")
        print("[OK] home.html padrão gerado.")

    # ---- 4) Ícones dos cards -> static/images (Spring serve em /images/) ----
    legacy_imgs = os.path.join(APP_DIR, "fabric", "frontend", "main", "images")
    if os.path.isdir(legacy_imgs):
        for fn in os.listdir(legacy_imgs):
            if fn.lower().endswith((".png", ".jpg", ".jpeg", ".svg", ".gif")):
                dst = os.path.join(imgs_dir, fn)
                if not os.path.exists(dst):
                    shutil.copy2(os.path.join(legacy_imgs, fn), dst)
        print("[OK] Ícones do dashboard copiados para src/main/resources/static/images/.")

    # ---- 5) Fundo do login: garante no lugar que o Nginx serve ----
    login_imgs = os.path.join(APP_DIR, "fabric", "frontend", "login", "images")
    os.makedirs(login_imgs, exist_ok=True)
    for cand in (os.path.join(legacy_imgs, "login.png"),
                 os.path.join(APP_DIR, "fabric", "frontend", "main", "login.png")):
        if os.path.exists(cand) and not os.path.exists(os.path.join(login_imgs, "login.png")):
            shutil.copy2(cand, os.path.join(login_imgs, "login.png"))
            print("[OK] login.png garantido em fabric/frontend/login/images/.")

# ============================================================
# COMPILAÇÃO E DEPLOY DO SPRING BOOT
# ============================================================
def build_and_deploy_spring_boot():
    """Compila o projeto com Maven e cria systemd service para o Spring Boot."""
    print("\n[INFO] ========== COMPILANDO SPRING BOOT ==========")

    pom_path = os.path.join(APP_DIR, "pom.xml")
    if not os.path.exists(pom_path):
        print("[ERRO] pom.xml não encontrado. Abortando compilação.")
        return False

    # ---- 1) Compilação com Maven ----
    print("[INFO] Compilando projeto com Maven (mvn package)...")
    env = os.environ.copy()
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        env["JAVA_HOME"] = java_home

    build_cmd = f"cd {APP_DIR} && mvn -B -DskipTests clean package"
    print(f"[CMD] {build_cmd}")

    result = subprocess.run(build_cmd, shell=True, capture_output=True, text=True, env=env)

    if result.returncode != 0:
        print(f"[ERRO] Falha na compilação Maven:")
        print(result.stdout[-1000:] if len(result.stdout) > 1000 else result.stdout)
        print(result.stderr[-1000:] if len(result.stderr) > 1000 else result.stderr)
        return False

    print("[OK] Compilação Maven concluída com sucesso.")

    # ---- 2) Localiza o JAR gerado ----
    target_dir = os.path.join(APP_DIR, "target")
    jar_files = [f for f in os.listdir(target_dir) if f.endswith(".jar") and "original" not in f]

    if not jar_files:
        print("[ERRO] Nenhum arquivo .jar encontrado em target/")
        return False

    jar_file = os.path.join(target_dir, jar_files[0])
    print(f"[OK] JAR localizado: {jar_file}")

    # ---- 3) Cria systemd service ----
    print("[INFO] Criando systemd service para o Spring Boot...")

    service_content = f"""[Unit]
Description=Astral Platform Spring Boot Application
After=network.target postgresql.service
Requires=postgresql.service

[Service]
Type=simple
User=root
WorkingDirectory={APP_DIR}
ExecStart=/usr/bin/java -jar {jar_file}
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal
Environment=JAVA_OPTS=-Xmx512m

[Install]
WantedBy=multi-user.target
"""

    service_path = "/etc/systemd/system/astral-platform.service"
    try:
        with open(service_path, "w") as f:
            f.write(service_content)
        print(f"[OK] Service criado em {service_path}")
    except Exception as e:
        print(f"[ERRO] Falha ao criar systemd service: {e}")
        return False

    # ---- 4) Habilita e inicia o serviço ----
    print("[INFO] Habilitando e iniciando o serviço astral-platform...")

    subprocess.run("systemctl daemon-reload", shell=True, capture_output=True)
    subprocess.run("systemctl enable astral-platform.service", shell=True, capture_output=True)
    subprocess.run("systemctl start astral-platform.service", shell=True, capture_output=True)

    # Aguarda o Spring Boot subir (max 30s)
    print("[INFO] Aguardando Spring Boot inicializar...")
    for i in range(30):
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            result = sock.connect_ex(('127.0.0.1', 8081))
            sock.close()
            if result == 0:
                print("[OK] Spring Boot está rodando na porta 8081!")
                return True
        except Exception:
            pass
        time.sleep(1)

    print("[AVISO] Spring Boot pode não ter inicializado completamente. Verifique: journalctl -u astral-platform.service")
    return False

# ============================================================
# CHOWN — devolve ao usuário real tudo que o root criou
# ============================================================
def fix_ownership():
    """Detecta o dono real do projeto (mesmo via sudo) e aplica chown -R,
    para que mv/cp do usuário nunca mais perguntem 'desobedecendo o modo'."""
    uid = os.stat(APP_DIR).st_uid
    gid = os.stat(APP_DIR).st_gid
    subprocess.run(f"chown -R {uid}:{gid} {APP_DIR}", shell=True, capture_output=True)
    print(f"[OK] chown -R {uid}:{gid} aplicado em {APP_DIR} "
          f"(arquivos criados pelo instalador devolvidos ao usuário do projeto).")

# ============================================================
# APPLICATION.PROPERTIES (Spring Boot) — injetado no setup-db
# ============================================================
def inject_spring_properties(username, password):
    resources_dir = os.path.join(APP_DIR, "src", "main", "resources")
    properties_path = os.path.join(resources_dir, "application.properties")
    os.makedirs(resources_dir, exist_ok=True)

    properties_content = f"""# ==========================================
# Configuracao Astral Platform (Spring Boot)
# ==========================================

# Porta da API (Nginx faz proxy de /api/ e /inicio para ca)
server.port=8081

# Conexao com PostgreSQL (database 'astral')
spring.datasource.url=jdbc:postgresql://localhost:5432/astral
spring.datasource.username={username}
spring.datasource.password={password}
spring.datasource.driver-class-name=org.postgresql.Driver

# JPA / Hibernate
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.format_sql=true
spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect

# Jackson (JSON)
spring.jackson.serialization.fail-on-empty-beans=false
"""
    try:
        with open(properties_path, "w") as f:
            f.write(properties_content)
        print(f"[OK] application.properties injetado em: {properties_path}")
    except Exception as e:
        print(f"[AVISO] Falha ao criar application.properties: {e}")

# ============================================================
# EXECUÇÃO DE COMANDOS COM STREAM
# ============================================================
def run_command_stream(cmd, shell=True):
    print(f"\n[SISTEMA] Executando: {cmd}")
    process = subprocess.Popen(cmd, shell=shell, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True, bufsize=1)
    for line in process.stdout:
        match = re.search(r'(?:unpacking|installing|upgrading|processing)\s+([a-zA-Z0-9\-_.]+)',
                          line, re.IGNORECASE)
        if match:
            with state.lock:
                state.package_name = match.group(1)
    process.wait()
    return process.returncode == 0

def update_progress(percent, status_msg):
    with state.lock:
        state.progress = percent
        state.status = status_msg

# ============================================================
# THREAD PRINCIPAL DE INSTALAÇÃO
# ============================================================
def installation_thread():
    time.sleep(2)

    configure_firewall()
    inject_java_pom_template()

    distro = detect_distro()
    update_cmd = ""
    install_cmd_base = ""

    if distro == 'debian':
        update_cmd = "apt-get update"
        install_cmd_base = "DEBIAN_FRONTEND=noninteractive apt-get install -y"
    elif distro == 'rhel':
        update_cmd = "dnf makecache"
        install_cmd_base = "dnf install -y"
    elif distro == 'arch':
        update_cmd = "pacman -Sy"
        install_cmd_base = "pacman -S --noconfirm"
    else:
        update_progress(0, "Erro: Distro não detectada.")
        return

    # ---- Repositórios ----
    update_progress(5, "Sincronizando repositórios do Linux...")
    run_command_stream(update_cmd)
    update_progress(15, "Repositórios sincronizados.")

    # ---- Node.js + dependências JS ----
    update_progress(20, "Verificando Node.js, NPM e dependências JS...")
    if not (shutil.which("node") or shutil.which("nodejs")) or not shutil.which("npm"):
        print("[INFO] Instalando Node.js e NPM...")
        node_pkg = "nodejs npm curl" if distro != 'rhel' else "nodejs nodejs-npm curl"
        run_command_stream(f"{install_cmd_base} {node_pkg}")
    subprocess.run("npm install -g pg express cors 2>/dev/null || true", shell=True)
    update_progress(35, "Ambiente JS e dependências prontos.")

    # ---- Oracle Java 21 ----
    update_progress(40, "Avaliando instalação do Oracle Java 21 LTS...")
    java_check = subprocess.run("java -version", shell=True, capture_output=True, text=True)

    if "Oracle" in java_check.stderr or "Oracle" in java_check.stdout:
        print("[INFO] Oracle Java já detectado como padrão no sistema.")
    else:
        print("[INFO] Baixando e instalando Oracle JDK 21...")
        if distro == 'debian':
            run_command_stream("curl -s -L -o /tmp/jdk.deb https://download.oracle.com/java/21/latest/jdk-21_linux-x64_bin.deb")
            run_command_stream("DEBIAN_FRONTEND=noninteractive dpkg -i /tmp/jdk.deb")
        elif distro == 'rhel':
            run_command_stream("dnf install -y https://download.oracle.com/java/21/latest/jdk-21_linux-x64_bin.rpm")
        elif distro == 'arch':
            run_command_stream("curl -s -L -o /tmp/jdk.tar.gz https://download.oracle.com/java/21/latest/jdk-21_linux-x64_bin.tar.gz")
            run_command_stream("tar -xzf /tmp/jdk.tar.gz -C /opt/")
            subprocess.run("ln -sf /opt/jdk-21*/bin/java /usr/bin/java", shell=True)
            subprocess.run("ln -sf /opt/jdk-21*/bin/javac /usr/bin/javac", shell=True)
    update_progress(50, "Oracle Java configurado.")

    # ---- Maven + JAVA_HOME + dependências Spring Boot ----
    update_progress(52, "Instalando o Maven...")
    if not shutil.which("mvn"):
        if distro == 'debian':
            run_command_stream("DEBIAN_FRONTEND=noninteractive apt-get install -y maven")
        elif distro == 'rhel':
            run_command_stream("dnf install -y maven")
        elif distro == 'arch':
            run_command_stream("pacman -S --noconfirm maven")

    java_home = None
    rl = subprocess.run("readlink -f $(which java)", shell=True, capture_output=True, text=True)
    if rl.returncode == 0 and rl.stdout.strip():
        java_bin = rl.stdout.strip()
        java_home = os.path.dirname(os.path.dirname(java_bin))
        # Se o dnf trouxe um OpenJDK junto, força o Oracle de volta como padrão
        subprocess.run(["alternatives", "--set", "java", java_bin], capture_output=True)
        try:
            with open("/etc/profile.d/java_home.sh", "w") as f:
                f.write(f"export JAVA_HOME={java_home}\nexport PATH=$JAVA_HOME/bin:$PATH\n")
            subprocess.run("chmod +x /etc/profile.d/java_home.sh", shell=True)
        except Exception as e:
            print(f"[AVISO] Não foi possível gravar /etc/profile.d/java_home.sh: {e}")
        os.environ["JAVA_HOME"] = java_home
        print(f"[OK] JAVA_HOME configurado: {java_home}")

    update_progress(53, "Pré-baixando dependências do Spring Boot (Maven)...")
    pom_path = os.path.join(APP_DIR, "pom.xml")
    env = os.environ.copy()
    if java_home:
        env["JAVA_HOME"] = java_home
    if shutil.which("mvn") and os.path.exists(pom_path):
        res_mvn = subprocess.run(f"cd {APP_DIR} && mvn -B -q dependency:go-offline",
                                 shell=True, capture_output=True, text=True, env=env)
        if res_mvn.returncode == 0:
            print("[OK] Dependências do Spring Boot baixadas.")
        else:
            print(f"[AVISO] mvn dependency:go-offline falhou: {res_mvn.stderr[-800:]}")
    update_progress(54, "Maven configurado.")

    # ---- PostgreSQL ----
    update_progress(55, "Instalando o motor de banco de dados (PostgreSQL)...")
    pg_pkg = "postgresql postgresql-contrib"
    if distro == 'rhel':
        pg_pkg = "postgresql postgresql-server postgresql-contrib"
    run_command_stream(f"{install_cmd_base} {pg_pkg}")
    update_progress(65, "PostgreSQL instalado.")

    # ---- Nginx ----
    update_progress(70, "Instalando e configurando proxy Nginx...")
    success_nginx = True
    if not shutil.which("nginx"):
        success_nginx = run_command_stream(f"{install_cmd_base} nginx")

    if success_nginx:
        subprocess.run("systemctl disable --now httpd", shell=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run("systemctl disable --now apache2", shell=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run("sed -i 's/.*listen.*\\[::\\]:80.*/#&/' /etc/nginx/nginx.conf 2>/dev/null", shell=True)

        print("[INFO] Gerando configuração avançada do Nginx via Python...")
        frontend_path = os.path.join(APP_DIR, "fabric", "frontend")

        subprocess.run(f"chmod -R 755 {frontend_path} 2>/dev/null", shell=True)
        current_path = frontend_path
        while current_path != '/':
            subprocess.run(f"chmod o+x {current_path} 2>/dev/null", shell=True)
            current_path = os.path.dirname(current_path)

        if distro == 'rhel':
            subprocess.run("setsebool -P httpd_can_network_connect 1 2>/dev/null", shell=True)
            subprocess.run(f"chcon -Rt httpd_sys_content_t {frontend_path} 2>/dev/null", shell=True)

        if distro == 'debian':
            aa_profile = "/etc/apparmor.d/usr.sbin.nginx"
            aa_override = "/etc/apparmor.d/local/usr.sbin.nginx"
            if os.path.exists(aa_profile):
                print("[INFO] Ajustando AppArmor para o Nginx...")
                rule = f"\n  {frontend_path}/ r,\n  {frontend_path}/** r,\n"
                try:
                    os.makedirs(os.path.dirname(aa_override), exist_ok=True)
                    content = ""
                    if os.path.exists(aa_override):
                        with open(aa_override, "r") as f:
                            content = f.read()
                    if frontend_path not in content:
                        with open(aa_override, "a") as f:
                            f.write(rule)
                    subprocess.run("apparmor_parser -r /etc/apparmor.d/usr.sbin.nginx 2>/dev/null", shell=True)
                except Exception as e:
                    print(f"[AVISO] Falha no AppArmor: {e}")

        nginx_conf = f"""server {{
    listen 80 default_server;
    server_name _;

    root {frontend_path}/login;
    index index.html;

    location / {{
        try_files $uri $uri/ =404;
    }}

    # Imagens: primeiro do disco (fundo do login), fallback no Spring (ícones dos cards)
    location /images/ {{
        try_files $uri @spring;
    }}

    location @spring {{
        proxy_pass http://127.0.0.1:8081;
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
            if distro == 'debian':
                conf_path = "/etc/nginx/sites-available/astral.conf"
            with open(conf_path, "w") as f:
                f.write(nginx_conf)
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

    # ---- Ativação do PostgreSQL + auth por senha no localhost ----
    update_progress(85, "Ativando serviços de dados e ajustando SELinux...")
    svc_name = "postgresql"

    if distro == 'rhel':
        svc_name = detect_pg_service()
        subprocess.run(f"systemctl stop {svc_name}", shell=True, capture_output=True)

        pgdata_check = subprocess.run("ls -A /var/lib/pgsql/data", shell=True,
                                      capture_output=True, text=True)
        if not pgdata_check.stdout.strip():
            subprocess.run("chown -R postgres:postgres /var/lib/pgsql", shell=True, capture_output=True)
            if shutil.which("restorecon"):
                subprocess.run("restorecon -Rv /var/lib/pgsql", shell=True, capture_output=True)
            subprocess.run("/usr/bin/postgresql-setup --initdb", shell=True, capture_output=True)

        subprocess.run("chown -R postgres:postgres /var/lib/pgsql/data", shell=True, capture_output=True)
        subprocess.run("chmod 700 /var/lib/pgsql/data", shell=True, capture_output=True)
        subprocess.run("grep -q \"^listen_addresses\" /var/lib/pgsql/data/postgresql.conf || echo \"listen_addresses = '*'\" >> /var/lib/pgsql/data/postgresql.conf", shell=True)
