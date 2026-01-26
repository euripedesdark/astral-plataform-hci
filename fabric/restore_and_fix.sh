#!/bin/bash
set -e

echo "=== RESTAURAÇÃO CONSERVADORA DO SERVIÇO WEB ==="
echo "Preservando o que já funcionava..."

# 1. Backup da configuração atual
BACKUP_DIR="/tmp/acess-report-backup-$(date +%Y%m%d_%H%M%S)"
mkdir -p "$BACKUP_DIR"
cp -r /opt/acess-report-system "$BACKUP_DIR/" 2>/dev/null || true
echo "✅ Backup criado em: $BACKUP_DIR"

# 2. Diagnosticar erro EXATO
echo ""
echo "🔍 Diagnosticando erro..."
ERROR_LOG=$(sudo journalctl -u access-report-web -n 20 --no-pager 2>/dev/null | tail -20 || echo "Sem logs disponíveis")

if echo "$ERROR_LOG" | grep -q "No module named 'config'"; then
    echo "❌ ERRO: Módulo 'config' não encontrado (problema de importação)"
    FIX_TYPE="import"
elif echo "$ERROR_LOG" | grep -q "name 'sys' is not defined"; then
    echo "❌ ERRO: Módulo 'sys' não importado"
    FIX_TYPE="sys"
elif echo "$ERROR_LOG" | grep -q "connection failed"; then
    echo "❌ ERRO: Conexão com PostgreSQL falhou"
    FIX_TYPE="postgres"
else
    echo "⚠️  Erro não identificado automaticamente:"
    echo "$ERROR_LOG" | tail -10
    FIX_TYPE="unknown"
fi

# 3. Correção conservadora baseada no erro
case $FIX_TYPE in
    "import")
        echo ""
        echo "🔧 Corrigindo problema de importação (PYTHONPATH)..."
        
        # Restaurar main.py com import sys correto e PYTHONPATH
        cat > /opt/acess-report-system/backend/main.py << 'EOF'
#!/usr/bin/env python3
import sys
import os

# Adicionar diretório do projeto ao PYTHONPATH
sys.path.insert(0, '/opt/acess-report-system')
sys.path.insert(0, '/opt/acess-report-system/backend')

from flask import Flask, render_template, jsonify, request
from flask_sqlalchemy import SQLAlchemy
from datetime import datetime, timedelta

# Importações relativas corrigidas
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
    return jsonify([{
        'id': r.id,
        'timestamp': r.timestamp.strftime('%Y-%m-%d %H:%M:%S'),
        'user': r.user_name,
        'hostname': r.hostname,
        'ip_address': r.ip_address,
        'url': r.url,
        'title': r.title or 'Sem título',
        'category': r.category,
        'domain': r.domain
    } for r in records])

@app.route('/api/stats')
def get_stats():
    total = AccessRecord.query.count()
    today = datetime.now().replace(hour=0, minute=0, second=0, microsecond=0)
    today_count = AccessRecord.query.filter(AccessRecord.timestamp >= today).count()
    return jsonify({
        'total_accesses': total,
        'today_accesses': today_count,
        'last_update': datetime.now().strftime('%d/%m/%Y %H:%M:%S')
    })

if __name__ == "__main__":
    app.run(host='0.0.0.0', port=5000)
EOF
        ;;
    
    "sys")
        echo ""
        echo "🔧 Adicionando import sys ao main.py..."
        if ! grep -q "^import sys" /opt/acess-report-system/backend/main.py; then
            sed -i '1i import sys' /opt/acess-report-system/backend/main.py
        fi
        ;;
    
    "postgres")
        echo ""
        echo "🔧 Verificando configuração do PostgreSQL..."
        # Testar conexão
        if sudo -u postgres psql -c "SELECT 1" access_report >/dev/null 2>&1; then
            echo "✅ PostgreSQL OK"
        else
            echo "⚠️  PostgreSQL com problemas. Verificando pg_hba.conf..."
            grep -E "^(local|host)" /var/lib/pgsql/data/pg_hba.conf | head -5
        fi
        ;;
    
    *)
        echo ""
        echo "🔧 Aplicando correção genérica conservadora..."
        # Garantir que main.py tenha import sys no início
        if ! head -5 /opt/acess-report-system/backend/main.py | grep -q "import sys"; then
            echo "import sys" | cat - /opt/acess-report-system/backend/main.py > /tmp/main_fixed.py && mv /tmp/main_fixed.py /opt/acess-report-system/backend/main.py
        fi
        ;;
esac

# 4. Corrigir permissões mínimas
echo ""
echo "🔑 Corrigindo permissões mínimas..."
PROJECT_USER=$(logname 2>/dev/null || echo "root")
chown -R $PROJECT_USER:$PROJECT_USER /opt/acess-report-system 2>/dev/null || chown -R root:root /opt/acess-report-system
chmod +x /opt/acess-report-system/backend/*.py 2>/dev/null || true

# 5. Testar execução manual ANTES de reiniciar serviço
echo ""
echo "🧪 Testando execução manual (sem iniciar serviço)..."
cd /opt/acess-report-system
if sudo -u $PROJECT_USER timeout 5 python3 -c "import sys; sys.path.insert(0, '/opt/acess-report-system/backend'); from config import Config; print('✅ Config importado com sucesso')" 2>&1; then
    echo "✅ Módulo 'config' importado com sucesso"
else
    echo "❌ Falha ao importar 'config'. Corrigindo..."
    # Tentar importação alternativa
    if [ -f /opt/acess-report-system/backend/__init__.py ]; then
        touch /opt/acess-report-system/backend/__init__.py
    fi
fi

# 6. Reiniciar serviço
echo ""
echo "🔄 Reiniciando serviço web..."
sudo systemctl daemon-reload
sudo systemctl restart access-report-web

# 7. Verificar status
sleep 3
if sudo systemctl is-active --quiet access-report-web; then
    echo ""
    echo "✅ SERVIÇO RESTAURADO COM SUCESSO!"
    echo ""
    echo "🌐 Acesse: http://$(hostname -I | awk '{print $1}'):5000/"
    echo ""
    echo "📊 Status:"
    sudo systemctl status access-report-web --no-pager | grep -E "Active:|Loaded:"
else
    echo ""
    echo "❌ Serviço ainda falhando. Logs detalhados:"
    sudo journalctl -u access-report-web -n 15 --no-pager --since "1 minute ago"
    echo ""
    echo "💡 Dica: O erro mais comum é falta de 'import sys' no início do main.py"
    echo "   Execute manualmente para ver o erro exato:"
    echo "   cd /opt/acess-report-system && sudo -u $PROJECT_USER python3 backend/main.py"
fi
