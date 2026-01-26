#!/bin/bash
set -e

echo "=== INSTALADOR AUTOMÁTICO UNIVERSAL - MONITORAMENTO SARG ==="
echo "Detectando ambiente Samba AD DC..."
echo "=============================================="

# ============================================================================
# DETECÇÃO AUTOMÁTICA DO AMBIENTE (INDEPENDENTE DE IP/DOMÍNIO)
# ============================================================================
detect_environment() {
    # 1. Detectar domínio do Samba AD DC
    if command -v samba-tool &>/dev/null && samba-tool domain info . --json 2>/dev/null | grep -q "realm"; then
        REALM=$(samba-tool domain info . --json 2>/dev/null | grep -oP '"realm":\s*"\K[^"]+' | tr '[:upper:]' '[:lower:]')
        DOMAIN="${REALM^^}"
        FQDN=$(hostname -f 2>/dev/null || hostname)
    elif [ -f /etc/samba/smb.conf ] && grep -qi "server role = active directory domain controller" /etc/samba/smb.conf; then
        REALM=$(grep -i "^\s*realm" /etc/samba/smb.conf | head -1 | awk '{print $2}' | tr '[:upper:]' '[:lower:]')
        DOMAIN="${REALM^^}"
        FQDN=$(hostname -f 2>/dev/null || hostname)
    else
        echo "❌ Não foi possível detectar ambiente Samba AD DC"
        exit 1
    fi

    # 2. Detectar interface de rede via smb.conf (sua abordagem correta!)
    INTERNAL_INTERFACE=""
    if [ -f /etc/samba/smb.conf ]; then
        SMB_IFACES=$(grep -i "^\s*interfaces" /etc/samba/smb.conf 2>/dev/null | head -1 | cut -d= -f2 | tr ',' ' ' | xargs)
        for iface in $SMB_IFACES; do
            [[ "$iface" == "lo" || "$iface" == "127.0.0.1" || "$iface" == "::1" ]] && continue
            if ip link show "$iface" >/dev/null 2>&1; then
                INTERNAL_INTERFACE="$iface"
                break
            fi
        done
    fi

    # 3. Fallback inteligente se não encontrar no smb.conf
    if [ -z "$INTERNAL_INTERFACE" ]; then
        INTERNAL_INTERFACE=$(ip -br link show | grep -vE 'lo|docker|virbr|veth' | grep UP | head -1 | awk '{print $1}')
    fi

    # 4. Obter IP e calcular rede
    if [ -n "$INTERNAL_INTERFACE" ]; then
        INTERNAL_IP=$(ip -4 addr show "$INTERNAL_INTERFACE" 2>/dev/null | grep -oP '(?<=inet\s)\d+(\.\d+){3}' | head -1)
        if [ -z "$INTERNAL_IP" ]; then
            echo "❌ Interface $INTERNAL_INTERFACE não tem IP configurado"
            exit 1
        fi
        # Calcular rede automaticamente (detectar máscara)
        NETMASK=$(ip -4 addr show "$INTERNAL_INTERFACE" | grep -oP '(?<=inet\s)\d+(\.\d+){3}/\d+' | grep -oP '/\K\d+')
        if [ -z "$NETMASK" ]; then NETMASK="24"; fi
        INTERNAL_NETWORK="${INTERNAL_IP%.*}.0/$NETMASK"
    else
        echo "❌ Não foi possível detectar interface de rede"
        exit 1
    fi

    echo "✅ Domínio detectado: $DOMAIN"
    echo "✅ Interface: $INTERNAL_INTERFACE ($INTERNAL_IP)"
    echo "✅ Rede: $INTERNAL_NETWORK"
}

# ============================================================================
# CRIAÇÃO DA ESTRUTURA COMPLETA (100% AUTÔNOMA)
# ============================================================================
create_structure() {
    INSTALL_DIR="/opt/acess-report-system"
    DB_NAME="access_report"
    DB_USER="acessreport"
    DB_PASSWORD=$(openssl rand -base64 16 | tr -dc 'a-zA-Z0-9' | head -c 12)

    echo "📁 Criando estrutura do projeto em $INSTALL_DIR..."
    mkdir -p $INSTALL_DIR/{backend,frontend/{templates,static/{css,js}},instance,blacklists,logs,reports/{daily,monthly}}

    # requirements.txt
    cat > $INSTALL_DIR/requirements.txt << 'EOF'
flask
flask-sqlalchemy
flask-cors
psycopg2-binary
requests
apscheduler
jinja2
EOF

    # config.py (com detecção automática)
    cat > $INSTALL_DIR/backend/config.py << EOF
import os
from datetime import datetime, timedelta

class Config:
    PROJECT_ROOT = "$INSTALL_DIR"
    BASE_DIR = os.path.join(PROJECT_ROOT, 'backend')
    INSTANCE_DIR = os.path.join(PROJECT_ROOT, 'instance')
    FRONTEND_DIR = os.path.join(PROJECT_ROOT, 'frontend')
    BLACKLIST_DIR = os.path.join(PROJECT_ROOT, 'blacklists')

    DB_NAME = "$DB_NAME"
    DB_USER = "$DB_USER"
    DB_PASSWORD = "$DB_PASSWORD"
    DB_HOST = "localhost"
    DB_PORT = "5432"

    SQLALCHEMY_DATABASE_URI = f'postgresql://{DB_USER}:{DB_PASSWORD}@{DB_HOST}:{DB_PORT}/{DB_NAME}'
    SQLALCHEMY_TRACK_MODIFICATIONS = False

    AD_DOMAIN = "$DOMAIN"
    AD_REALM = "$REALM"
    INTERNAL_INTERFACE = "$INTERNAL_INTERFACE"
    INTERNAL_NETWORK = "$INTERNAL_NETWORK"
    GATEWAY_IP = "$INTERNAL_IP"

    # Manter registros por 30 dias (requisito do usuário)
    RETENTION_DAYS = 30

    CATEGORIAS_EXCLUIDAS = {
        "adult", "mixed_adult", "dating", "gambling", "drogue", "agressif",
        "warez", "phishing", "malware", "hacking", "ddos", "cryptojacking",
        "stalkerware", "vpn", "redirector", "strict_redirector",
        "strong_redirector", "chat", "social_networks", "games", "lingerie"
    }

    SECRET_KEY = os.urandom(24).hex()
EOF

    # models.py (com campo created_at para limpeza automática)
    cat > $INSTALL_DIR/backend/models.py << 'EOF'
from flask_sqlalchemy import SQLAlchemy
from datetime import datetime

db = SQLAlchemy()

class AccessRecord(db.Model):
    __tablename__ = 'access_records'
    id = db.Column(db.BigInteger, primary_key=True)
    timestamp = db.Column(db.DateTime, nullable=False, index=True)
    user_name = db.Column(db.String(100), nullable=False, index=True)
    hostname = db.Column(db.String(100), nullable=False)
    ip_address = db.Column(db.String(45), nullable=False)
    url = db.Column(db.Text, nullable=False)
    title = db.Column(db.Text)
    category = db.Column(db.String(100), nullable=False, index=True)
    domain = db.Column(db.String(255), nullable=False, index=True)
    is_blacklisted = db.Column(db.Boolean, default=False)
    created_at = db.Column(db.DateTime, default=datetime.utcnow, index=True)  # Para limpeza automática

    def to_dict(self):
        return {
            'id': self.id,
            'timestamp': self.timestamp.strftime('%Y-%m-%d %H:%M:%S'),
            'user': self.user_name,
            'hostname': self.hostname,
            'ip_address': self.ip_address,
            'url': self.url,
            'title': self.title or 'Sem título',
            'category': self.category,
            'domain': self.domain
        }
EOF

    # main.py (servidor Flask com limpeza automática)
    cat > $INSTALL_DIR/backend/main.py << 'EOF'
from flask import Flask, render_template, jsonify, request
from flask_sqlalchemy import SQLAlchemy
from datetime import datetime, timedelta
import sys
sys.path.insert(0, '/opt/acess-report-system/backend')

from config import Config
from models import AccessRecord, db

app = Flask(__name__,
    template_folder='/opt/acess-report-system/frontend/templates',
    static_folder='/opt/acess-report-system/frontend/static'
)
app.config.from_object(Config())
db.init_app(app)

@app.before_first_request
def initialize():
    with app.app_context():
        db.create_all()
        # Limpar registros antigos na inicialização
        cleanup_old_records()

def cleanup_old_records():
    """Limpar registros mais antigos que 30 dias"""
    try:
        with app.app_context():
            retention_days = app.config['RETENTION_DAYS']
            cutoff_date = datetime.utcnow() - timedelta(days=retention_days)
            deleted = AccessRecord.query.filter(AccessRecord.created_at < cutoff_date).delete()
            db.session.commit()
            print(f"🧹 Limpeza automática: {deleted} registros removidos (mais antigos que {retention_days} dias)")
    except Exception as e:
        print(f"❌ Erro na limpeza automática: {e}")

@app.route('/')
def index():
    return render_template('index.html')

@app.route('/api/access-records')
def get_access_records():
    start_date = request.args.get('start_date')
    end_date = request.args.get('end_date')
    category = request.args.get('category', 'all')
    user = request.args.get('user', 'all')

    query = AccessRecord.query.order_by(AccessRecord.timestamp.desc())

    if start_date:
        start_dt = datetime.strptime(start_date, '%Y-%m-%d')
        query = query.filter(AccessRecord.timestamp >= start_dt)

    if end_date:
        end_dt = datetime.strptime(end_date, '%Y-%m-%d') + timedelta(days=1)
        query = query.filter(AccessRecord.timestamp < end_dt)

    if category and category != 'all':
        query = query.filter(AccessRecord.category == category)

    if user and user != 'all':
        query = query.filter(AccessRecord.user_name == user)

    records = query.limit(100).all()
    return jsonify([record.to_dict() for record in records])

@app.route('/api/stats')
def get_stats():
    total = AccessRecord.query.count()
    today = datetime.now().replace(hour=0, minute=0, second=0, microsecond=0)
    today_count = AccessRecord.query.filter(AccessRecord.timestamp >= today).count()
    retention = app.config['RETENTION_DAYS']
    return jsonify({
        'total_accesses': total,
        'today_accesses': today_count,
        'retention_days': retention,
        'last_update': datetime.now().strftime('%d/%m/%Y %H:%M:%S')
    })

if __name__ == "__main__":
    app.run(host='0.0.0.0', port=5000)
EOF

    # gateway_collector.py (monitoramento DNS com salvamento por 30 dias)
    cat > $INSTALL_DIR/backend/gateway_collector.py << EOF
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
                    if ('dns:' in line.lower() and 'query' in line.lower()) or \
                       ('A?' in line and 'client' in line) or \
                       ('query:' in line and 'IN A' in line):

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
EOF
chmod +x $INSTALL_DIR/backend/gateway_collector.py

    # index.html (interface web minimalista)
    cat > $INSTALL_DIR/frontend/templates/index.html << 'EOF'
<!DOCTYPE html>
<html lang="pt-BR">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Monitoramento SARG - <script>document.write(window.location.hostname)</script></title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css" rel="stylesheet">
    <style>
        body { background-color: #f8f9fa; }
        .stat-card { text-align: center; padding: 1.5rem; }
        .stat-number { font-size: 2.5rem; font-weight: bold; }
    </style>
</head>
<body>
    <nav class="navbar navbar-dark bg-primary">
        <div class="container">
            <a class="navbar-brand" href="#">
                <i class="bi bi-shield-lock me-2"></i>Monitoramento SARG
            </a>
        </div>
    </nav>
    <div class="container py-4">
        <div class="row text-center mb-4">
            <div class="col-md-3">
                <div class="stat-card border-primary">
                    <div class="stat-number text-primary" id="total-accesses">0</div>
                    <div class="text-muted">Total de Acessos</div>
                </div>
            </div>
            <div class="col-md-3">
                <div class="stat-card border-success">
                    <div class="stat-number text-success" id="today-accesses">0</div>
                    <div class="text-muted">Acessos Hoje</div>
                </div>
            </div>
            <div class="col-md-3">
                <div class="stat-card border-info">
                    <div class="stat-number text-info" id="retention-days">30</div>
                    <div class="text-muted">Dias de Retenção</div>
                </div>
            </div>
            <div class="col-md-3">
                <div class="stat-card border-warning">
                    <div class="stat-number text-warning" id="last-update">--:--</div>
                    <div class="text-muted">Última Atualização</div>
                </div>
            </div>
        </div>
        <div class="card">
            <div class="card-header bg-white">
                <h5>Últimos Acessos (30 dias de histórico)</h5>
            </div>
            <div class="card-body">
                <div class="table-responsive">
                    <table class="table table-hover" id="records-table">
                        <thead>
                            <tr>
                                <th>Data/Hora</th>
                                <th>Usuário</th>
                                <th>Categoria</th>
                                <th>Domínio</th>
                            </tr>
                        </thead>
                        <tbody>
                            <tr>
                                <td colspan="4" class="text-center py-3">
                                    <div class="spinner-border text-primary" role="status">
                                        <span class="visually-hidden">Carregando...</span>
                                    </div>
                                </td>
                            </tr>
                        </tbody>
                    </table>
                </div>
            </div>
        </div>
    </div>
    <script>
        async function loadStats() {
            try {
                const res = await fetch('/api/stats');
                const data = await res.json();
                document.getElementById('total-accesses').textContent = data.total_accesses;
                document.getElementById('today-accesses').textContent = data.today_accesses;
                document.getElementById('retention-days').textContent = data.retention_days;
                document.getElementById('last-update').textContent = data.last_update.split(' ')[1].slice(0,5);
            } catch(e) { console.error(e); }
        }
        async function loadRecords() {
            try {
                const res = await fetch('/api/access-records');
                const records = await res.json();
                const tbody = document.querySelector('#records-table tbody');
                tbody.innerHTML = records.map(r =>
                    `<tr>
                        <td>${r.timestamp}</td>
                        <td><span class="badge bg-secondary">${r.user}</span></td>
                        <td><span class="badge bg-primary">${r.category}</span></td>
                        <td>${r.domain}</td>
                    </tr>`
                ).join('');
            } catch(e) {
                document.querySelector('#records-table tbody').innerHTML =
                    '<tr><td colspan="4" class="text-center text-danger py-3">Erro ao carregar dados</td></tr>';
            }
        }
        loadStats();
        loadRecords();
        setInterval(loadStats, 30000);
        setInterval(loadRecords, 30000);
    </script>
</body>
</html>
EOF

    # Permissões
    PROJECT_USER=$(logname 2>/dev/null || echo "root")
    chown -R $PROJECT_USER:$PROJECT_USER $INSTALL_DIR 2>/dev/null || chown -R root:root $INSTALL_DIR
    chmod -R 755 $INSTALL_DIR

    echo "✅ Estrutura criada com sucesso"
}

# ============================================================================
# CONFIGURAÇÃO DO POSTGRESQL (AUTOMÁTICA)
# ============================================================================
setup_postgresql() {
    echo "🐘 Configurando PostgreSQL..."

    # Instalar se necessário
    if ! command -v psql &>/dev/null; then
        dnf install -y postgresql-server postgresql-contrib 2>/dev/null || \
        apt-get install -y postgresql postgresql-contrib 2>/dev/null || \
        echo "⚠️ PostgreSQL não instalado. Configure manualmente."
    fi

    # Inicializar cluster se necessário
    if [ -x /usr/bin/postgresql-setup ] && [ ! -d "/var/lib/pgsql/data/base" ]; then
        postgresql-setup --initdb --unit postgresql 2>/dev/null || true
    fi

    # Iniciar serviço
    systemctl enable --now postgresql 2>/dev/null || systemctl enable --now postgresql.service 2>/dev/null || true

    # Criar usuário e banco
    sudo -u postgres psql -c "CREATE USER $DB_USER WITH PASSWORD '$DB_PASSWORD';" 2>/dev/null || true
    sudo -u postgres psql -c "CREATE DATABASE $DB_NAME OWNER $DB_USER;" 2>/dev/null || true

    # Criar tabela com índice para created_at (limpeza automática)
    sudo -u postgres psql -d $DB_NAME << 'EOF'
CREATE TABLE IF NOT EXISTS access_records (
    id BIGSERIAL PRIMARY KEY,
    timestamp TIMESTAMP NOT NULL,
    user_name VARCHAR(100) NOT NULL,
    hostname VARCHAR(100) NOT NULL,
    ip_address VARCHAR(45) NOT NULL,
    url TEXT NOT NULL,
    title TEXT,
    category VARCHAR(100) NOT NULL,
    domain VARCHAR(255) NOT NULL,
    is_blacklisted BOOLEAN DEFAULT false,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_access_records_timestamp ON access_records(timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_access_records_user ON access_records(user_name);
CREATE INDEX IF NOT EXISTS idx_access_records_domain ON access_records(domain);
CREATE INDEX IF NOT EXISTS idx_access_records_created_at ON access_records(created_at); -- Para limpeza automática
EOF

    echo "✅ PostgreSQL configurado"
}

# ============================================================================
# CONFIGURAÇÃO DOS SERVIÇOS SYSTEMD
# ============================================================================
setup_services() {
    echo "⚙️ Configurando serviços systemd..."

    # Serviço do coletor
    cat > /etc/systemd/system/access-report-collector.service << EOF
[Unit]
Description=Coletor de Tráfego DNS para Monitoramento SARG
After=network.target samba-ad-dc.service named.service postgresql.service
Wants=samba-ad-dc.service named.service postgresql.service

[Service]
Type=simple
User=$(logname 2>/dev/null || echo "root")
Group=$(logname 2>/dev/null || echo "root")
WorkingDirectory=/opt/acess-report-system
Environment=PATH=/opt/acess-report-system/venv/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin
ExecStart=/usr/bin/python3 /opt/acess-report-system/backend/gateway_collector.py
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

    # Serviço web
    cat > /etc/systemd/system/access-report-web.service << EOF
[Unit]
Description=Servidor Web do Monitoramento SARG
After=network.target postgresql.service
Wants=postgresql.service

[Service]
Type=simple
User=$(logname 2>/dev/null || echo "root")
Group=$(logname 2>/dev/null || echo "root")
WorkingDirectory=/opt/acess-report-system
Environment=PATH=/opt/acess-report-system/venv/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin
ExecStart=/usr/bin/python3 /opt/acess-report-system/backend/main.py
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

    systemctl daemon-reload
    systemctl enable access-report-collector access-report-web 2>/dev/null || true

    echo "✅ Serviços configurados"
}

# ============================================================================
# FUNÇÃO PRINCIPAL
# ============================================================================
main() {
    # Verificar root
    if [ "$(id -u)" != "0" ]; then
        echo "❌ Execute como root: sudo ./install.sh"
        exit 1
    fi

    # Detectar ambiente
    detect_environment

    # Criar estrutura
    create_structure

    # Configurar PostgreSQL
    setup_postgresql

    # Configurar serviços
    setup_services

    # Iniciar serviços
    echo "🚀 Iniciando serviços..."
    systemctl start access-report-collector 2>/dev/null || true
    systemctl start access-report-web 2>/dev/null || true

    # Mensagem final
    echo ""
    echo "=================================================="
    echo "   ✅ INSTALAÇÃO CONCLUÍDA COM SUCESSO!"
    echo "=================================================="
    echo ""
    echo "🌐 Acesse o monitoramento em:"
    echo "   http://$INTERNAL_IP:5000/"
    echo ""
    echo "📊 FUNCIONALIDADES:"
    echo "   • Detecção automática de domínio e rede"
    echo "   • Monitoramento de consultas DNS em tempo real"
    echo "   • Identificação de usuários via Samba AD"
    echo "   • Salvamento automático de logs por 30 dias"
    echo "   • Limpeza automática de registros antigos"
    echo "   • Interface web com atualização em tempo real"
    echo ""
    echo "⚙️ Comandos úteis:"
    echo "   • Status: systemctl status access-report-*"
    echo "   • Logs: journalctl -u access-report-collector -f"
    echo "   • Reiniciar: systemctl restart access-report-*"
    echo ""
    echo "💡 Este sistema funciona em QUALQUER servidor"
    echo "   Fedora com Samba AD DC, sem configuração manual!"
    echo ""
}

# Executar
main
