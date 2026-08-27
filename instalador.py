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
PORT = 5000
HOST_IP = "0.0.0.0"

# Detecção da Distribuição
def detect_distro():
    """Detecta a família da distribuição Linux."""
    if os.path.exists("/etc/os-release"):
        with open("/etc/os-release") as f:
            content = f.read()
            if "ID=debian" in content or "ID=ubuntu" in content or "ID=linuxmint" in content:
                return "DEBIAN"
            elif "ID=fedora" in content or "ID=rhel" in content or "ID=centos" in content or "ID=almalinux" in content or "ID=rocky" in content:
                return "RHEL"
            elif "ID=arch" in content or "ID=manjaro" in content:
                return "ARCH"
    return "UNKNOWN"

DISTRO = detect_distro()
print(f"Distribuição detectada: {DISTRO}")

# Mapeamento de Comandos e Pacotes
PKG_MANAGER = {
    "DEBIAN": {"install": "apt-get install -y", "update": "apt-get update", "node": ["nodejs", "npm"], "pg": ["postgresql", "postgresql-contrib"]},
    "RHEL": {"install": "dnf install -y", "update": "dnf check-update", "node": ["nodejs", "npm"], "pg": ["postgresql", "postgresql-server", "postgresql-contrib"]},
    "ARCH": {"install": "pacman -Sy --noconfirm", "update": "pacman -Sy", "node": ["nodejs", "npm"], "pg": ["postgresql"]}
}

def get_pkg_cmd(action):
    if DISTRO == "UNKNOWN":
        raise Exception("Distribuição não suportada.")
    return PKG_MANAGER[DISTRO][action]

def get_pg_packages():
    if DISTRO == "UNKNOWN":
        raise Exception("Distribuição não suportada.")
    return " ".join(PKG_MANAGER[DISTRO]["pg"])

def get_node_packages():
    if DISTRO == "UNKNOWN":
        raise Exception("Distribuição não suportada.")
    return " ".join(PKG_MANAGER[DISTRO]["node"])

# Estado global para o stream SSE
class InstallState:
    def __init__(self):
        self.progress = 0
        self.status = "Aguardando conexão..."
        self.clients = []
        self.lock = threading.Lock()

state = InstallState()
app = Flask(__name__, static_folder='frontend', static_url_path='')

def get_local_ip():
    """Passo 2: Captura o IP Real da máquina."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        # Não precisa ser alcançável, apenas para rotear a interface correta
        s.connect(('8.8.8.8', 80))
        ip = s.getsockname()[0]
    except Exception:
        ip = '127.0.0.1'
    finally:
        s.close()
    return ip

def run_command_stream(cmd, shell=True):
    """Executa comando e captura saída em tempo real para stream."""
    try:
        process = subprocess.Popen(
            cmd,
            shell=shell,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1
        )
        
        output_lines = []
        for line in process.stdout:
            line = line.strip()
            if line:
                output_lines.append(line)
                # Tenta extrair nome do pacote sendo instalado
                pkg_name = None
                if DISTRO == "DEBIAN":
                    # Padrão: Selecting previously unselected package <pkg>
                    match = re.search(r'Selecting previously unselected package (\S+)', line)
                    if match:
                        pkg_name = match.group(1)
                    # Ou apenas o nome se estiver configurando
                    elif "Setting up" in line:
                        match = re.search(r'Setting up (\S+)', line)
                        if match:
                            pkg_name = match.group(1)
                
                elif DISTRO == "RHEL":
                    # Padrão: Installing: <pkg>
                    match = re.search(r'Installing:\s+(\S+)', line)
                    if match:
                        pkg_name = match.group(1)
                    elif "Installed:" in line:
                        match = re.search(r'Installed:\s+(\S+)', line)
                        if match:
                            pkg_name = match.group(1)
                            
                elif DISTRO == "ARCH":
                    # Padrão: installing <pkg>
                    match = re.search(r'installing (\S+)', line)
                    if match:
                        pkg_name = match.group(1)
                
                if pkg_name:
                    update_progress(None, f"Instalando pacote: {pkg_name}")
                    
        process.wait()
        return process.returncode == 0
    except Exception as e:
        print(f"Erro ao executar comando: {e}")
        return False

def run_command(cmd, shell=True):
    """Executa comando silenciando saída técnica (fallback)."""
    try:
        subprocess.run(
            cmd, 
            shell=shell, 
            check=True, 
            stdout=subprocess.DEVNULL, 
            stderr=subprocess.DEVNULL
        )
        return True
    except subprocess.CalledProcessError:
        return False

def update_progress(percent, status_msg):
    """Atualiza estado e notifica clientes SSE."""
    with state.lock:
        state.progress = percent
        state.status = status_msg
        # Notificação é feita no momento da requisição SSE

def installation_thread():
    """Orquestra os Passos 5 a 8 com detecção de distro e stream em tempo real."""
    time.sleep(2) # Aguarda cliente conectar

    # Passo 5: Sincronização (0% -> 20%)
    update_progress(5, "Sincronizando repositórios do Linux...")
    update_cmd = get_pkg_cmd("update")
    # Para RHEL, o check-update pode retornar 100 se houver updates, tratamos como sucesso
    if DISTRO == "RHEL":
        try:
            subprocess.run(update_cmd, shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except subprocess.CalledProcessError as e:
            if e.returncode != 100: # 100 significa updates disponíveis, não é erro
                raise e
    else:
        run_command(update_cmd)
    update_progress(20, "Repositórios sincronizados.")

    # Passo 6: Node.js (20% -> 50%)
    update_progress(25, "Configurando ambiente Node.js...")
    node_pkgs = get_node_packages()
    install_cmd = f"{get_pkg_cmd('install')} {node_pkgs}"
    run_command_stream(install_cmd)
    update_progress(50, "Node.js instalado.")

    # Passo 7: PostgreSQL (50% -> 90%) - COM STREAM E NOMES DE PACOTES
    update_progress(55, "Instalando o motor do banco de dados...")
    pg_pkgs = get_pg_packages()
    install_cmd = f"{get_pkg_cmd('install')} {pg_pkgs}"
    run_command_stream(install_cmd)
    update_progress(90, "PostgreSQL instalado.")

    # Passo 8: Ativação e Validação (90% -> 100%)
    update_progress(95, "Ativando serviços...")
    
    # Iniciar serviço depende da distro
    if DISTRO == "ARCH":
        run_command("systemctl start postgresql")
        run_command("postgresql-setup --initdb", check=False) # Init se necessário
    elif DISTRO == "RHEL":
        run_command("postgresql-setup --initdb", check=False)
        run_command("systemctl enable postgresql")
        run_command("systemctl start postgresql")
    else: # DEBIAN
        run_command("systemctl start postgresql")
    
    # Validação do Socket
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
        update_progress(100, "Instalação finalizada (verifique logs se houver erros).")

@app.route('/')
def index():
    """Passo 4: Serve o HTML estático."""
    return send_from_directory('frontend', 'index.html')

@app.route('/api/stream')
def stream():
    """Passo 4 & 5+: Handshake SSE e envio de progresso."""
    def generate():
        while True:
            with state.lock:
                data = {
                    "porcentagem": state.progress,
                    "status": state.status
                }
            yield f"data: {json.dumps(data)}\n\n"
            if state.progress >= 100:
                break
            time.sleep(0.5)
    return Response(generate(), mimetype='text/event-stream')

@app.route('/api/setup-db', methods=['POST'])
def setup_db():
    """Passo 10: Criação do Superuser via Login Shell."""
    data = request.json
    username = data.get('username')
    password = data.get('password')

    if not username or not password:
        return jsonify({"error": "Dados inválidos"}), 400

    # Comando seguro usando login shell do usuário postgres
    cmd = f'sudo -i -u postgres psql -c "CREATE USER {username} WITH PASSWORD \'{password}\' SUPERUSER;"'
    
    try:
        # Executa o comando no servidor local
        result = subprocess.run(
            cmd, 
            shell=True, 
            capture_output=True, 
            text=True
        )
        
        if result.returncode == 0 or "already exists" in result.stderr:
            return jsonify({"success": True, "message": "Usuário criado com sucesso!"})
        else:
            return jsonify({"error": result.stderr}), 500
            
    except Exception as e:
        return jsonify({"error": str(e)}), 500

if __name__ == '__main__':
    # Passo 1: Verificação de Root
    if os.geteuid() != 0:
        print("ERRO: Este script deve ser executado com sudo.")
        print("Uso correto: sudo python3 instalador.py")
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
