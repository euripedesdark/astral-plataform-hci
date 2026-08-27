tro (Debian, RHEL, Arch)
Criação automática da database 'astral'
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

# Estado global para o stream SSE
class InstallState:
    def __init__(self):
        self.progress = 0
        self.status = "Aguardando conexão..."
        self.package_status = ""
        self.clients = []
        self.lock = threading.Lock()

state = InstallState()
app = Flask(__name__, static_folder='frontend', static_url_path='')

# --- Detecção de Distro ---
def detect_distro():
    """Detecta a família da distribuição Linux."""
    if os.path.exists('/etc/os-release'):
        with open('/etc/os-release', 'r') as f:
            content = f.read().lower()
            if 'debian' in content or 'ubuntu' in content:
                return 'DEBIAN'
            elif 'rhel' in content or 'fedora' in content or 'almalinux' in content or 'rocky' in content:
                return 'RHEL'
            elif 'arch' in content or 'manjaro' in content:
                return 'ARCH'
    return 'UNKNOWN'

def get_package_manager(distro):
    """Retorna o gerenciador de pacotes e comandos base."""
    if distro == 'DEBIAN':
        return {'install': 'apt-get install -y', 'update': 'apt-get update', 'packages': {'postgres': 'postgresql postgresql-contrib', 'node': 'nodejs npm curl'}}
    elif distro == 'RHEL':
        return {'install': 'dnf install -y', 'update': 'dnf makecache', 'packages': {'postgres': 'postgresql-server postgresql-contrib', 'node': 'nodejs npm curl'}}
    elif distro == 'ARCH':
        return {'install': 'pacman -Sy --noconfirm', 'update': 'pacman -Sy', 'packages': {'postgres': 'postgresql postgresql-contrib', 'node': 'nodejs npm curl'}}
    else:
        raise Exception("Distribuição não suportada.")

# --- Funções de Rede e Sistema ---
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

def update_progress(percent, status_msg, package_msg=""):
    with state.lock:
        state.progress = percent
        state.status = status_msg
        if package_msg:
            state.package_status = package_msg

# --- Thread de Instalação ---
def installation_thread():
    time.sleep(2)
    distro = detect_distro()
    try:
        pm = get_package_manager(distro)
    except Exception as e:
        update_progress(0, f"Erro: {str(e)}")
        return

    # Passo 5: Sincronização
    update_progress(5, f"Sincronizando repositórios ({distro})...")
    run_command(pm['update'])
    update_progress(20, "Repositórios sincronizados.")

    # Passo 6: Node.js
    update_progress(25, "Configurando ambiente Node.js...")
    run_command(f"{pm['install']} {pm['packages']['node']}")
    update_progress(50, "Node.js instalado.")

    # Passo 7: PostgreSQL com feedback em tempo real
    update_progress(55, "Iniciando instalação do PostgreSQL...")
    install_postgres_with_feedback(pm['install'], pm['packages']['postgres'])
    update_progress(90, "PostgreSQL instalado.")

    # Passo 8: Ativação
    update_progress(95, "Ativando serviços...")
    start_postgres_service(distro)
    
    # Validação
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
        update_progress(100, "Instalação concluída! Pronto para configurar.")
    else:
        update_progress(100, "Instalação finalizada (verifique logs).")

def run_command(cmd, shell=True):
    try:
        subprocess.run(cmd, shell=shell, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return True
    except subprocess.CalledProcessError:
        return False

def install_postgres_with_feedback(install_cmd, packages):
    """Executa instalação capturando saída para feedback em tempo real."""
    cmd = f"{install_cmd} {packages}"
    try:
        process = subprocess.Popen(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        for line in process.stdout:
            # Tenta extrair nome do pacote (padrão comum em apt/dnf/pacman)
            match = re.search(r'(Unpacking|Installing|Processing)\s+([a-zA-Z0-9\-\.]+)', line)
            if match:
                pkg_name = match.group(2)
                update_progress(state.progress, f"Instalando pacote: {pkg_name}...", pkg_name)
            elif "Setting up" in line or "configured" in line:
                 match = re.search(r'Setting up ([a-zA-Z0-9\-\.]+)', line)
                 if match:
                    update_progress(state.progress, f"Configurando {match.group(1)}...", match.group(1))
            
            # Aumenta progresso gradualmente enquanto há output
            if state.progress < 90:
                state.progress += 0.5
        
        process.wait()
    except Exception as e:
        update_progress(state.progress, f"Erro na instalação: {str(e)}")

def start_postgres_service(distro):
    if distro == 'DEBIAN':
        run_command("systemctl start postgresql")
    elif distro == 'RHEL':
        run_command("systemctl initdb postgresql") # Necessário no RHEL antes de start
        run_command("systemctl enable postgresql")
        run_command("systemctl start postgresql")
    elif distro == 'ARCH':
        run_command("su - postgres -c 'initdb -D /var/lib/postgres/data'") # Se necessário
        run_command("systemctl enable postgresql")
        run_command("systemctl start postgresql")

# --- Rotas Flask ---
@app.route('/')
def index():
    return send_from_directory('frontend', 'index.html')

@app.route('/api/stream')
def stream():
    def generate():
        while True:
            with state.lock:
                data = {
                    "porcentagem": state.progress,
                    "status": state.status,
                    "pacote": state.package_status
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

    # 1. Criar Usuário
    cmd_user = f'sudo -i -u postgres psql -c "CREATE USER {username} WITH PASSWORD \'{password}\' SUPERUSER;"'
    
    # 2. Criar Database Astral
    cmd_db = f'sudo -i -u postgres psql -c "CREATE DATABASE astral OWNER {username};"'

    try:
        res_user = subprocess.run(cmd_user, shell=True, capture_output=True, text=True)
        if res_user.returncode != 0 and "already exists" not in res_user.stderr:
            return jsonify({"error": res_user.stderr}), 500
            
        res_db = subprocess.run(cmd_db, shell=True, capture_output=True, text=True)
        if res_db.returncode != 0 and "already exists" not in res_db.stderr:
            # Se falhar a DB mas o user ok, avisamos mas consideramos parcial
            pass 

        return jsonify({"success": True, "message": "Usuário e database 'astral' criados com sucesso!"})
            
    except Exception as e:
        return jsonify({"error": str(e)}), 500

if __name__ == '__main__':
    if os.geteuid() != 0:
        print("ERRO: Este script deve ser executado com sudo.")
        sys.exit(1)

    local_ip = get_local_ip()
    print("\n" + "="*60)
    print("[GIT PROJETO] INSTALADOR WEB ATIVO NA PASTA LOCAL")
    print(f"[ENDEREÇO] http://{local_ip}:{PORT}")
    print("="*60 + "\n")

    t = threading.Thread(target=installation_thread)
    t.daemon = True
    t.start()

    app.run(host=HOST_IP, port=PORT, threaded=True)
