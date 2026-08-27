#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Instalador Web Unificado - Fluxo Lógico em 10 Passos
Executa estritamente dentro do diretório do repositório Git.
Uso: sudo python3 instalador.py
"""

import os
import sys
import socket
import subprocess
import time
import json
import threading
import re
from flask import Flask, send_from_directory, request, jsonify, Response

# Configurações Globais
APP_DIR = os.path.dirname(os.path.abspath(__file__))
# ATENÇÃO: Caminho atualizado para a pasta dentro de 'fabric'
FRONTEND_PATH = os.path.join(APP_DIR, 'fabric', 'frontend')
PORT = 5000
HOST_IP = "0.0.0.0"

# Estado global para o stream SSE
class InstallState:
    def __init__(self):
        self.progress = 0
        self.status = "Aguardando conexão..."
        self.current_package = ""
        self.clients = []
        self.lock = threading.Lock()

state = InstallState()
app = Flask(__name__, static_folder=FRONTEND_PATH, static_url_path='')

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
    """Detecta a distribuição Linux e retorna família e gerenciador de pacotes."""
    try:
        with open('/etc/os-release', 'r') as f:
            content = f.read()

        if 'ID_LIKE=' in content:
            ids = re.search(r'ID_LIKE="?([^"\n]+)"?', content)
            if ids:
                ids = ids.group(1).split()

        id_line = re.search(r'^ID="?([^"\n]+)"?', content, re.MULTILINE)
        distro_id = id_line.group(1) if id_line else ""

        if 'debian' in content or 'ubuntu' in content or distro_id in ['debian', 'ubuntu', 'linuxmint']:
            return 'DEBIAN', 'apt-get'
        elif 'rhel' in content or 'fedora' in content or 'almalinux' in content or 'centos' in content or distro_id in ['fedora', 'rhel', 'centos', 'almalinux', 'rocky']:
            return 'RHEL', 'dnf'
        elif 'arch' in content or distro_id == 'arch':
            return 'ARCH', 'pacman'
        else:
            # Fallback padrão
            return 'DEBIAN', 'apt-get'

    except Exception:
        return 'DEBIAN', 'apt-get'

def run_command_stream(cmd, shell=True):
    """Executa comando e captura saída em tempo real para streaming."""
    process = subprocess.Popen(
        cmd,
        shell=shell,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1
    )

    output_lines = []
    for line in iter(process.stdout.readline, ''):
        if line:
            clean_line = line.strip()
            output_lines.append(clean_line)
            # Tenta identificar pacote sendo instalado (padrão apt/dnf/pacman)
            pkg_match = re.search(r'(Unpacking|Installing|Processing)\s+([a-zA-Z0-9\-\.]+)', line, re.IGNORECASE)
            if pkg_match:
                pkg_name = pkg_match.group(2)
                with state.lock:
                    state.current_package = pkg_name
                    state.status = f"Instalando pacote: {pkg_name}..."

            with state.lock:
                # Atualiza status genérico se não houver pacote específico
                if not state.current_package:
                    state.status = clean_line[:60] # Limita tamanho

            time.sleep(0.1) # Pequena pausa para não saturar o stream

    process.wait()
    return process.returncode == 0

def update_progress(percent, status_msg):
    """Atualiza estado e notifica clientes SSE."""
    with state.lock:
        state.progress = percent
        if not state.current_package:
            state.status = status_msg

def installation_thread():
    """Orquestra os Passos 5 a 8 com detecção de distro."""
    time.sleep(2)

    distro_family, pkg_mgr = detect_distro()
    print(f"Distro detectada: {distro_family} ({pkg_mgr})")

    # Comandos baseados na distro
    if distro_family == 'DEBIAN':
        cmd_update = f"{pkg_mgr} update -qq"
        cmd_node = f"{pkg_mgr} install -y nodejs npm curl"
        cmd_pg = f"{pkg_mgr} install -y postgresql postgresql-contrib"
        svc_pg = "postgresql"
    elif distro_family == 'RHEL':
        cmd_update = f"{pkg_mgr} makecache -q"
        cmd_node = f"{pkg_mgr} install -y nodejs npm curl --quiet"
        cmd_pg = f"{pkg_mgr} install -y postgresql postgresql-server --quiet"
        svc_pg = "postgresql" # ou postgresql-setup
    else: # ARCH
        cmd_update = f"{pkg_mgr} -Sy --noconfirm"
        cmd_node = f"{pkg_mgr} -S --noconfirm nodejs npm curl"
        cmd_pg = f"{pkg_mgr} -S --noconfirm postgresql"
        svc_pg = "postgresql"

    # Passo 5: Sincronização (0% -> 20%)
    update_progress(5, "Sincronizando repositórios do Linux...")
    run_command_stream(cmd_update)
    update_progress(20, "Repositórios sincronizados.")

    # Passo 6: Node.js (20% -> 50%)
    update_progress(25, "Configurando ambiente Node.js...")
    run_command_stream(cmd_node)
    update_progress(50, "Node.js instalado.")

    # Passo 7: PostgreSQL (50% -> 90%) - COM STREAMING REAL
    update_progress(55, "Iniciando instalação do PostgreSQL...")
    state.current_package = "" # Reset
    success = run_command_stream(cmd_pg)

    if not success:
        update_progress(90, "Erro na instalação do PostgreSQL (verifique logs).")
    else:
        update_progress(90, "PostgreSQL instalado.")

    # Passo 8: Ativação e Validação (90% -> 100%)
    update_progress(95, "Ativando serviços...")

    # Tentativa de start do serviço (pode variar o nome exato dependendo da distro)
    run_command_stream(f"systemctl start {svc_pg}", shell=True)
    # Para RHEL as vezes precisa initdb
    if distro_family == 'RHEL':
        run_command_stream("postgresql-setup --initdb", shell=True)
        run_command_stream("systemctl enable postgresql", shell=True)

    db_ready = False
    for _ in range(10):
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            result = sock.connect_ex(('127.0.0.1', 5432))
            sock.close()
            if result == 0:
                db_ready = True
                break
        except:
            pass
        time.sleep(1)

    if db_ready:
        update_progress(100, "Instalação concluída!")
    else:
        update_progress(100, "Instalação finalizada (serviço pode estar iniciando).")

@app.route('/')
def index():
    """Passo 4: Serve o HTML estático da nova localização."""
    return send_from_directory('fabric/frontend', 'index.html')

@app.route('/api/stream')
def stream():
    """Passo 4 & 5+: Handshake SSE e envio de progresso."""
    def generate():
        while True:
            with state.lock:
                data = {
                    "porcentagem": state.progress,
                    "status": state.status,
                    "pacote_atual": state.current_package
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

    # 1. Criar Usuário
    cmd_user = f'sudo -i -u postgres psql -c "CREATE USER {username} WITH PASSWORD \'{password}\' SUPERUSER;"'

    # 2. Criar Database Astral
    cmd_db = f'sudo -i -u postgres psql -c "CREATE DATABASE astral OWNER {username};"'

    try:
        # Executa Usuário
        res_user = subprocess.run(cmd_user, shell=True, capture_output=True, text=True)
        if res_user.returncode != 0 and "already exists" not in res_user.stderr:
            return jsonify({"error": res_user.stderr}), 500

        # Executa Database
        res_db = subprocess.run(cmd_db, shell=True, capture_output=True, text=True)
        if res_db.returncode != 0 and "already exists" not in res_db.stderr:
            return jsonify({"error": res_db.stderr}), 500

        return jsonify({"success": True, "message": "Usuário e Database 'astral' criados!"})

    except Exception as e:
        return jsonify({"error": str(e)}), 500

if __name__ == '__main__':
    # Passo 1: Verificação de Root
    if os.geteuid() != 0:
        print("ERRO: Este script deve ser executado com sudo.")
        print("Uso correto: sudo python3 instalador.py")
        sys.exit(1)

    # Verifica se a pasta frontend existe no novo caminho
    if not os.path.exists(FRONTEND_PATH):
        print(f"ERRO: Pasta frontend não encontrada em {FRONTEND_PATH}")
        print("Certifique-se de mover a pasta frontend para dentro de fabric/")
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
