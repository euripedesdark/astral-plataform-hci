#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Instalador Web Unificado - Fluxo Lógico em 10 Passos
Executa estritamente dentro do diretório do repositório Git.
Uso: sudo python3 install.py
Autossuficiente: Instala suas próprias dependências (Flask) se necessário.
Compatível com Debian, RHEL/CentOS/Alma 10+, e Arch Linux.
"""

import os
import sys
import socket
import subprocess
import time
import json
import threading
import re

# Configurações Globais
APP_DIR = os.path.dirname(os.path.abspath(__file__))
PORT = 5000
HOST_IP = "0.0.0.0"

# Estado global para o stream SSE
class InstallState:
    def __init__(self):
        self.progress = 0
        self.status = "Aguardando conexão..."
        self.package_name = ""
        self.clients = []
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

def ensure_flask_installed():
    """Verifica e instala o Flask se necessário."""
    try:
        import flask
        print("[OK] Flask já está instalado.")
        return True
    except ImportError:
        print("[!] Flask não encontrado. Instalando automaticamente...")
        distro = detect_distro()
        cmd = None

        if distro == 'debian':
            # Atualiza cache primeiro se possível
            subprocess.run("apt-get update", shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            cmd = "apt-get install -y python3-flask"
        elif distro == 'rhel':
            # RHEL 10/CentOS 10 usa python3-flask no AppStream
            # Tenta limpar cache se falhar, mas geralmente não precisa
            cmd = "dnf install -y python3-flask"
        elif distro == 'arch':
            cmd = "pacman -Sy --noconfirm python-flask"
        else:
            print("[ERRO] Distribuição não suportada para instalação automática do Flask.")
            print("Por favor, instale manualmente: pip3 install flask")
            return False

        try:
            # Como o script já roda com sudo, executamos direto
            result = subprocess.run(cmd, shell=True, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            print("[OK] Flask instalado com sucesso.")
            return True
        except subprocess.CalledProcessError as e:
            err_msg = e.stderr.decode() if e.stderr else str(e)
            print(f"[ERRO] Falha ao instalar Flask: {err_msg}")
            print("[SUGESTÃO] Se for RHEL/CentOS, verifique se o repositório 'AppStream' ou 'CRB' está habilitado.")
            print("[SUGESTÃO] Tente manualmente: sudo dnf install -y python3-flask")
            return False

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

    output_buffer = ""
    for line in process.stdout:
        output_buffer += line
        # Tenta extrair nome do pacote sendo instalado (padrão comum em apt/dnf/pacman)
        # Ex: "Preparing to unpack .../postgresql_12.deb" ou "Installing postgresql" ou "upgrading python3-"
        match = re.search(r'(?:unpacking|installing|upgrading|processing)\s+([a-zA-Z0-9\-_.]+)', line, re.IGNORECASE)
        if match:
            pkg_name = match.group(1)
            with state.lock:
                state.package_name = pkg_name

        # Envia para os clientes SSE imediatamente (lógica simplificada para não travar)
        # O status detalhado é lido pelo frontend via JSON

    process.wait()
    return process.returncode == 0

def update_progress(percent, status_msg):
    """Atualiza estado e notifica clientes SSE."""
    with state.lock:
        state.progress = percent
        state.status = status_msg

def installation_thread():
    """Orquestra os Passos 5 a 8."""
    time.sleep(2) # Aguarda cliente conectar

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

    # Passo 5: Sincronização (0% -> 20%)
    update_progress(5, "Sincronizando repositórios do Linux...")
    run_command_stream(update_cmd)
    update_progress(20, "Repositórios sincronizados.")

    # Passo 6: Node.js (20% -> 50%)
    update_progress(25, "Configurando ambiente Node.js...")
    node_pkg = "nodejs npm curl"
    if distro == 'arch':
        node_pkg = "nodejs npm curl" # No Arch os nomes são iguais
    run_command_stream(f"{install_cmd_base} {node_pkg}")
    update_progress(50, "Node.js instalado.")

    # Passo 7: PostgreSQL (50% -> 90%)
    update_progress(55, "Instalando o motor do banco de dados...")
    pg_pkg = "postgresql postgresql-contrib"
    if distro == 'arch':
        pg_pkg = "postgresql postgresql-contrib"

    # Executa com stream para capturar nomes dos pacotes
    success = run_command_stream(f"{install_cmd_base} {pg_pkg}")

    if success:
        update_progress(90, "PostgreSQL instalado.")
    else:
        update_progress(90, "Erro na instalação do PostgreSQL (verifique logs).")

    # Passo 8: Ativação e Validação (90% -> 100%)
    update_progress(95, "Ativando serviços...")

    svc_name = "postgresql"

    # Lógica específica para RHEL/CentOS/Alma/Rocky 10+
    if distro == 'rhel':
        svc_name = "postgresql-server"
        # No RHEL, após instalar, é necessário inicializar o DB pela primeira vez manualmente
        print("[INFO] Detectado RHEL/Fedora. Inicializando banco de dados pela primeira vez...")
        init_result = subprocess.run("postgresql-setup --initdb", shell=True, capture_output=True, text=True)
        if init_result.returncode != 0 and "is not empty" not in init_result.stderr:
            print(f"[AVISO] Falha ao inicializar DB: {init_result.stderr}")

        # Habilitar e iniciar
        subprocess.run(f"systemctl enable {svc_name}", shell=True, capture_output=True)
        subprocess.run(f"systemctl start {svc_name}", shell=True, capture_output=True)

    elif distro == 'arch':
        # No Arch, às vezes precisa inicializar manualmente se não for systemd automático
        subprocess.run(f"systemctl enable {svc_name}", shell=True, capture_output=True)
        subprocess.run(f"systemctl start {svc_name}", shell=True, capture_output=True)

    else:
        # Debian/Ubuntu geralmente iniciam sozinhos
        subprocess.run(f"systemctl restart {svc_name}", shell=True, capture_output=True)

    # Validação do Socket
    db_ready = False
    print("[INFO] Aguardando PostgreSQL aceitar conexões na porta 5432...")
    for i in range(20): # Aumenta tentativas para 20s
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            result = sock.connect_ex(('127.0.0.1', 5432))
            sock.close()
            if result == 0:
                db_ready = True
                print(f"[OK] PostgreSQL respondendo após {i+1} segundos.")
                break
        except Exception as e:
            pass
        time.sleep(1)

    if db_ready:
        update_progress(100, "Instalação concluída!")
    else:
        update_progress(100, "Instalação finalizada (serviço pode estar iniciando lentamente).")

# Importação tardia do Flask após verificação
if not ensure_flask_installed():
    print("\n[CRÍTICO] Não foi possível prosseguir sem o Flask.")
    sys.exit(1)

from flask import Flask, send_from_directory, request, jsonify, Response

# Configuração do caminho para a pasta frontend dentro de fabric
app = Flask(__name__, static_folder='fabric/frontend', static_url_path='')

@app.route('/')
def index():
    """Passo 4: Serve o HTML estático."""
    return send_from_directory('fabric/frontend', 'index.html')

@app.route('/api/stream')
def stream():
    """Passo 4 & 5+: Handshake SSE e envio de progresso."""
    def generate():
        last_pkg = ""
        while True:
            with state.lock:
                # Se tiver um nome de pacote sendo processado, adiciona ao status
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
    """Passo 10: Criação do Superuser e Database Astral via Login Shell."""
    data = request.json
    username = data.get('username')
    password = data.get('password')

    if not username or not password:
        return jsonify({"error": "Dados inválidos"}), 400

    # Comandos seguros usando login shell do usuário postgres
    # 1. Criar Usuário
    cmd_user = f'sudo -i -u postgres psql -c "CREATE USER {username} WITH PASSWORD \'{password}\' SUPERUSER;"'
    # 2. Criar Database Astral
    cmd_db = f'sudo -i -u postgres psql -c "CREATE DATABASE astral OWNER {username};"'

    try:
        # Executa criação do usuário
        res_user = subprocess.run(cmd_user, shell=True, capture_output=True, text=True)
        if res_user.returncode != 0 and "already exists" not in res_user.stderr:
            return jsonify({"error": f"Erro ao criar usuário: {res_user.stderr}"}), 500

        # Executa criação da database
        res_db = subprocess.run(cmd_db, shell=True, capture_output=True, text=True)
        if res_db.returncode != 0 and "already exists" not in res_db.stderr:
            return jsonify({"error": f"Erro ao criar database astral: {res_db.stderr}"}), 500

        return jsonify({"success": True, "message": "Usuário e database 'astral' criados com sucesso!"})

    except Exception as e:
        return jsonify({"error": str(e)}), 500

if __name__ == '__main__':
    # Passo 1: Verificação de Root
    if os.geteuid() != 0:
        print("ERRO: Este script deve ser executado com sudo.")
        print("Uso correto: sudo python3 install.py")
        sys.exit(1)

    # Passo 2: Captura IP
    local_ip = get_local_ip()

    # Passo 3: Sinalização Visual no Terminal
    print("\n" + "="*60)
    print("[GIT PROJETO] INSTALADOR WEB ATIVO NA PASTA LOCAL")
    print("="*60)
    print(f"[AÇÃO] Abra o navegador em outra máquina e acesse:")
    print(f"[ENDEREÇO] http://{local_ip}:{PORT}")
    print("="*60 + "\n")
    print("Aguardando conexão... (Ctrl+C para cancelar)")

    # Inicia thread de instalação em background
    t = threading.Thread(target=installation_thread)
    t.daemon = True
    t.start()

    # Inicia Servidor Flask
    app.run(host=HOST_IP, port=PORT, threaded=True)
