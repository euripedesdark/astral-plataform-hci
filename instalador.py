#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import os
import sys
import socket
import subprocess
import threading
import time
import re
import json
from flask import Flask, render_template_string, request, jsonify, Response
from queue import Queue

# --- Configuração e Detecção de Distro ---
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
os.chdir(SCRIPT_DIR)

DISTRO_INFO = {}

def detect_distro():
    """Detecta a distribuição Linux e define os comandos/pacotes corretos."""
    global DISTRO_INFO
    try:
        with open('/etc/os-release', 'r') as f:
            os_release = f.read()
        
        distro_id = ""
        id_like = ""
        for line in os_release.splitlines():
            if line.startswith('ID='):
                distro_id = line.split('=')[1].strip('"\'')
            elif line.startswith('ID_LIKE='):
                id_like = line.split('=')[1].strip('"\'')
        
        # Lógica de detecção
        if 'debian' in id_like or distro_id == 'debian' or distro_id == 'ubuntu':
            DISTRO_INFO = {
                'family': 'DEBIAN',
                'update_cmd': ['apt-get', 'update'],
                'install_cmd': ['apt-get', 'install', '-y'],
                'packages': {
                    'nodejs': ['nodejs', 'npm'],
                    'postgres': ['postgresql', 'postgresql-contrib'],
                    'service_name': 'postgresql'
                }
            }
        elif 'rhel' in id_like or 'fedora' in id_like or 'almalinux' in id_like or distro_id == 'fedora' or distro_id == 'rhel' or distro_id == 'almalinux':
            DISTRO_INFO = {
                'family': 'RHEL',
                'update_cmd': ['dnf', 'update', '-y'],
                'install_cmd': ['dnf', 'install', '-y'],
                'packages': {
                    'nodejs': ['nodejs', 'npm'],
                    'postgres': ['postgresql', 'postgresql-server', 'postgresql-contrib'],
                    'service_name': 'postgresql'
                }
            }
        elif 'arch' in id_like or distro_id == 'arch':
            DISTRO_INFO = {
                'family': 'ARCH',
                'update_cmd': ['pacman', '-Sy', '--noconfirm'],
                'install_cmd': ['pacman', '-S', '--noconfirm'],
                'packages': {
                    'nodejs': ['nodejs', 'npm'],
                    'postgres': ['postgresql', 'postgresql-libs'],
                    'service_name': 'postgresql'
                }
            }
        else:
            # Fallback para Debian
            DISTRO_INFO = {
                'family': 'DEBIAN',
                'update_cmd': ['apt-get', 'update'],
                'install_cmd': ['apt-get', 'install', '-y'],
                'packages': {
                    'nodejs': ['nodejs', 'npm'],
                    'postgres': ['postgresql', 'postgresql-contrib'],
                    'service_name': 'postgresql'
                }
            }
        print(f"[SYSTEM] Distro detectada: {DISTRO_INFO['family']}")
    except Exception as e:
        print(f"[ERROR] Erro ao detectar distro: {e}. Usando fallback Debian.")
        DISTRO_INFO = {
            'family': 'DEBIAN',
            'update_cmd': ['apt-get', 'update'],
            'install_cmd': ['apt-get', 'install', '-y'],
            'packages': {
                'nodejs': ['nodejs', 'npm'],
                'postgres': ['postgresql', 'postgresql-contrib'],
                'service_name': 'postgresql'
            }
        }

detect_distro()

app = Flask(__name__)
clients = []

def get_real_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(('8.8.8.8', 80))
        ip = s.getsockname()[0]
    except Exception:
        ip = '127.0.0.1'
    finally:
        s.close()
    return ip

def run_command_stream(cmd_list, step_start, step_end):
    """Executa comando e gera stream de saída em tempo real."""
    process = subprocess.Popen(
        cmd_list,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1
    )
    
    current_package = "Desconhecido"
    
    for line in process.stdout:
        line = line.strip()
        if not line:
            continue
            
        # Tenta identificar pacote sendo instalado
        match = re.search(r'(unpacking|installing|downloading)\s+([a-zA-Z0-9\-_\.]+)', line, re.IGNORECASE)
        if match:
            current_package = match.group(2)
        
        # Envia para os clientes SSE
        for client in list(clients):
            try:
                client.put({
                    "type": "log",
                    "package": current_package,
                    "detail": line,
                    "progress": None # Mantém progresso atual
                })
            except:
                pass
        
    process.wait()
    return process.returncode == 0

def installation_thread():
    time.sleep(2) # Aguarda cliente conectar
    
    # Passo 1: Update
    for client in list(clients):
        client.put({"step": "update", "msg": "Atualizando repositórios...", "progress": 10})
    
    run_command_stream(DISTRO_INFO['update_cmd'], 0, 20)
    
    # Passo 2: Node.js
    for client in list(clients):
        client.put({"step": "nodejs", "msg": "Instalando Node.js e NPM...", "progress": 40})
    
    pkgs_node = DISTRO_INFO['packages']['nodejs']
    run_command_stream(DISTRO_INFO['install_cmd'] + pkgs_node, 20, 50)
    
    # Passo 3: PostgreSQL (Com feedback de pacote)
    for client in list(clients):
        client.put({"step": "postgres", "msg": "Iniciando instalação do PostgreSQL...", "progress": 60})
    
    pkgs_pg = DISTRO_INFO['packages']['postgres']
    success = run_command_stream(DISTRO_INFO['install_cmd'] + pkgs_pg, 50, 90)
    
    if success:
        # Iniciar serviço
        svc_name = DISTRO_INFO['packages']['service_name']
        try:
            subprocess.run(['systemctl', 'start', svc_name], check=True)
            subprocess.run(['systemctl', 'enable', svc_name], check=True)
        except:
            pass # Ignora erro se systemctl não estiver disponível
            
        for client in list(clients):
            client.put({"step": "done_install", "msg": "PostgreSQL instalado e iniciado!", "progress": 100})
    else:
        for client in list(clients):
            client.put({"step": "error", "msg": "Erro na instalação do PostgreSQL.", "progress": 100})

@app.route('/')
def index():
    return render_template_string(HTML_TEMPLATE, ip_addr=get_real_ip())

@app.route('/api/stream')
def stream():
    def event_stream():
        q = Queue()
        clients.append(q)
        try:
            while True:
                data = q.get(timeout=30)
                yield f"data: {json.dumps(data)}\n\n"
                if data.get('step') == 'done_install' or data.get('step') == 'error':
                    break
        except:
            pass
        finally:
            if q in clients:
                clients.remove(q)
    return Response(event_stream(), mimetype='text/event-stream')

@app.route('/api/setup-db', methods=['POST'])
def setup_db():
    data = request.json
    user = data.get('user')
    password = data.get('password')
    
    if not user or not password:
        return jsonify({"error": "Dados inválidos"}), 400
    
    # Cria usuário superuser no Postgres
    cmd = f"sudo -i -u postgres psql -c \"CREATE USER {user} WITH PASSWORD '{password}' SUPERUSER;\""
    try:
        subprocess.run(cmd, shell=True, check=True, capture_output=True)
        return jsonify({"success": True, "message": "Usuário criado com sucesso!"})
    except Exception as e:
        return jsonify({"error": str(e)}), 500

if __name__ == '__main__':
    threading.Thread(target=installation_thread, daemon=True).start()
    
    ip = get_real_ip()
    print("="*60)
    print(f"SERVIDOR ONLINE! ACESSE EM OUTRA MÁQUINA:")
    print(f"http://{ip}:5000")
    print("="*60)
    
    app.run(host='0.0.0.0', port=5000, threaded=True)

HTML_TEMPLATE = """
<!DOCTYPE html>
<html lang="pt-BR">
<head>
    <meta charset="UTF-8">
    <title>Instalador do Sistema</title>
    <style>
        body { font-family: sans-serif; background: #f0f2f5; display: flex; justify-content: center; align-items: center; height: 100vh; margin: 0; }
        .card { background: white; padding: 2rem; border-radius: 8px; box-shadow: 0 4px 6px rgba(0,0,0,0.1); width: 400px; text-align: center; }
        .progress-bar { width: 100%; background: #e0e0e0; height: 20px; border-radius: 10px; overflow: hidden; margin: 20px 0; }
        .progress-fill { height: 100%; background: #4caf50; width: 0%; transition: width 0.3s; }
        input { width: 100%; padding: 10px; margin: 10px 0; border: 1px solid #ddd; border-radius: 4px; box-sizing: border-box; }
        button { background: #007bff; color: white; border: none; padding: 10px 20px; border-radius: 4px; cursor: pointer; width: 100%; font-size: 16px; }
        button:hover { background: #0056b3; }
        .hidden { display: none; }
        #log-area { font-size: 12px; color: #666; text-align: left; height: 100px; overflow-y: auto; background: #f9f9f9; padding: 5px; margin-top: 10px; border: 1px solid #eee; }
    </style>
</head>
<body>
    <div class="card">
        <h2 id="title">Instalação do Sistema</h2>
        
        <!-- Tela de Progresso -->
        <div id="progress-screen">
            <p id="status-msg">Conectando...</p>
            <p id="pkg-msg" style="font-size: 0.9em; color: #555;"></p>
            <div class="progress-bar">
                <div class="progress-fill" id="fill"></div>
            </div>
            <div id="log-area"></div>
        </div>

        <!-- Tela de Formulário -->
        <div id="form-screen" class="hidden">
            <p>Configuração do Banco de Dados</p>
            <input type="text" id="db-user" placeholder="Usuário Administrador">
            <input type="password" id="db-pass" placeholder="Senha Master">
            <button onclick="submitData()">Salvar e Inicializar</button>
        </div>

        <!-- Tela Final -->
        <div id="final-screen" class="hidden">
            <h3 style="color: green">Sucesso!</h3>
            <p>Banco configurado.</p>
            <button onclick="finish()">Concluir e Iniciar Sistema</button>
        </div>
        
        <!-- Dashboard Simulado -->
        <div id="dashboard-screen" class="hidden">
            <h3>Dashboard do Sistema</h3>
            <nav class="sidebar" style="text-align:left; border-top:1px solid #eee; padding-top:10px;">
                <ul>
                    <li>Visão Geral</li>
                    <li>Usuários</li>
                    <li>Relatórios</li>
                    <li>Configurações</li>
                </ul>
            </nav>
        </div>
    </div>

    <script>
        const source = new EventSource('/api/stream');
        const fill = document.getElementById('fill');
        const statusMsg = document.getElementById('status-msg');
        const pkgMsg = document.getElementById('pkg-msg');
        const logArea = document.getElementById('log-area');

        source.onmessage = function(event) {
            const data = JSON.parse(event.data);
            
            if (data.progress !== undefined) {
                fill.style.width = data.progress + '%';
            }
            if (data.msg) {
                statusMsg.innerText = data.msg;
            }
            if (data.package && data.type === 'log') {
                pkgMsg.innerText = "Instalando pacote: " + data.package;
            }
            if (data.detail) {
                const line = document.createElement('div');
                line.textContent = "> " + data.detail;
                logArea.appendChild(line);
                logArea.scrollTop = logArea.scrollHeight;
            }

            if (data.step === 'done_install') {
                source.close();
                document.getElementById('progress-screen').classList.add('hidden');
                document.getElementById('form-screen').classList.remove('hidden');
            }
            if (data.step === 'error') {
                source.close();
                statusMsg.innerText = "ERRO: " + data.msg;
                statusMsg.style.color = "red";
            }
        };

        function submitData() {
            const user = document.getElementById('db-user').value;
            const pass = document.getElementById('db-pass').value;
            
            fetch('/api/setup-db', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({user: user, password: pass})
            })
            .then(res => res.json())
            .then(data => {
                if (data.success) {
                    document.getElementById('form-screen').classList.add('hidden');
                    document.getElementById('final-screen').classList.remove('hidden');
                } else {
                    alert('Erro: ' + data.error);
                }
            });
        }

        function finish() {
            document.getElementById('final-screen').classList.add('hidden');
            document.getElementById('dashboard-screen').classList.remove('hidden');
            document.getElementById('title').innerText = "Sistema Online";
        }
    </script>
</body>
</html>
"""
