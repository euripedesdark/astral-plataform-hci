import os
from datetime import datetime, timedelta

class Config:
    PROJECT_ROOT = "/opt/acess-report-system"
    BASE_DIR = os.path.join(PROJECT_ROOT, 'backend')
    INSTANCE_DIR = os.path.join(PROJECT_ROOT, 'instance')
    FRONTEND_DIR = os.path.join(PROJECT_ROOT, 'frontend')
    BLACKLIST_DIR = os.path.join(PROJECT_ROOT, 'blacklists')
    
    # PostgreSQL
    DB_NAME = "access_report"
    DB_USER = "acessreport"
    DB_PASSWORD = "changeme123"  # ALTERE NA PRODUÇÃO!
    DB_HOST = "localhost"
    DB_PORT = "5432"
    SQLALCHEMY_DATABASE_URI = f'postgresql://{DB_USER}:{DB_PASSWORD}@{DB_HOST}:{DB_PORT}/{DB_NAME}'
    SQLALCHEMY_TRACK_MODIFICATIONS = False
    
    # Chrome History Paths (multi-user, multi-platform)
    CHROME_BASES = [
        "~/.config/google-chrome/",
        "~/.config/microsoft-edge/",
        "~/.config/brave-browser/",
        "~/.mozilla/firefox/"  # Firefox (requer parsing diferente)
    ]
    
    # Data inicial para coleta (30 dias atrás)
    DATA_INICIAL = datetime.now() - timedelta(days=30)
    
    # Domínio detectado automaticamente (será atualizado pelo install.sh)
    AD_DOMAIN = "astral.celeste"
    AD_REALM = "astral.celeste"
    
    # Interface de rede (será detectada automaticamente)
    INTERNAL_INTERFACE = "eth0"
    INTERNAL_NETWORK = "192.168.1.0/24"
    GATEWAY_IP = "192.168.1.1"
    
    # Retenção de dados
    RETENTION_DAYS = 30
    
    # Categorias bloqueadas
    CATEGORIAS_EXCLUIDAS = {
        "adult", "mixed_adult", "dating", "gambling", "drogue", "agressif",
        "warez", "phishing", "malware", "hacking", "ddos", "cryptojacking",
        "stalkerware", "vpn", "redirector", "strict_redirector",
        "strong_redirector", "chat", "social_networks", "games", "lingerie"
    }
    
    SECRET_KEY = os.urandom(24).hex()
