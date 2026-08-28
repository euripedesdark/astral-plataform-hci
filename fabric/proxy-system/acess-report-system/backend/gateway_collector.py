#!/usr/bin/env python3
"""
Coletor universal para Samba AD DC com DNS Híbrido
Detecta automaticamente ambiente e salva logs por 30 dias
"""
import time
import re
import subprocess
import sys
import os
sys.path.insert(0, '/opt/acess-report-system/backend')

from config import Config
from models import AccessRecord, db
from flask import Flask

app = Flask(__name__)
app.config.from_object(Config())
db.init_app(app)

def detect_samba_logs():
    """Detectar automaticamente o caminho dos logs do Samba"""
    candidates = [
        "/var/log/samba/log.samba",
        "/var/log/samba/smbd.log",
        "/var/log/messages"
    ]
    for path in candidates:
        if os.path.exists(path):
            return path
    return None

def monitor_dns():
    samba_log = detect_samba_logs()
    if not samba_log:
        print("❌ Não foi possível detectar logs do Samba. Verifique as permissões.")
        return

    pos = 0
    print(f"🔍 Monitorando consultas DNS em: {samba_log}")
    print(f"📊 Salvando registros por {Config.RETENTION_DAYS} dias")

    while True:
        try:
            if not os.path.exists(samba_log):
                time.sleep(5)
                continue

            with open(samba_log) as f:
                if pos > 0:
                    f.seek(pos)

                for line in f:
                    # Detectar consultas DNS em vários formatos
                    if ('dns:' in line.lower() and 'query' in line.lower()) or                        ('A?' in line and 'client' in line) or                        ('query:' in line and 'IN A' in line):

                        # Extrair IP do cliente
                        ip_match = re.search(r'client(?:\s+address)?\s+(\d+\.\d+\.\d+\.\d+)', line, re.IGNORECASE)
                        if ip_match:
                            client_ip = ip_match.group(1)

                            # Verificar se é da rede interna (detectada automaticamente)
                            if client_ip.startswith(Config.INTERNAL_NETWORK.split('.')[0]):
                                # Extrair domínio
                                domain_match = re.search(r'(?:query|A\?|IN A)\s+([^\s]+)', line, re.IGNORECASE)
                                if domain_match:
                                    domain = domain_match.group(1).lower().rstrip('.')

                                    # Ignorar domínios locais
                                    if not any(domain.endswith(f".{Config.AD_REALM}") for f in [Config.AD_REALM, Config.AD_REALM.lower()]):
                                        # Salvar no banco com data de criação para limpeza automática
                                        with app.app_context():
                                            rec = AccessRecord(
                                                timestamp=datetime.now(),
                                                user_name=f"user_{client_ip.replace('.', '_')}",
                                                hostname=client_ip,
                                                ip_address=client_ip,
                                                url=f"http://{domain}",
                                                title=f"Acesso a {domain}",
                                                category="internet",
                                                domain=domain,
                                                is_blacklisted=False,
                                                created_at=datetime.now()  # Para limpeza automática
                                            )
                                            db.session.add(rec)
                                            db.session.commit()
                                            print(f"✅ DNS: {client_ip} -> {domain}")

                pos = f.tell()

        except Exception as e:
            print(f"⚠️ Erro no coletor: {e}")
            time.sleep(5)

        time.sleep(2)

if __name__ == "__main__":
    monitor_dns()
