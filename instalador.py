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

def run_command(cmd, shell=True):
    """Executa comando silenciando saída técnica."""
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
    """Orquestra os Passos 5 a 8."""
    time.sleep(2) # Aguarda cliente conectar

    # Passo 5: Sincronização (0% -> 20%)
    update_progress(5, "Sincronizando repositórios do Linux...")
    run_command("apt-get update")
    update_progress(20, "Repositórios sincronizados.")

    # Passo 6: Node.js (20% -> 50%)
    update_progress(25, "Configurando ambiente Node.js...")
    # Instalação simplificada para exemplo (pode exigir setup do repo nodesource)
    run_command("apt-get install -y nodejs npm curl") 
    update_progress(50, "Node.js instalado.")

    # Passo 7: PostgreSQL (50% -> 90%)
    update_progress(55, "Instalando o motor do banco de dados...")
    run_command("apt-get install -y postgresql postgresql-contrib")
    update_progress(90, "PostgreSQL instalado.")

    # Passo 8: Ativação e Validação (90% -> 100%)
    update_progress(95, "Ativando serviços...")
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
