import os
import shutil
import sqlite3
import socket
from datetime import datetime
import getpass
from urllib.parse import urlparse
from models import AccessRecord, db
from config import Config

def obter_ip_local():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except:
        return "127.0.0.1"

def carregar_historico_chrome():
    historico = []
    for base in Config.CHROME_BASES:
        base = os.path.expanduser(base)
        if not os.path.isdir(base):
            continue
        for perfil in os.listdir(base):
            history_path = os.path.join(base, perfil, "History")
            if not os.path.isfile(history_path):
                continue
            try:
                temp_db = f"/tmp/history_{os.path.basename(base)}_{perfil}_{int(datetime.now().timestamp())}.db"
                shutil.copy2(history_path, temp_db)
                conn = sqlite3.connect(temp_db)
                cur = conn.cursor()
                cur.execute("""
                    SELECT url, title,
                           datetime(last_visit_time/1000000 - 11644473600,
                                    'unixepoch', 'localtime')
                    FROM urls
                    WHERE last_visit_time > ?
                    ORDER BY last_visit_time DESC
                    LIMIT 500
                """, (int((datetime.now() - Config.DATA_INICIAL).timestamp() * 1000000),))
                historico.extend(cur.fetchall())
                conn.close()
                os.remove(temp_db)
            except Exception as e:
                continue
    return historico

def extrair_dominio(url):
    try:
        parsed = urlparse(url)
        domain = parsed.hostname.lower() if parsed.hostname else ""
        if domain.startswith('www.'):
            domain = domain[4:]
        return domain
    except:
        return ""

def classificar_dominio(dominio):
    if not dominio:
        return "outros"
    # Verificar domínios locais
    dominios_locais = ['localhost', '127.0.0.1', '192.168.', '10.', '172.16.', '172.17.', '172.18.', '172.19.', '172.20.', '172.21.', '172.22.', '172.23.', '172.24.', '172.25.', '172.26.', '172.27.', '172.28.', '172.29.', '172.30.', '172.31.']
    for local in dominios_locais:
        if dominio.startswith(local):
            return "local"
    return "outros"  # Simples para começar

def coletar_e_salvar_acessos():
    from app import app
    with app.app_context():
        historico = carregar_historico_chrome()
        novos_registros = 0
        for url, title, visit_time in historico:
            try:
                data = datetime.strptime(visit_time, "%Y-%m-%d %H:%M:%S")
            except:
                continue
            if data < Config.DATA_INICIAL:
                continue
            dominio = extrair_dominio(url)
            categoria = classificar_dominio(dominio)
            # Verificar se já existe
            exists = AccessRecord.query.filter_by(url=url, timestamp=data).first()
            if exists:
                continue
            record = AccessRecord(
                timestamp=data,
                user_name=getpass.getuser(),
                hostname=socket.gethostname(),
                ip_address=obter_ip_local(),
                url=url,
                title=title or "Sem título",
                category=categoria,
                domain=dominio,
                is_blacklisted=False
            )
            db.session.add(record)
            novos_registros += 1
        try:
            db.session.commit()
            return novos_registros
        except Exception as e:
            db.session.rollback()
            print(f"Erro ao salvar registros: {e}")
            return 0
