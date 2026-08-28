#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Instalador Web Unificado - Fluxo Lógico em 10 Passos
Executa estritamente dentro do diretório do repositório Git.
Uso: sudo python3 install.py
Autossuficiente: Instala pip e flask se necessário.
Compatível com Debian, RHEL/CentOS/Alma/Rocky 10+, e Arch Linux.
Inclui abertura automática de firewall e configuração SELinux/PostgreSQL.

CORREÇÕES APLICADAS (v2):
- RHEL/Fedora/Rocky: a unit correta do systemd é "postgresql" (não "postgresql-server").
- Detecção automática da unit (postgresql, postgresql-16, etc. via systemctl cat).
- Erros de systemctl não são mais silenciados (stderr + journalctl visíveis).
- listen_addresses/pg_hba aplicados de forma idempotente mesmo se o PGDATA já existir.
- Permissões do PGDATA corrigidas (chown postgres / chmod 700) antes do start.
- Node.js, NPM e Curl restaurados no fluxo de instalação.
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
    """CORREÇÃO: Descobre o nome real da unit do PostgreSQL neste sistema.
    Em RHEL/Fedora/Rocky/Alma a unit é 'postgresql'; com repo PGDG pode ser
    'postgresql-15'/'postgresql-16' etc."""
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
    ports_to_open = [22, PORT, 5432, EXTRA_PORT]
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
            # Loop forçando a exclusão da regra caso exista múltiplas vezes
            while True:
                del_check = subprocess.run(['iptables', '-D', 'INPUT', '-p', 'tcp', '--dport', str(p), '-j', 'ACCEPT'], capture_output=True)
                if del_check.returncode != 0:
                    break

            # Insere a regra de forma limpa e única no topo
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
    """Executa comando capturando saída em tempo real para o stream."""
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
    """Orquestra os Passos 5 a 8."""
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
    update_progress(20, "Repositórios sincronizados.")

    update_progress(25, "Configurando ambiente Node.js...")
    node_pkg = "nodejs npm curl"
    run_command_stream(f"{install_cmd_base} {node_pkg}")
    update_progress(50, "Node.js instalado.")

    update_progress(55, "Instalando o motor do banco de dados...")
    pg_pkg = "postgresql postgresql-contrib"
    if distro == 'rhel':
        pg_pkg = "postgresql postgresql-server postgresql-contrib"

    success = run_command_stream(f"{install_cmd_base} {pg_pkg}")

    if success:
        update_progress(90, "PostgreSQL instalado.")
    else:
        update_progress(90, "Erro na instalação do PostgreSQL (verifique logs).")

    update_progress(95, "Ativando serviços e domando o SELinux...")

    svc_name = "postgresql"

    # Correção robusta para inicialização no RHEL/Fedora com SELinux e Bind de Porta
    if distro == 'rhel':
        # CORREÇÃO: a unit no RHEL/Fedora/Rocky é "postgresql", não "postgresql-server"
        svc_name = detect_pg_service()
        print(f"[INFO] Detectado RHEL/Fedora. Serviço identificado: {svc_name}")
        subprocess.run(f"systemctl stop {svc_name}", shell=True, capture_output=True)

        pgdata_check = subprocess.run("ls -A /var/lib/pgsql/data", shell=True, capture_output=True, text=True)
        if not pgdata_check.stdout.strip():
            print("[INFO] Inicializando diretório PGDATA...")

            # Acerta permissões
            subprocess.run("chown -R postgres:postgres /var/lib/pgsql", shell=True, capture_output=True)

            # ====================================================
            # CORREÇÃO SELINUX: Restaura contexto de segurança
            # ====================================================
            if shutil.which("restorecon"):
                print("[INFO] Aplicando restorecon para o SELinux na pasta do PostgreSQL...")
                subprocess.run("restorecon -Rv /var/lib/pgsql", shell=True, capture_output=True)

            init_res = subprocess.run("/usr/bin/postgresql-setup --initdb", shell=True, capture_output=True, text=True)
            if init_res.returncode != 0:
                print(f"[ERRO] Falha no initdb: {init_res.stderr}")
        else:
            print("[INFO] Diretório PGDATA já possui arquivos. Pulando initdb.")

        # CORREÇÃO: garante dono/permissão corretos do data directory antes do start
        subprocess.run("chown -R postgres:postgres /var/lib/pgsql/data", shell=True, capture_output=True)
        subprocess.run("chmod 700 /var/lib/pgsql/data", shell=True, capture_output=True)

        # ====================================================
        # CORREÇÃO NETWORK: idempotente (grep || echo), roda mesmo se PGDATA já existir
        # ====================================================
        print("[INFO] Configurando banco para escutar em todas as interfaces...")
        subprocess.run("grep -q \"^listen_addresses\" /var/lib/pgsql/data/postgresql.conf || "
                       "echo \"listen_addresses = '*'\" >> /var/lib/pgsql/data/postgresql.conf", shell=True)
        subprocess.run("grep -q '0.0.0.0/0' /var/lib/pgsql/data/pg_hba.conf || "
                       "echo 'host    all             all             0.0.0.0/0               md5' >> /var/lib/pgsql/data/pg_hba.conf", shell=True)

        r = subprocess.run(f"systemctl enable {svc_name}", shell=True, capture_output=True, text=True)
        if r.returncode != 0:
            print(f"[ERRO] systemctl enable {svc_name} falhou: {r.stderr}")

        # CORREÇÃO: não silenciar mais o erro de start (era aqui que o bug se escondia)
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
        # Debian/Ubuntu config paths are different, usually handled automatically during install
        r = subprocess.run(f"systemctl restart {svc_name}", shell=True, capture_output=True, text=True)
        if r.returncode != 0:
            print(f"[ERRO] Falha ao reiniciar {svc_name}: {r.stderr}")

    # Validação do Socket
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

        return jsonify({"success": True, "message": "Usuário e database 'astral' criados com sucesso!"})

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
    print(f"[EXTRA]  Portas 22, 5432 e 9090 também foram verificadas/liberadas.")
    print("="*60 + "\n")
    print("Aguardando conexão... (Ctrl+C para cancelar)")

    t = threading.Thread(target=installation_thread)
    t.daemon = True
    t.start()

    app.run(host=HOST_IP, port=PORT, threaded=True)
