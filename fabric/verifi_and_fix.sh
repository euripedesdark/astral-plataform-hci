#!/bin/bash
set -e

echo "=== SISTEMA DE VERIFICAÇÃO E CORREÇÃO INTELIGENTE ==="
echo "Servidor: Fedora 42 na OVH"
echo "Domínio: astral.celeste"
echo "Ambiente: Samba AD DC com DNS Híbrido"
echo "=============================================="

# Variáveis de configuração
PROJECT_DIR="/home/fedora/acess-report-system"
VENV_DIR="$PROJECT_DIR/venv"
POSTGRES_DB="access_report"
POSTGRES_USER="admin"
POSTGRES_PASSWORD="Senha@123"
SERVICES=("access-report-collector" "access-report-web")

# Função para verificar se um comando existe
command_exists() {
    command -v "$1" &> /dev/null
}

# Função para verificar diretórios essenciais
verify_directories() {
    echo "📁 Verificando estrutura de diretórios..."
    REQUIRED_DIRS=(
        "backend"
        "frontend/static/css"
        "frontend/static/js"
        "frontend/templates"
        "instance"
        "blacklists"
        "sql"
        "logs"
        "reports/daily"
    )
    
    DIRS_CREATED=0
    for dir in "${REQUIRED_DIRS[@]}"; do
        if [ ! -d "$PROJECT_DIR/$dir" ]; then
            echo "   ⚠️ Diretório ausente: $dir"
            mkdir -p "$PROJECT_DIR/$dir"
            chmod 755 "$PROJECT_DIR/$dir"
            chown fedora:fedora "$PROJECT_DIR/$dir"
            ((DIRS_CREATED++))
        fi
    done
    
    if [ $DIRS_CREATED -eq 0 ]; then
        echo "   ✅ Todos os diretórios essenciais existem"
    else
        echo "   ✅ $DIRS_CREATED diretórios criados"
    fi
}

# Função para verificar arquivos essenciais
verify_files() {
    echo "📝 Verificando arquivos essenciais..."
    REQUIRED_FILES=(
        "backend/config.py"
        "backend/app.py"
        "backend/main.py"
        "backend/collector.py"
        "backend/gateway_collector.py"
        "backend/models.py"
        "backend/report_generator.py"
        "frontend/templates/index.html"
        "frontend/static/css/main.css"
        "frontend/static/js/realtime.js"
        "sql/setup_postgres.sql"
        "setup.sh"
        "start.sh"
        "requirements.txt"
        "fix_named_logs.sh"
        "setup_hybrid_dns.sh"
        "run_report_generator.sh"
    )
    
    MISSING_FILES=0
    for file in "${REQUIRED_FILES[@]}"; do
        if [ ! -f "$PROJECT_DIR/$file" ]; then
            echo "   ⚠️ Arquivo ausente: $file"
            ((MISSING_FILES++))
        fi
    done
    
    if [ $MISSING_FILES -eq 0 ]; then
        echo "   ✅ Todos os arquivos essenciais existem"
    else
        echo "   ❌ $MISSING_FILES arquivos essenciais estão ausentes"
        return 1
    fi
}

# Função para verificar dependências do sistema
verify_system_dependencies() {
    echo "📦 Verificando dependências do sistema..."
    SYSTEM_DEPS=(
        "python3"
        "python3-pip"
        "python3-virtualenv"
        "postgresql-server"
        "postgresql-contrib"
        "bind"
        "iptables-services"
        "git"
        "curl"
        "wget"
        "rsync"
        "libcap"
        "policycoreutils-python-utils"
        "setroubleshoot-server"
    )
    
    MISSING_DEPS=()
    for dep in "${SYSTEM_DEPS[@]}"; do
        if ! rpm -q "$dep" &> /dev/null; then
            MISSING_DEPS+=("$dep")
        fi
    done
    
    if [ ${#MISSING_DEPS[@]} -eq 0 ]; then
        echo "   ✅ Todas as dependências do sistema estão instaladas"
    else
        echo "   ⚠️ Dependências faltando: ${MISSING_DEPS[*]}"
        echo "   🔧 Instalando dependências..."
        sudo dnf install -y "${MISSING_DEPS[@]}"
        echo "   ✅ Dependências instaladas com sucesso"
    fi
}

# Função para verificar ambiente Python e dependências
verify_python_environment() {
    echo "🐍 Verificando ambiente Python..."
    
    # Verificar se o ambiente virtual existe
    if [ ! -d "$VENV_DIR" ]; then
        echo "   ⚠️ Ambiente virtual não encontrado"
        echo "   🔧 Criando ambiente virtual..."
        python3 -m venv "$VENV_DIR"
    else
        echo "   ✅ Ambiente virtual encontrado"
    fi
    
    # Ativar ambiente virtual para verificação
    source "$VENV_DIR/bin/activate"
    
    # Verificar dependências Python
    echo "   📦 Verificando dependências Python..."
    MISSING_PYTHON_DEPS=()
    while IFS= read -r dep; do
        if ! pip show "$dep" &> /dev/null; then
            MISSING_PYTHON_DEPS+=("$dep")
        fi
    done < "$PROJECT_DIR/requirements.txt"
    
    if [ ${#MISSING_PYTHON_DEPS[@]} -eq 0 ]; then
        echo "   ✅ Todas as dependências Python estão instaladas"
    else
        echo "   ⚠️ Dependências Python faltando: ${MISSING_PYTHON_DEPS[*]}"
        echo "   🔧 Instalando dependências Python..."
        pip install -r "$PROJECT_DIR/requirements.txt"
        echo "   ✅ Dependências Python instaladas com sucesso"
    fi
    
    # Desativar ambiente virtual
    deactivate
}

# Função para verificar PostgreSQL
verify_postgresql() {
    echo "🐘 Verificando PostgreSQL..."
    
    # Verificar se o serviço está em execução
    if ! sudo systemctl is-active --quiet postgresql; then
        echo "   ⚠️ Serviço PostgreSQL não está em execução"
        echo "   🔧 Iniciando serviço PostgreSQL..."
        sudo systemctl start postgresql
        sudo systemctl enable postgresql
    else
        echo "   ✅ Serviço PostgreSQL está em execução"
    fi
    
    # Verificar se o banco de dados e usuário existem
    DB_EXISTS=$(sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='$POSTGRES_DB'")
    USER_EXISTS=$(sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='$POSTGRES_USER'")
    
    if [ -z "$DB_EXISTS" ] || [ -z "$USER_EXISTS" ]; then
        echo "   ⚠️ Banco de dados ou usuário PostgreSQL não configurados"
        echo "   🔧 Configurando PostgreSQL..."
        
        # Criar usuário se não existir
        if [ -z "$USER_EXISTS" ]; then
            sudo -u postgres psql -c "CREATE USER $POSTGRES_USER WITH PASSWORD '$POSTGRES_PASSWORD' SUPERUSER;"
        fi
        
        # Criar banco de dados se não existir
        if [ -z "$DB_EXISTS" ]; then
            sudo -u postgres psql -c "CREATE DATABASE $POSTGRES_DB OWNER $POSTGRES_USER;"
        fi
        
        # Executar script de setup do banco
        if [ -f "$PROJECT_DIR/sql/setup_postgres.sql" ]; then
            sudo -u postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f "$PROJECT_DIR/sql/setup_postgres.sql"
            echo "   ✅ Banco de dados configurado com sucesso"
        else
            echo "   ❌ Arquivo de setup do banco não encontrado"
            return 1
        fi
    else
        echo "   ✅ Banco de dados e usuário PostgreSQL configurados"
    fi
}

# Função para verificar logs do BIND (named)
verify_bind_logs() {
    echo "🌐 Verificando configuração de logs do BIND (named)..."
    
    # Verificar se o serviço named está em execução
    if ! sudo systemctl is-active --quiet named; then
        echo "   ⚠️ Serviço named (BIND) não está em execução"
        echo "   🔧 Iniciando serviço named..."
        sudo systemctl start named
        sudo systemctl enable named
    else
        echo "   ✅ Serviço named (BIND) está em execução"
    fi
    
    # Verificar se o diretório de logs existe
    NAMED_LOG_DIR="/var/log/named"
    if [ ! -d "$NAMED_LOG_DIR" ]; then
        echo "   ⚠️ Diretório de logs do named não encontrado"
        echo "   🔧 Criando diretório de logs do named..."
        sudo mkdir -p "$NAMED_LOG_DIR"
        sudo chown named:named "$NAMED_LOG_DIR"
        sudo chmod 755 "$NAMED_LOG_DIR"
    fi
    
    # Verificar se o arquivo de log existe
    NAMED_LOG_FILE="$NAMED_LOG_DIR/query.log"
    if [ ! -f "$NAMED_LOG_FILE" ]; then
        echo "   ⚠️ Arquivo de log do named não encontrado"
        echo "   🔧 Criando arquivo de log do named..."
        sudo touch "$NAMED_LOG_FILE"
        sudo chown named:named "$NAMED_LOG_FILE"
        sudo chmod 644 "$NAMED_LOG_FILE"
    fi
    
    # Verificar se a configuração de logging está no named.conf
    NAMED_CONF="/etc/named.conf"
    if ! grep -q "channel query_log" "$NAMED_CONF"; then
        echo "   ⚠️ Configuração de logging não encontrada no named.conf"
        echo "   🔧 Adicionando configuração de logging ao named.conf..."
        
        # Fazer backup do arquivo original
        sudo cp "$NAMED_CONF" "${NAMED_CONF}.bak.$(date +%Y%m%d)"
        
        # Adicionar configuração de logging
        cat >> "$NAMED_CONF" << 'EOF'

logging {
    channel query_log {
        file "/var/log/named/query.log" versions 3 size 5m;
        severity info;
        print-time yes;
        print-category yes;
    };
    category queries { query_log; };
};
EOF

        # Reiniciar serviço named
        sudo systemctl restart named
        echo "   ✅ Configuração de logging atualizada"
    else
        echo "   ✅ Configuração de logging do named está configurada"
    fi
}

# Função para verificar serviços systemd
verify_services() {
    echo "⚙️ Verificando serviços systemd..."
    
    SERVICES_CREATED=0
    for service in "${SERVICES[@]}"; do
        SERVICE_FILE="/etc/systemd/system/$service.service"
        
        if [ ! -f "$SERVICE_FILE" ]; then
            echo "   ⚠️ Serviço não configurado: $service"
            
            if [ "$service" == "access-report-collector" ]; then
                echo "   🔧 Configurando serviço access-report-collector..."
                sudo tee "$SERVICE_FILE" > /dev/null << 'EOF'
[Unit]
Description=SARG Style - Coletor de Tráfego DNS Híbrido
After=network.target samba-ad-dc.service named.service postgresql.service
Wants=samba-ad-dc.service named.service postgresql.service

[Service]
Type=simple
User=fedora
Group=fedora
WorkingDirectory=/home/fedora/acess-report-system
Environment=PATH=/home/fedora/acess-report-system/venv/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin
Environment=HOME=/home/fedora
Environment=USER=fedora
ExecStart=/home/fedora/acess-report-system/venv/bin/python /home/fedora/acess-report-system/backend/gateway_collector.py
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal
SyslogIdentifier=access-report-collector

[Install]
WantedBy=multi-user.target
EOF
            elif [ "$service" == "access-report-web" ]; then
                echo "   🔧 Configurando serviço access-report-web..."
                sudo tee "$SERVICE_FILE" > /dev/null << 'EOF'
[Unit]
Description=SARG Style - Servidor Web de Relatórios
After=network.target access-report-collector.service
Wants=access-report-collector.service

[Service]
Type=simple
User=fedora
Group=fedora
WorkingDirectory=/home/fedora/acess-report-system
Environment=PATH=/home/fedora/acess-report-system/venv/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin
Environment=HOME=/home/fedora
Environment=USER=fedora
ExecStart=/home/fedora/acess-report-system/venv/bin/python /home/fedora/acess-report-system/backend/main.py
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal
SyslogIdentifier=access-report-web

[Install]
WantedBy=multi-user.target
EOF
            fi
            
            ((SERVICES_CREATED++))
        else
            echo "   ✅ Serviço configurado: $service"
        fi
    done
    
    # Recarregar daemon do systemd se algum serviço foi criado
    if [ $SERVICES_CREATED -gt 0 ]; then
        echo "   🔧 Recarregando daemon do systemd..."
        sudo systemctl daemon-reload
        
        # Habilitar e iniciar serviços
        for service in "${SERVICES[@]}"; do
            sudo systemctl enable "$service"
            sudo systemctl start "$service"
        done
        echo "   ✅ $SERVICES_CREATED serviços configurados e iniciados"
    else
        echo "   ✅ Todos os serviços systemd estão configurados"
    fi
}

# Função para verificar e corrigir permissões
verify_permissions() {
    echo "🔑 Verificando e corrigindo permissões..."
    
    # Corrigir permissões do projeto
    echo "   🔧 Corrigindo permissões do projeto..."
    sudo chown -R fedora:fedora "$PROJECT_DIR"
    sudo chmod -R 755 "$PROJECT_DIR"
    find "$PROJECT_DIR" -name "*.sh" -exec chmod +x {} \;
    find "$PROJECT_DIR/venv/bin" -type f -exec chmod +x {} \;
    
    # Corrigir permissões do tcpdump
    echo "   🔧 Corrigindo permissões do tcpdump..."
    if command_exists setcap; then
        sudo setcap cap_net_raw,cap_net_admin+eip /usr/sbin/tcpdump
        echo "   ✅ Permissões do tcpdump configuradas"
    else
        echo "   ❌ Comando setcap não encontrado. Instale o pacote libcap."
    fi
    
    # Verificar contexto SELinux
    echo "   🔍 Verificando contexto SELinux..."
    SELINUX_CONTEXT=$(ls -Zd "$PROJECT_DIR" | awk '{print $4}')
    
    if [[ "$SELINUX_CONTEXT" != "httpd_sys_rw_content_t" ]]; then
        echo "   ⚠️ Contexto SELinux incorreto para o projeto"
        echo "   🔧 Corrigindo contexto SELinux..."
        
        # Verificar se o semanage está instalado
        if command_exists semanage; then
            sudo semanage fcontext -a -t httpd_sys_rw_content_t "$PROJECT_DIR(/.*)?"
            sudo restorecon -Rv "$PROJECT_DIR"
            echo "   ✅ Contexto SELinux corrigido"
        else
            echo "   ❌ Comando semanage não encontrado. Instale o pacote policycoreutils-python-utils."
        fi
    else
        echo "   ✅ Contexto SELinux está correto"
    fi
}

# Função para testar o sistema
test_system() {
    echo "🔬 Testando o sistema..."
    
    # Testar API
    echo "   🌐 Testando API..."
    API_RESPONSE=$(curl -s http://localhost:5000/api/stats || echo "failed")
    if [[ "$API_RESPONSE" == *"total_accesses"* ]]; then
        echo "   ✅ API está respondendo corretamente"
    else
        echo "   ❌ API não está respondendo corretamente"
        return 1
    fi
    
    # Testar interface web
    echo "   🖥️ Testando interface web..."
    WEB_RESPONSE=$(curl -s http://localhost:5000/ || echo "failed")
    if [[ "$WEB_RESPONSE" == *"<title>Relatório de Acessos - OVH Fedora42</title>"* ]]; then
        echo "   ✅ Interface web está respondendo corretamente"
    else
        echo "   ❌ Interface web não está respondendo corretamente"
        return 1
    fi
    
    # Verificar status dos serviços
    echo "   ⚙️ Verificando status dos serviços..."
    ALL_SERVICES_OK=true
    for service in "${SERVICES[@]}"; do
        if sudo systemctl is-active --quiet "$service"; then
            echo "   ✅ Serviço $service está ativo"
        else
            echo "   ❌ Serviço $service não está ativo"
            ALL_SERVICES_OK=false
        fi
    done
    
    if $ALL_SERVICES_OK; then
        echo "   ✅ Todos os serviços estão ativos"
    else
        return 1
    fi
    
    return 0
}

# Função principal
main() {
    # Verificar se está sendo executado como root
    if [ "$(id -u)" != "0" ]; then
        echo "❌ Este script precisa ser executado como root"
        exit 1
    fi
    
    echo "🚀 Iniciando verificação e correção inteligente..."
    
    # Verificar e criar diretórios
    verify_directories
    
    # Verificar arquivos essenciais
    if ! verify_files; then
        echo "❌ Alguns arquivos essenciais estão ausentes. A correção falhou."
        exit 1
    fi
    
    # Verificar dependências do sistema
    verify_system_dependencies
    
    # Verificar ambiente Python
    verify_python_environment
    
    # Verificar PostgreSQL
    verify_postgresql
    
    # Verificar logs do BIND
    verify_bind_logs
    
    # Verificar serviços systemd
    verify_services
    
    # Verificar e corrigir permissões
    verify_permissions
    
    # Testar o sistema
    if test_system; then
        echo ""
        echo "🎉 ✅ SISTEMA TOTALMENTE FUNCIONAL!"
        echo "=================================="
        echo ""
        echo "📊 STATUS DO SISTEMA:"
        echo "   ✅ Estrutura de diretórios: OK"
        echo "   ✅ Arquivos essenciais: OK"
        echo "   ✅ Dependências do sistema: OK"
        echo "   ✅ Ambiente Python: OK"
        echo "   ✅ PostgreSQL: OK"
        echo "   ✅ Logs do BIND: OK"
        echo "   ✅ Serviços systemd: OK"
        echo "   ✅ Permissões: OK"
        echo "   ✅ API: OK"
        echo "   ✅ Interface web: OK"
        echo ""
        echo "🌐 ACESSO AO SISTEMA:"
        echo "   • Interface Web: http://100.100.100.1:5000/"
        echo "   • API: http://100.100.100.1:5000/api/stats"
        echo ""
        echo "⚙️ COMANDOS ÚTEIS:"
        echo "   • Ver status dos serviços: sudo systemctl status access-report-*"
        echo "   • Ver logs do coletor: journalctl -u access-report-collector -f"
        echo "   • Ver logs do servidor web: journalctl -u access-report-web -f"
        echo "   • Reiniciar serviços: sudo systemctl restart access-report-*"
        echo ""
        echo "💡 O sistema está monitorando seu ambiente DNS Híbrido (Samba + BIND)"
        echo "   e identificando usuários do domínio astral.celeste que acessam a internet."
        echo ""
    else
        echo ""
        echo "🔧 CORREÇÃO PARCIAL CONCLUÍDA"
        echo "==============================="
        echo ""
        echo "⚠️ O sistema foi parcialmente corrigido, mas ainda há problemas."
        echo "Execute os seguintes comandos para resolver os problemas restantes:"
        echo ""
        echo "1. Verificar logs dos serviços:"
        echo "   journalctl -u access-report-collector -f --since \"1 minute ago\""
        echo "   journalctl -u access-report-web -f --since \"1 minute ago\""
        echo ""
        echo "2. Verificar erros específicos:"
        echo "   sudo systemctl status access-report-*"
        echo ""
        echo "3. Corrigir problemas manualmente conforme necessário"
        echo ""
    fi
}

# Executar função principal
main
