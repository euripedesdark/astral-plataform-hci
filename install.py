#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Instalador Web Unificado - Fluxo Lógico
Executa estritamente dentro do diretório do repositório Git.
Uso: sudo python3 install.py
Autossuficiente: Instala pip e flask se necessário.
Compatível com Debian, RHEL/CentOS/Alma/Rocky 10+, e Arch Linux.
Inclui abertura de firewall (exclusivo via iptables), Node.js/React, Oracle Java 21, Nginx e PostgreSQL.
Auto-desligamento ativado no final da configuração.
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

# Configurações Globais
APP_DIR = os.path.dirname(os.path.abspath(__file__))
PORT = 5000
HOST_IP = "0.0.0.0"
EXTRA_PORT = 9090

# Estado global para o stream SSE
class InstallState:
    def __init__(self):
        self.progress = 0
        self.status = "Aguardando conexão..."
        self.package_name = ""
        self.lock = threading.Lock()

state = InstallState()

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
            elif 'rhel' in content or 'fedora' in content or 'almalinux' in content or 'centos' in content or 'rocky' in content:
                return 'rhel'
            elif 'arch' in content or 'manjaro' in content:
                return 'arch'
    except FileNotFoundError:
        pass
    return 'unknown'

def detect_pg_service():
    candidates = [
        "postgresql",
        "postgresql-server",
        "postgresql-16", "postgresql-15", "postgresql-14",
        "postgresql-13", "postgresql-12",
    ]
    for name in candidates:
        r = subprocess.run(["systemctl", "cat", name], capture_output=True)
        if r.returncode == 0:
            return name
    return "postgresql"

def configure_firewall():
    ports_to_open = [22, 80, 443, 3000, PORT, 5173, 5432, EXTRA_PORT]

    print("[INFO] Exterminando firewalld/ufw para uso exclusivo do iptables...")
    subprocess.run("systemctl stop firewalld ufw 2>/dev/null || true", shell=True)
    subprocess.run("systemctl disable firewalld ufw 2>/dev/null || true", shell=True)

    print(f"[INFO] Injetando portas da aplicação no firewall: {ports_to_open}...")
    try:
        rules_changed = False
        for p in ports_to_open:
            while True:
                del_check = subprocess.run(['iptables', '-D', 'INPUT', '-p', 'tcp', '--dport', str(p), '-j', 'ACCEPT'], capture_output=True)
                if del_check.returncode != 0:
                    break

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

def ensure_flask_installed():
    try:
        import flask
        print("[OK] Flask já está instalado.")
        return True
    except ImportError:
        print("[!] Flask não encontrado. Instalando automaticamente...")
        distro = detect_distro()

        pip_install_cmd = ""
        if distro == 'debian':
            pip_install_cmd = "apt-get update && apt-get install -y python3-pip"
        elif distro == 'rhel':
            pip_install_cmd = "dnf install -y python3-pip"
        elif distro == 'arch':
            pip_install_cmd = "pacman -Sy --noconfirm python-pip"
        else:
            print("[ERRO] Distribuição não suportada para instalação automática.")
            return False

        print(f"[INFO] Instalando pip ({distro})...")
        res_pip = subprocess.run(pip_install_cmd, shell=True, capture_output=True, text=True)
        if res_pip.returncode != 0:
            print(f"[ERRO] Falha ao instalar pip: {res_pip.stderr}")
            return False

        pip_flask_cmd = "pip3 install flask --break-system-packages"
        print("[INFO] Instalando Flask via pip...")
        res_flask = subprocess.run(pip_flask_cmd, shell=True, capture_output=True, text=True)

        if res_flask.returncode != 0:
            res_flask_retry = subprocess.run(
                "pip3 install flask --break-system-packages --trusted-host pypi.org --trusted-host files.pythonhosted.org",
                shell=True, capture_output=True, text=True
            )
            if res_flask_retry.returncode != 0:
                print(f"[ERRO] Falha ao instalar Flask via pip: {res_flask_retry.stderr}")
                return False

        print("[OK] Flask instalado com sucesso via pip.")
        return True

def run_command_stream(cmd, shell=True):
    print(f"\n[SISTEMA] Executando: {cmd}")
    process = subprocess.Popen(
        cmd,
        shell=shell,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1
    )

    for line in process.stdout:
        match = re.search(r'(?:unpacking|installing|upgrading|processing)\s+([a-zA-Z0-9\-_.]+)', line, re.IGNORECASE)
        if match:
            pkg_name = match.group(1)
            with state.lock:
                state.package_name = pkg_name

    process.wait()
    return process.returncode == 0

def update_progress(percent, status_msg):
    with state.lock:
        state.progress = percent
        state.status = status_msg

def installation_thread():
    time.sleep(2)

    configure_firewall()

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

    update_progress(5, "Sincronizando repositórios do Linux...")
    run_command_stream(update_cmd)
    update_progress(15, "Repositórios sincronizados.")

    update_progress(20, "Verificando ambiente Node.js e ReactJS...")
    node_installed = shutil.which("node") or shutil.which("nodejs")
    npm_installed = shutil.which("npm")

    success_node = True
    if node_installed and npm_installed:
        print("[INFO] Node.js e NPM já estão instalados.")
    else:
        print("[INFO] Instalando pacotes base do Node.js...")
        if distro == 'debian':
            node_pkg = "nodejs npm curl"
        elif distro == 'rhel':
            node_pkg = "nodejs nodejs-npm curl"
        else:
            node_pkg = "nodejs npm curl"

        success_node = run_command_stream(f"{install_cmd_base} {node_pkg}")

    if success_node:
        if shutil.which("npm"):
            cra_installed = shutil.which("create-react-app")
            vite_installed = shutil.which("vite")

            if not (cra_installed and vite_installed):
                print("[INFO] Instalando ambiente ReactJS globalmente...")
                subprocess.run("npm install -g create-react-app vite", shell=True, capture_output=True)
    update_progress(35, "Node.js e ReactJS prontos.")

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

    update_progress(55, "Instalando o motor de banco de dados (PostgreSQL)...")
    pg_pkg = "postgresql postgresql-contrib"
    if distro == 'rhel':
        pg_pkg = "postgresql postgresql-server postgresql-contrib"

    run_command_stream(f"{install_cmd_base} {pg_pkg}")
    update_progress(65, "PostgreSQL instalado.")

    update_progress(70, "Instalando e configurando proxy Nginx...")

    nginx_installed = shutil.which("nginx")
    success_nginx = True

    if nginx_installed:
        print("[INFO] Nginx já está instalado no sistema. Pulando download do pacote.")
    else:
        success_nginx = run_command_stream(f"{install_cmd_base} nginx")

    if success_nginx:
        subprocess.run("systemctl disable --now httpd", shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run("systemctl disable --now apache2", shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

        print("[INFO] Gerando configuração avançada do Nginx via Python...")
        host_fqdn = socket.getfqdn()

        nginx_conf = f"""server {{
    listen 80;
    server_name {host_fqdn};

    location = / {{
        proxy_pass http://127.0.0.1:8081;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }}

    location /inicio {{
        proxy_pass http://127.0.0.1:8082;
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

    update_progress(85, "Ativando serviços de dados e ajustando SELinux...")

    svc_name = "postgresql"

    if distro == 'rhel':
        svc_name = detect_pg_service()
        subprocess.run(f"systemctl stop {svc_name}", shell=True, capture_output=True)

        pgdata_check = subprocess.run("ls -A /var/lib/pgsql/data", shell=True, capture_output=True, text=True)
        if not pgdata_check.stdout.strip():
            subprocess.run("chown -R postgres:postgres /var/lib/pgsql", shell=True, capture_output=True)
            if shutil.which("restorecon"):
                subprocess.run("restorecon -Rv /var/lib/pgsql", shell=True, capture_output=True)

            subprocess.run("/usr/bin/postgresql-setup --initdb", shell=True, capture_output=True)

        subprocess.run("chown -R postgres:postgres /var/lib/pgsql/data", shell=True, capture_output=True)
        subprocess.run("chmod 700 /var/lib/pgsql/data", shell=True, capture_output=True)

        subprocess.run("grep -q \"^listen_addresses\" /var/lib/pgsql/data/postgresql.conf || "
                       "echo \"listen_addresses = '*'\" >> /var/lib/pgsql/data/postgresql.conf", shell=True)
        subprocess.run("grep -q '0.0.0.0/0' /var/lib/pgsql/data/pg_hba.conf || "
                       "echo 'host    all             all             0.0.0.0/0               md5' >> /var/lib/pgsql/data/pg_hba.conf", shell=True)

        subprocess.run(f"systemctl enable {svc_name}", shell=True, capture_output=True)
        subprocess.run(f"systemctl start {svc_name}", shell=True, capture_output=True)

    elif distro == 'arch':
        if not os.path.exists("/var/lib/postgres/data/PG_VERSION"):
            subprocess.run("sudo -u postgres initdb -D /var/lib/postgres/data", shell=True, capture_output=True)
            subprocess.run("grep -q \"^listen_addresses\" /var/lib/postgres/data/postgresql.conf || "
                           "echo \"listen_addresses = '*'\" >> /var/lib/postgres/data/postgresql.conf", shell=True)
            subprocess.run("grep -q '0.0.0.0/0' /var/lib/postgres/data/pg_hba.conf || "
                           "echo 'host    all             all             0.0.0.0/0               md5' >> /var/lib/postgres/data/pg_hba.conf", shell=True)
        subprocess.run(f"systemctl enable {svc_name}", shell=True, capture_output=True)
        subprocess.run(f"systemctl start {svc_name}", shell=True, capture_output=True)
    else:
        subprocess.run(f"systemctl restart {svc_name}", shell=True, capture_output=True)

    update_progress(90, "Aguardando o serviço de banco de dados iniciar...")
    db_ready = False
    for i in range(30):
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            result = sock.connect_ex(('127.0.0.1', 5432))
            sock.close()
            if result == 0:
                db_ready = True
                break
        except Exception:
            pass
        time.sleep(1)

    if db_ready:
        update_progress(100, "Instalação concluída!")
    else:
        update_progress(100, "Falha crítica: PostgreSQL não está escutando na porta 5432.")

if __name__ == '__main__':
    if os.geteuid() != 0:
        print("ERRO: Este script deve ser executado com sudo.")
        sys.exit(1)

    if not check_internet():
        print("\n[AVISO] Conexão com a internet não detectada!")
        wan_script = os.path.join(APP_DIR, "fabric", "network-firewall", "config-wan.py")

        if os.path.exists(wan_script):
            print(f"[INFO] Delegando configuração de rede e firewall para: {wan_script}")
            subprocess.run([sys.executable, wan_script])

            if not check_internet():
                print("\n[ERRO] A internet ainda não está acessível após a configuração. Abortando.")
                sys.exit(1)
            else:
                print("\n[OK] Conectividade estabelecida com sucesso. Retomando instalação web...")
        else:
            print(f"\n[ERRO] Sem internet e script de rede auxiliar não encontrado: {wan_script}")
            sys.exit(1)

    if not ensure_flask_installed():
        print("\n[CRÍTICO] Não foi possível prosseguir sem o Flask.")
        sys.exit(1)

    # Verifica se os assets do frontend existem, se não, usa uma string embutida
    frontend_dir = os.path.join(APP_DIR, 'fabric', 'frontend')
    has_frontend = os.path.isdir(frontend_dir) and os.path.exists(os.path.join(frontend_dir, 'index.html'))

    from flask import Flask, send_from_directory, request, jsonify, Response

    app = Flask(__name__, static_folder=frontend_dir if has_frontend else None)

    @app.route('/')
    def index():
        if has_frontend:
            return send_from_directory(frontend_dir, 'index.html')

        # HTML Inline embutido para evitar erros de diretório
        html_content = """<!DOCTYPE html>
<html lang="pt-BR">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Instalador Web Unificado - Astral Platform</title>
    <style>
        :root { --primary-color: #2563eb; --success-color: #16a34a; --bg-color: #f3f4f6; --card-bg: #ffffff; --text-color: #1f2937; }
        body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; background-color: var(--bg-color); color: var(--text-color); display: flex; justify-content: center; align-items: center; min-height: 100vh; margin: 0; }
        .container { background-color: var(--card-bg); padding: 2rem; border-radius: 12px; box-shadow: 0 4px 6px -1px rgba(0,0,0,0.1); width: 100%; max-width: 500px; text-align: center; }
        h1 { color: var(--primary-color); margin-bottom: 1.5rem; font-size: 1.8rem; }
        .progress-container { background-color: #e5e7eb; border-radius: 9999px; height: 24px; width: 100%; margin: 1.5rem 0; overflow: hidden; position: relative; }
        .progress-bar { background-color: var(--primary-color); height: 100%; width: 0%; border-radius: 9999px; transition: width 0.4s ease; display: flex; align-items: center; justify-content: center; color: white; font-size: 0.75rem; font-weight: bold; }
        .progress-bar.success { background-color: var(--success-color); }
        .status-text { font-size: 0.95rem; color: #4b5563; margin-bottom: 1rem; min-height: 1.5em; }
        .package-log { font-family: 'Courier New', monospace; font-size: 0.85rem; color: #6b7280; background: #f9fafb; padding: 0.5rem; border-radius: 6px; margin-top: 0.5rem; border: 1px solid #e5e7eb; display: none; }
        .form-group { margin-bottom: 1rem; text-align: left; }
        label { display: block; margin-bottom: 0.5rem; font-weight: 600; font-size: 0.9rem; }
        input { width: 100%; padding: 0.75rem; border: 1px solid #d1d5db; border-radius: 6px; font-size: 1rem; box-sizing: border-box; }
        button { background-color: var(--primary-color); color: white; border: none; padding: 0.75rem 1.5rem; font-size: 1rem; border-radius: 6px; cursor: pointer; width: 100%; font-weight: 600; transition: background-color 0.2s; }
        button:hover { background-color: #1d4ed8; }
        button:disabled { background-color: #9ca3af; cursor: not-allowed; }
        .hidden { display: none !important; }
        .dashboard-preview { text-align: left; border: 1px solid #e5e7eb; border-radius: 8px; overflow: hidden; margin-top: 1rem; }
        .dash-header { background: #1f2937; color: white; padding: 1rem; font-weight: bold; }
        .dash-body { padding: 1rem; display: flex; gap: 1rem; }
        .sidebar { width: 30%; background: #f3f4f6; padding: 0.5rem; border-radius: 4px; font-size: 0.8rem; }
        .sidebar ul { list-style: none; padding: 0; }
        .sidebar li { margin-bottom: 0.5rem; color: #4b5563; }
        .content-area { width: 70%; background: white; border: 1px dashed #d1d5db; display: flex; align-items: center; justify-content: center; color: #9ca3af; border-radius: 4px; }
    </style>
</head>
<body>
    <div class="container">
        <div id="install-screen">
            <h1>Instalando Sistema</h1>
            <div class="status-text" id="status-text">Conectando ao servidor...</div>
            <div class="progress-container"><div class="progress-bar" id="progress-bar">0%</div></div>
            <div class="package-log" id="package-log">Instalando pacote: ...</div>
        </div>
        <div id="credential-screen" class="hidden">
            <h1>Configuração do Banco</h1>
            <p style="color: #6b7280; font-size: 0.9rem; margin-bottom: 1.5rem;">O PostgreSQL foi instalado. Defina o usuário mestre e a senha para a database <strong>astral</strong>.</p>
            <form id="setup-form">
                <div class="form-group"><label>Usuário Administrador</label><input type="text" id="username" required></div>
                <div class="form-group"><label>Senha Master</label><input type="password" id="password" required></div>
                <button type="submit" id="btn-save">Salvar e Inicializar Sistema</button>
            </form>
            <p id="form-error" style="color: #dc2626; font-size: 0.85rem; margin-top: 1rem;" class="hidden"></p>
        </div>
        <div id="success-screen" class="hidden">
            <h1 style="color: var(--success-color);">Sistema Pronto!</h1>
            <p>Configuração concluída com sucesso.</p>
            <button id="btn-finish" style="background-color: var(--success-color); margin-top: 1rem;">Concluir e Iniciar Sistema</button>
            <div class="dashboard-preview">
                <div class="dash-header">Astral Platform Dashboard</div>
                <div class="dash-body">
                    <div class="sidebar"><ul><li>📊 Visão Geral</li><li>👥 Usuários</li><li>⚙️ Configurações</li><li>🔒 Segurança</li></ul></div>
                    <div class="content-area">Área de Conteúdo Principal</div>
                </div>
            </div>
        </div>
    </div>
    <script>
        const statusText = document.getElementById('status-text');
        const progressBar = document.getElementById('progress-bar');
        const packageLog = document.getElementById('package-log');
        const installScreen = document.getElementById('install-screen');
        const credentialScreen = document.getElementById('credential-screen');
        const successScreen = document.getElementById('success-screen');
        const setupForm = document.getElementById('setup-form');
        const formError = document.getElementById('form-error');
        const btnFinish = document.getElementById('btn-finish');
        let eventSource = null;

        function connectSSE() {
            eventSource = new EventSource('/api/stream');
            eventSource.onmessage = function(event) {
                const data = JSON.parse(event.data);
                statusText.textContent = data.status;
                progressBar.style.width = data.porcentagem + '%';
                progressBar.textContent = data.porcentagem + '%';

                if (data.status.includes("Instalando pacote:") || data.status.includes("postgresql")) {
                    packageLog.style.display = 'block';
                    packageLog.textContent = data.status;
                    if (data.porcentagem >= 90 && data.status.includes("instalado")) {
                         setTimeout(() => { installScreen.classList.add('hidden'); credentialScreen.classList.remove('hidden'); }, 1000);
                    }
                }

                if (data.porcentagem === 100 && data.status === "Instalação concluída!") {
                    progressBar.classList.add('success');
                    packageLog.style.display = 'none';
                    if (eventSource) eventSource.close();
                    installScreen.classList.add('hidden');
                    credentialScreen.classList.remove('hidden');
                }
            };
        }

        setupForm.addEventListener('submit', async (e) => {
            e.preventDefault();
            const username = document.getElementById('username').value;
            const password = document.getElementById('password').value;
            const btn = document.getElementById('btn-save');
            btn.disabled = true; btn.textContent = "Processando..."; formError.classList.add('hidden');

            try {
                const response = await fetch('/api/setup-db', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username, password }) });
                const result = await response.json();

                if (response.ok && result.success) {
                    credentialScreen.classList.add('hidden');
                    successScreen.classList.remove('hidden');

                    // Botão verde: Dá shutdown e redireciona
                    btnFinish.onclick = () => {
                        fetch('/api/shutdown', { method: 'POST' }).finally(() => {
                            if (result.redirect_url) {
                                window.location.href = result.redirect_url;
                            } else {
                                window.location.reload();
                            }
                        });
                    };
                } else { throw new Error(result.error || "Falha ao criar usuário"); }
            } catch (error) {
                formError.textContent = error.message; formError.classList.remove('hidden');
                btn.disabled = false; btn.textContent = "Salvar e Inicializar Sistema";
            }
        });
        connectSSE();
    </script>
</body>
</html>"""
        return html_content

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

                    data = {
                        "porcentagem": state.progress,
                        "status": display_status,
                        "package": state.package_name
                    }

                    if state.progress >= 100:
                        data["redirect_url"] = f"http://{get_local_ip()}"

                yield f"data: {json.dumps(data)}\n\n"
                if state.progress >= 100:
                    break
                time.sleep(0.5)
        return Response(generate(), mimetype='text/event-stream')

    @app.route('/api/setup-db', methods=['POST'])
    def setup_db():
        data = request.json
        username = data.get('username')
        password = data.get('password')

        if not username or not password:
            return jsonify({"error": "Dados inválidos"}), 400

        cmd_user = f'sudo -i -u postgres psql -c "CREATE USER {username} WITH PASSWORD \'{password}\' SUPERUSER;"'
        cmd_db = f'sudo -i -u postgres psql -c "CREATE DATABASE astral OWNER {username};"'

        try:
            res_user = subprocess.run(cmd_user, shell=True, capture_output=True, text=True)
            if res_user.returncode != 0 and "already exists" not in res_user.stderr:
                return jsonify({"error": f"Erro ao criar usuário: {res_user.stderr}"}), 500

            res_db = subprocess.run(cmd_db, shell=True, capture_output=True, text=True)
            if res_db.returncode != 0 and "already exists" not in res_db.stderr:
                return jsonify({"error": f"Erro ao criar database astral: {res_db.stderr}"}), 500

            return jsonify({
                "success": True,
                "message": "Usuário e database 'astral' criados com sucesso!",
                "redirect_url": f"http://{get_local_ip()}"
            })

        except Exception as e:
            return jsonify({"error": str(e)}), 500

    @app.route('/api/shutdown', methods=['POST'])
    def shutdown():
        # Agenda o encerramento do script (os._exit) para 1 segundo após retornar o "OK" pro browser
        print("\n[INFO] Sinal de encerramento recebido. Desligando instalador web em 1s...")
        threading.Timer(1.0, lambda: os._exit(0)).start()
        return jsonify({"success": True, "message": "Desligando servidor Flask..."})

    local_ip = get_local_ip()

    print("\n" + "="*60)
    print("[GIT PROJETO] INSTALADOR WEB ATIVO NA PASTA LOCAL")
    print("="*60)
    print(f"[AÇÃO] Abra o navegador em outra máquina e acesse:")
    print(f"[ENDEREÇO] http://{local_ip}:{PORT}")
    print(f"[EXTRA]  Portas 22, 80, 443, 3000, 5000, 5173, 5432 e 9090 verificadas/liberadas.")
    print("="*60 + "\n")
    print("Aguardando conexão... (Ctrl+C para cancelar)")

    t = threading.Thread(target=installation_thread)
    t.daemon = True
    t.start()

    app.run(host=HOST_IP, port=PORT, threaded=True)
