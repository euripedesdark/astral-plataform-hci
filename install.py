#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Instalador Web Unificado - Fluxo Lógico em 10 Passos
Executa estritamente dentro do diretório do repositório Git.
Uso: sudo python3 install.py
Autossuficiente: Instala pip e flask se necessário.
Compatível com Debian, RHEL/CentOS/Alma/Rocky 10+, e Arch Linux.
Inclui abertura automática de firewall, Node.js/React, Nginx e configuração PostgreSQL.
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

def get_local_ip():
    """Passo 2: Captura o IP Real da máquina."""
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
    """Detecta a família da distribuição Linux."""
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
    """Descobre o nome real da unit do PostgreSQL neste sistema."""
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
    """Abre as portas essenciais no firewall limpando regras antigas duplicadas."""
    ports_to_open = [22, 80, 443, 3000, PORT, 5173, 5432, EXTRA_PORT]
    print(f"[INFO] Verificando firewall para as portas {ports_to_open}...")

    try:
        result = subprocess.run(['systemctl', 'is-active', '--quiet', 'firewalld'])
        if result.returncode == 0:
            print("[INFO] Firewalld detectado. Analisando regras...")
            needs_reload = False
            for p in ports_to_open:
                check = subprocess.run(['firewall-cmd', '--query-port', f'{p}/tcp'], capture_output=True)
                if check.returncode != 0:
                    subprocess.run(['firewall-cmd', '--permanent', '--add-port', f'{p}/tcp'], check=True, capture_output=True)
                    needs_reload = True

            if needs_reload:
                subprocess.run(['firewall-cmd', '--reload'], check=True, capture_output=True)
            print("[OK] Firewalld configurado.")
            return
    except Exception:
        pass

    print("[INFO] Configurando via iptables (limpando duplicidades)...")
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
        print("[OK] Verificação do iptables concluída e regras aplicadas limpas.")
    except Exception as e:
        print(f"[AVISO] Falha ao configurar firewall automaticamente: {e}")

def ensure_flask_installed():
    """Verifica e instala o Flask se necessário."""
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
            print("[AVISO] Tentando com flags alternativas...")
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
    """Executa comando capturando saída em tempo real para o stream E imprime no terminal."""
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
    """Atualiza estado e notifica clientes SSE."""
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

    # Passo: Sincronização
    update_progress(5, "Sincronizando repositórios do Linux...")
    run_command_stream(update_cmd)
    update_progress(20, "Repositórios sincronizados.")

    # Passo: Instalação Node.js, NPM e React
    update_progress(25, "Verificando ambiente Node.js e ReactJS...")

    node_installed = shutil.which("node") or shutil.which("nodejs")
    npm_installed = shutil.which("npm")

    success_node = True
    if node_installed and npm_installed:
        print("[INFO] Node.js e NPM já estão instalados. Pulando instalação do pacote base.")
    else:
        print("[INFO] Node.js e/ou NPM não encontrados. Instalando pacotes base...")
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

            if cra_installed and vite_installed:
                print("[INFO] Pacotes ReactJS e Vite globais já instalados. Pulando npm install.")
            else:
                print("[INFO] NPM detectado. Instalando ambiente ReactJS globalmente...")
                npm_res = subprocess.run("npm install -g create-react-app vite", shell=True, capture_output=True, text=True)
                if npm_res.returncode == 0:
                    print("[OK] ReactJS, Vite e Node.js verificados/instalados com sucesso.")
                else:
                    print(f"[AVISO] NPM falhou ao instalar pacotes globais: {npm_res.stderr}")
        else:
            print("[AVISO] Binário do NPM não encontrado no sistema após a instalação do pacote.")
    else:
        print("[ERRO] Falha ao instalar o pacote Node.js e NPM. Verifique os repositórios do SO.")

    update_progress(45, "Node.js e ReactJS processados.")

    # Passo: PostgreSQL
    update_progress(50, "Instalando o motor do banco de dados (PostgreSQL)...")
    pg_pkg = "postgresql postgresql-contrib"
    if distro == 'rhel':
        pg_pkg = "postgresql postgresql-server postgresql-contrib"

    success_pg = run_command_stream(f"{install_cmd_base} {pg_pkg}")

    if success_pg:
        update_progress(65, "PostgreSQL instalado.")
    else:
        update_progress(65, "Erro na instalação do PostgreSQL (verifique logs).")

    # Passo: Nginx
    update_progress(70, "Instalando servidor web Nginx...")
    success_nginx = run_command_stream(f"{install_cmd_base} nginx")

    if success_nginx:
        # Previne que o Apache nativo dê conflito com o Nginx na porta 80
        subprocess.run("systemctl disable --now httpd", shell=True, capture_output=True, stderr=subprocess.DEVNULL)
        subprocess.run("systemctl disable --now apache2", shell=True, capture_output=True, stderr=subprocess.DEVNULL)

        r_en = subprocess.run("systemctl enable nginx", shell=True, capture_output=True, text=True)
        r_st = subprocess.run("systemctl start nginx", shell=True, capture_output=True, text=True)
        if r_st.returncode == 0:
            print("[OK] Nginx instalado, habilitado e iniciado com sucesso.")
            update_progress(75, "Nginx instalado e iniciado.")
        else:
            print(f"[ERRO] Falha ao iniciar Nginx: {r_st.stderr}")
            log = subprocess.run("journalctl -u nginx --no-pager -n 20", shell=True, capture_output=True, text=True)
            print(log.stdout)
            update_progress(75, "Nginx instalado (falha no start).")
    else:
        print("[ERRO] Falha ao instalar Nginx.")
        update_progress(75, "Erro na instalação do Nginx.")

    # Passo: Configuração do Banco e SELinux
    update_progress(80, "Ativando serviços e domando o SELinux...")

    svc_name = "postgresql"

    if distro == 'rhel':
        svc_name = detect_pg_service()
        print(f"[INFO] Detectado RHEL/Fedora. Serviço identificado: {svc_name}")
        subprocess.run(f"systemctl stop {svc_name}", shell=True, capture_output=True)

        pgdata_check = subprocess.run("ls -A /var/lib/pgsql/data", shell=True, capture_output=True, text=True)
        if not pgdata_check.stdout.strip():
            print("[INFO] Inicializando diretório PGDATA...")
            subprocess.run("chown -R postgres:postgres /var/lib/pgsql", shell=True, capture_output=True)

            if shutil.which("restorecon"):
                print("[INFO] Aplicando restorecon para o SELinux na pasta do PostgreSQL...")
                subprocess.run("restorecon -Rv /var/lib/pgsql", shell=True, capture_output=True)

            init_res = subprocess.run("/usr/bin/postgresql-setup --initdb", shell=True, capture_output=True, text=True)
            if init_res.returncode != 0:
                print(f"[ERRO] Falha no initdb: {init_res.stderr}")
        else:
            print("[INFO] Diretório PGDATA já possui arquivos. Pulando initdb.")

        subprocess.run("chown -R postgres:postgres /var/lib/pgsql/data", shell=True, capture_output=True)
        subprocess.run("chmod 700 /var/lib/pgsql/data", shell=True, capture_output=True)

        print("[INFO] Configurando banco para escutar em todas as interfaces...")
        subprocess.run("grep -q \"^listen_addresses\" /var/lib/pgsql/data/postgresql.conf || "
                       "echo \"listen_addresses = '*'\" >> /var/lib/pgsql/data/postgresql.conf", shell=True)
        subprocess.run("grep -q '0.0.0.0/0' /var/lib/pgsql/data/pg_hba.conf || "
                       "echo 'host    all             all             0.0.0.0/0               md5' >> /var/lib/pgsql/data/pg_hba.conf", shell=True)

        r = subprocess.run(f"systemctl enable {svc_name}", shell=True, capture_output=True, text=True)
        if r.returncode != 0:
            print(f"[ERRO] systemctl enable {svc_name} falhou: {r.stderr}")

        r = subprocess.run(f"systemctl start {svc_name}", shell=True, capture_output=True, text=True)
        if r.returncode != 0:
            print(f"[ERRO] Falha ao iniciar {svc_name}: {r.stderr}")
            log = subprocess.run(f"journalctl -u {svc_name} --no-pager -n 40", shell=True, capture_output=True, text=True)
            print(log.stdout)
    elif distro == 'arch':
        if not os.path.exists("/var/lib/postgres/data/PG_VERSION"):
            subprocess.run("sudo -u postgres initdb -D /var/lib/postgres/data", shell=True, capture_output=True)
            subprocess.run("grep -q \"^listen_addresses\" /var/lib/postgres/data/postgresql.conf || "
                           "echo \"listen_addresses = '*'\" >> /var/lib/postgres/data/postgresql.conf", shell=True)
            subprocess.run("grep -q '0.0.0.0/0' /var/lib/postgres/data/pg_hba.conf || "
                           "echo 'host    all             all             0.0.0.0/0               md5' >> /var/lib/postgres/data/pg_hba.conf", shell=True)
        r = subprocess.run(f"systemctl enable {svc_name}", shell=True, capture_output=True, text=True)
        if r.returncode != 0:
            print(f"[ERRO] systemctl enable {svc_name} falhou: {r.stderr}")
        r = subprocess.run(f"systemctl start {svc_name}", shell=True, capture_output=True, text=True)
        if r.returncode != 0:
            print(f"[ERRO] Falha ao iniciar {svc_name}: {r.stderr}")
            log = subprocess.run(f"journalctl -u {svc_name} --no-pager -n 40", shell=True, capture_output=True, text=True)
            print(log.stdout)
    else:
        r = subprocess.run(f"systemctl restart {svc_name}", shell=True, capture_output=True, text=True)
        if r.returncode != 0:
            print(f"[ERRO] Falha ao reiniciar {svc_name}: {r.stderr}")

    # Validação do Socket
    update_progress(90, "Aguardando o serviço de banco de dados...")
    db_ready = False
    print("[INFO] Aguardando PostgreSQL aceitar conexões na porta 5432...")
    for i in range(30):
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            result = sock.connect_ex(('127.0.0.1', 5432))
            sock.close()
            if result == 0:
                db_ready = True
                print(f"[OK] PostgreSQL respondendo após {i+1} segundos.")
                break
        except Exception:
            pass
        time.sleep(1)

    if db_ready:
        update_progress(100, "Instalação concluída!")
    else:
        update_progress(100, "Falha crítica: PostgreSQL não está escutando na porta 5432.")

if not ensure_flask_installed():
    print("\n[CRÍTICO] Não foi possível prosseguir sem o Flask.")
    sys.exit(1)

from flask import Flask, send_from_directory, request, jsonify, Response

app = Flask(__name__, static_folder='fabric/frontend', static_url_path='')

@app.route('/')
def index():
    return send_from_directory('fabric/frontend', 'index.html')

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

                # Envia URL de redirecionamento para o HTTPS (porta 443) ao finalizar
                if state.progress >= 100:
                    data["redirect_url"] = f"https://{get_local_ip()}"

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

        # Inclui a URL de redirecionamento também nesta rota, caso o frontend use daqui
        return jsonify({
            "success": True,
            "message": "Usuário e database 'astral' criados com sucesso!",
            "redirect_url": f"https://{get_local_ip()}"
        })

    except Exception as e:
        return jsonify({"error": str(e)}), 500

if __name__ == '__main__':
    if os.geteuid() != 0:
        print("ERRO: Este script deve ser executado com sudo.")
        print("Uso correto: sudo python3 install.py")
        sys.exit(1)

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
