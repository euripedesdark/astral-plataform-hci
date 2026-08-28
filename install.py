#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Instalador Web Unificado - Fluxo Lógico
Executa estritamente dentro do diretório do repositório Git.
Uso: sudo python3 install.py
Autossuficiente: Instala pip e flask se necessário.
Compatível com Debian, RHEL/CentOS/Alma/Rocky 10+, e Arch Linux.
Inclui abertura de firewall (exclusivo via iptables), Node.js/React, Oracle Java 21, Nginx e PostgreSQL.
Configura Nginx (default_server) para servir index.html (Login) e Flask serve install.html (Instalador).
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

        # Elimina a escuta IPv6 do arquivo padrão de fábrica para evitar o erro 97
        subprocess.run("sed -i 's/.*listen.*\\[::\\]:80.*/#&/' /etc/nginx/nginx.conf 2>/dev/null", shell=True)

        print("[INFO] Gerando configuração avançada do Nginx via Python...")
        frontend_path = os.path.join(APP_DIR, "fabric", "frontend")

        # Garante a permissão de travessia do Linux (DAC) para o Nginx chegar até o /home/user/...
        current_path = frontend_path
        while current_path != '/':
            subprocess.run(f"chmod o+x {current_path} 2>/dev/null", shell=True)
            current_path = os.path.dirname(current_path)

        # Ajuste de Permissões SELinux para RHEL
        if distro == 'rhel':
            subprocess.run("setsebool -P httpd_can_network_connect 1 2>/dev/null", shell=True)
            subprocess.run(f"chcon -Rt httpd_sys_content_t {frontend_path} 2>/dev/null", shell=True)

        # Ajuste de Permissões AppArmor para Debian/Ubuntu
        if distro == 'debian':
            aa_profile = "/etc/apparmor.d/usr.sbin.nginx"
            aa_override = "/etc/apparmor.d/local/usr.sbin.nginx"
            if os.path.exists(aa_profile):
                print("[INFO] Ajustando AppArmor para permitir leitura do frontend pelo Nginx...")
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
                    print(f"[AVISO] Falha ao ajustar regras do AppArmor: {e}")

        # Nginx configurado para buscar o index.html (Login)
        nginx_conf = f"""server {{
    listen 80 default_server;
    server_name _;

    # Servir arquivos estáticos do frontend nativamente (HTML/CSS/JS/Imagens)
    root {frontend_path};
    index index.html;

    # Se acessar a raiz, entrega o arquivo e impede o erro de loop 500
    location / {{
        try_files $uri $uri/ =404;
    }}

    # Requisições de API vão para o backend Spring Boot (ainda desligado)
    location /api/ {{
        proxy_pass http://127.0.0.1:8081;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }}

    # Tela inicial (dashboard Astral Platform)
    location /inicio {{
        proxy_pass http://127.0.0.1:8082;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }}

    # DNS Management
    location /dns {{
        proxy_pass http://127.0.0.1:8053;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }}

    # Sublocação Pi-hole dentro de DNS
    location /dns/pihole {{
        proxy_pass http://127.0.0.1:8081/admin;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }}

    # Firewall
    location /firewall {{
        proxy_pass http://127.0.0.1:8040;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }}

    # Proxy System
    location /proxy {{
        proxy_pass http://127.0.0.1:8085;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }}

    # Domain Controllers
    location /domain {{
        proxy_pass http://127.0.0.1:8090;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }}

    # PostgreSQL Admin
    location /postgres {{
        proxy_pass http://127.0.0.1:5433;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }}

    # Web Server Admin
    location /web {{
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }}

    # Virtual Machines
    location /vm {{
        proxy_pass http://127.0.0.1:8070;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }}

    # Storage
    location /storage {{
        proxy_pass http://127.0.0.1:8060;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }}

    # Network Config & VLAN
    location /network {{
        proxy_pass http://127.0.0.1:8024;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }}

    # System Alerts
    location /alerts {{
        proxy_pass http://127.0.0.1:8010;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }}

    # Database Telemetry
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
        print("[OK] Nginx inicializado e configurado (Estático + Proxy).")

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

    frontend_dir = os.path.join(APP_DIR, 'fabric', 'frontend')

    from flask import Flask, send_from_directory, request, jsonify, Response

    app = Flask(__name__, static_folder=frontend_dir, static_url_path='')

    @app.route('/')
    def index():
        # O Flask serve especificamente o arquivo install.html
        return send_from_directory(frontend_dir, 'install.html')

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

            print("\n[INFO] Banco de dados configurado! Agendando encerramento do instalador para 1 minuto...")
            threading.Timer(60.0, lambda: os._exit(0)).start()

            return jsonify({
                "success": True,
                "message": "Usuário e database 'astral' criados com sucesso!",
                "redirect_url": f"http://{get_local_ip()}"
            })

        except Exception as e:
            return jsonify({"error": str(e)}), 500

    @app.route('/api/shutdown', methods=['POST'])
    def shutdown():
        print("\n[INFO] Sinal de encerramento manual recebido. Desligando...")
        threading.Timer(1.0, lambda: os._exit(0)).start()
        return jsonify({"success": True})

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
