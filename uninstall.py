#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Desinstalador Web Unificado - Astral Platform HCI
Uso: sudo python3 uninstall.py
"""
import os
import sys
import socket
import subprocess
import time
import json
import threading
import shutil
import base64

APP_DIR = os.path.dirname(os.path.abspath(__file__))
PORT = 5000
HOST_IP = "0.0.0.0"

class UninstallState:
    def __init__(self):
        self.progress = 0
        self.status = "Aguardando conexão..."
        self.package_name = ""
        self.lock = threading.Lock()

state = UninstallState()

def get_local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(('8.8.8.8', 80)); ip = s.getsockname()[0]
    except Exception:
        ip = '127.0.0.1'
    finally:
        s.close()
    return ip

def detect_distro():
    try:
        with open('/etc/os-release', 'r') as f:
            content = f.read().lower()
        if 'debian' in content or 'ubuntu' in content: return 'debian'
        elif 'rhel' in content or 'fedora' in content or 'almalinux' in content \
             or 'centos' in content or 'rocky' in content: return 'rhel'
        elif 'arch' in content or 'manjaro' in content: return 'arch'
    except FileNotFoundError:
        pass
    return 'unknown'

def run_command_stream(cmd, shell=True):
    print(f"\n[SISTEMA] Executando: {cmd}")
    process = subprocess.Popen(cmd, shell=shell, stdout=subprocess.PIPE,
                             stderr=subprocess.STDOUT, text=True, bufsize=1)
    for line in process.stdout:
        print(line, end="")
    process.wait()
    return process.returncode == 0

def update_progress(percent, status_msg):
    with state.lock:
        state.progress = percent
        state.status = status_msg

# ============================================================
# THREAD PRINCIPAL DE DESINSTALAÇÃO
# ============================================================
def uninstallation_thread():
    time.sleep(2)

    update_progress(10, "Parando serviços do sistema (Astral Platform e Nginx)...")
    subprocess.run("systemctl stop astral-platform.service 2>/dev/null || true", shell=True)
    subprocess.run("systemctl disable astral-platform.service 2>/dev/null || true", shell=True)
    subprocess.run("rm -f /etc/systemd/system/astral-platform.service", shell=True)
    subprocess.run("systemctl daemon-reload", shell=True)

    update_progress(25, "Removendo artefatos de produção (/opt/astral-platform) e configs (/etc/astral)...")
    shutil.rmtree("/opt/astral-platform", ignore_errors=True)
    shutil.rmtree("/etc/astral", ignore_errors=True)
    if os.path.exists("/etc/profile.d/java_home.sh"):
        os.remove("/etc/profile.d/java_home.sh")

    update_progress(40, "Limpando configurações e virtual hosts do Nginx...")
    distro = detect_distro()
    if distro == 'debian':
        subprocess.run("rm -f /etc/nginx/sites-enabled/astral.conf /etc/nginx/sites-available/astral.conf", shell=True)
    else:
        subprocess.run("rm -f /etc/nginx/conf.d/astral.conf", shell=True)
    subprocess.run("systemctl restart nginx 2>/dev/null || true", shell=True)

    update_progress(60, "Removendo base de dados e usuário 'astral' do PostgreSQL...")
    try:
        subprocess.run('sudo -i -u postgres psql -c "DROP DATABASE IF EXISTS astral;"', shell=True, capture_output=True)
        subprocess.run('sudo -i -u postgres psql -c "DROP USER IF EXISTS astral;"', shell=True, capture_output=True)
    except Exception as e:
        print(f"[AVISO] Não foi possível limpar o banco de dados completamente: {e}")

    update_progress(80, "Limpando dependências globais e arquivos temporários...")
    subprocess.run("rm -f /tmp/astral-bootstrap.sql /tmp/jdk.deb /tmp/jdk.tar.gz", shell=True)
    subprocess.run("pip3 uninstall -y flask psycopg2-binary 2>/dev/null || true", shell=True)

    update_progress(100, "Desinstalação concluída com sucesso!")

# ============================================================
# BLOCO PRINCIPAL
# ============================================================
if __name__ == '__main__':
    if os.geteuid() != 0:
        print("ERRO: Este script deve ser executado com sudo.", flush=True)
        sys.exit(1)

    try:
        import flask
        from flask import Flask, Response, jsonify
    except ImportError:
        subprocess.run("pip3 install flask --break-system-packages 2>/dev/null || pip3 install flask", shell=True, capture_output=True)
        import flask
        from flask import Flask, Response, jsonify

    app = Flask(__name__)

    @app.route('/')
    def index():
        html_content = """<!DOCTYPE html>
<html lang="pt-br">
<head>
<meta charset="UTF-8">
<title>ASTRAL PLATFORM - Desinstalação</title>
<style>
  body{background:#05070d;font-family:'Segoe UI',sans-serif;color:#fff;display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh;margin:0}
  .box{width:400px;background:rgba(4,10,22,.8);border:2px solid #ff5c5c;border-radius:14px;padding:30px;box-shadow:0 0 20px rgba(255,92,92,.3);text-align:center}
  h1{font-size:22px;color:#ff8888;margin-top:0}
  .bar{width:100%;background:#111;height:14px;border-radius:7px;overflow:hidden;margin:20px 0;border:1px solid #333}
  .fill{width:0%;height:100%;background:#ff5c5c;transition:width .4s}
  #status{font-size:14px;color:#aaa}
</style>
</head>
<body>
<div class="box">
  <h1>Desinstalando Astral</h1>
  <div class="bar"><div class="fill" id="fill"></div></div>
  <div id="status">Iniciando desinstalação...</div>
</div>
<script>
var evt = new EventSource('/api/stream');
evt.onmessage = function(e) {
    var data = JSON.parse(e.data);
    document.getElementById('fill').style.width = data.porcentagem + '%';
    document.getElementById('status').textContent = data.status;
    if(data.porcentagem >= 100) {
        evt.close();
        setTimeout(() => { alert('Desinstalação concluída.'); }, 1000);
    }
};
</script>
</body>
</html>"""
        return html_content

    @app.route('/api/stream')
    def stream():
        def generate():
            while True:
                with state.lock:
                    data = {"porcentagem": state.progress, "status": state.status}
                yield f"data: {json.dumps(data)}\n\n"
                if state.progress >= 100: break
                time.sleep(0.5)
        return Response(generate(), mimetype='text/event-stream')

    local_ip = get_local_ip()
    print("\n" + "="*60, flush=True)
    print("[ASTRAL PLATFORM] DESINSTALADOR WEB UNIFICADO", flush=True)
    print("="*60, flush=True)
    print(f"[AÇÃO] Abra o navegador e acesse para acompanhar:", flush=True)
    print(f"[ENDEREÇO] http://{local_ip}:{PORT}", flush=True)
    print("="*60 + "\n", flush=True)

    t = threading.Thread(target=uninstallation_thread)
    t.daemon = True
    t.start()
    app.run(host=HOST_IP, port=PORT, threaded=True)
