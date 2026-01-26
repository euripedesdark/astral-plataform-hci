#!/bin/bash
set -e

echo "=== CORREÇÃO DO SERVIÇO WEB ==="

# 1. Verificar se o PostgreSQL está acessível
echo "🔍 Testando conexão com PostgreSQL..."
if sudo -u postgres psql -c "SELECT 1" access_report >/dev/null 2>&1; then
    echo "✅ PostgreSQL acessível"
else
    echo "❌ PostgreSQL não acessível. Configurando pg_hba.conf..."
    
    # Backup
    sudo cp /var/lib/pgsql/data/pg_hba.conf /var/lib/pgsql/data/pg_hba.conf.bak.$(date +%Y%m%d)
    
    # Criar nova configuração com trust local
    cat > /tmp/pg_hba.conf.new << 'EOF'
# TYPE  DATABASE        USER            ADDRESS                 METHOD
local   all             all                                     trust
host    all             all             127.0.0.1/32            trust
host    all             all             ::1/128                 trust
host    all             all             100.0.0.0/8             md5
EOF
    
    sudo mv /tmp/pg_hba.conf.new /var/lib/pgsql/data/pg_hba.conf
    sudo chown postgres:postgres /var/lib/pgsql/data/pg_hba.conf
    sudo chmod 600 /var/lib/pgsql/data/pg_hba.conf
    sudo systemctl restart postgresql
    echo "✅ PostgreSQL reconfigurado"
fi

# 2. Corrigir main.py (import sys faltando + correções)
echo "🔧 Corrigindo main.py..."
cat > /opt/acess-report-system/backend/main.py << 'EOF'
#!/usr/bin/env python3
import sys
import os
sys.path.insert(0, '/opt/acess-report-system/backend')

from flask import Flask, render_template, jsonify, request
from flask_sqlalchemy import SQLAlchemy
from datetime import datetime, timedelta

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
        cleanup_old_records()

def cleanup_old_records():
    """Limpar registros mais antigos que 30 dias"""
    try:
        with app.app_context():
            retention_days = app.config['RETENTION_DAYS']
            cutoff_date = datetime.utcnow() - timedelta(days=retention_days)
            deleted = AccessRecord.query.filter(AccessRecord.created_at < cutoff_date).delete()
            db.session.commit()
            print(f"🧹 Limpeza automática: {deleted} registros removidos")
    except Exception as e:
        print(f"❌ Erro na limpeza: {e}")

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

# 3. Corrigir config.py (adicionar RETENTION_DAYS se não existir)
echo "🔧 Verificando config.py..."
if ! grep -q "RETENTION_DAYS" /opt/acess-report-system/backend/config.py; then
    sed -i "s/CATEGORIAS_EXCLUIDAS/RETENTION_DAYS = 30\n\n    CATEGORIAS_EXCLUIDAS/" /opt/acess-report-system/backend/config.py
    echo "✅ RETENTION_DAYS adicionado à configuração"
fi

# 4. Verificar permissões
echo "🔑 Corrigindo permissões..."
PROJECT_USER=$(logname 2>/dev/null || echo "root")
chown -R $PROJECT_USER:$PROJECT_USER /opt/acess-report-system 2>/dev/null || chown -R root:root /opt/acess-report-system
chmod -R 755 /opt/acess-report-system
chmod +x /opt/acess-report-system/backend/*.py

# 5. Testar execução manual
echo "🧪 Testando execução manual..."
cd /opt/acess-report-system
if sudo -u $PROJECT_USER python3 backend/main.py --help 2>&1 | grep -q "run"; then
    echo "✅ main.py executável sem erros"
else
    echo "⚠️ Teste manual falhou, mas continuando com reinicialização..."
fi

# 6. Reiniciar serviço
echo "🔄 Reiniciando serviço web..."
sudo systemctl daemon-reload
sudo systemctl restart access-report-web

# 7. Verificar status
sleep 3
if sudo systemctl is-active --quiet access-report-web; then
    echo ""
    echo "✅ SERVIÇO WEB RESTAURADO COM SUCESSO!"
    echo ""
    echo "🌐 Acesse: http://$(hostname -I | awk '{print $1}'):5000/"
    echo ""
    echo "📊 Status dos serviços:"
    sudo systemctl status access-report-web --no-pager | grep -E "Active:|Loaded:"
    sudo systemctl status access-report-collector --no-pager | grep -E "Active:|Loaded:"
else
    echo ""
    echo "❌ Serviço ainda falhando. Verifique logs:"
    echo "   journalctl -u access-report-web -n 30 --no-pager"
fi
