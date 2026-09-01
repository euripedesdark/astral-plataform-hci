#!/bin/bash
# Desinstalador completo da Astral Platform
# Uso: sudo ./uninstall.sh

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

if [[ $EUID -ne 0 ]]; then
   echo -e "${RED}ERRO: Este script deve ser executado como root${NC}"
   exit 1
fi

echo -e "${YELLOW}===========================================${NC}"
echo -e "${YELLOW}  DESINSTALADOR ASTRAL PLATFORM${NC}"
echo -e "${YELLOW}===========================================${NC}"
echo ""

# Confirmação
read -p "Isso vai remover TODOS os componentes da Astral Platform. Continuar? (s/N): " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Ss]$ ]]; then
    echo "Cancelado."
    exit 0
fi

echo -e "${GREEN}[1/8] Parando serviços...${NC}"
systemctl stop astral-platform 2>/dev/null || true
systemctl stop astral-firewall 2>/dev/null || true
systemctl stop postgresql 2>/dev/null || true
systemctl stop nginx 2>/dev/null || true

echo -e "${GREEN}[2/8] Removendo serviços do systemd...${NC}"
systemctl disable astral-platform 2>/dev/null || true
systemctl disable astral-firewall 2>/dev/null || true
rm -f /etc/systemd/system/astral-platform.service
rm -f /etc/systemd/system/astral-firewall.service
systemctl daemon-reload

echo -e "${GREEN}[3/8] Removendo diretórios de instalação...${NC}"
rm -rf /opt/astral-platform
rm -rf /opt/astral-firewall
rm -rf /etc/astral

echo -e "${GREEN}[4/8] Removendo banco de dados e certificados...${NC}"
if command -v psql &> /dev/null; then
    sudo -u postgres psql -c "DROP DATABASE IF EXISTS astral;" 2>/dev/null || true
    sudo -u postgres psql -c "DROP ROLE IF EXISTS astral;" 2>/dev/null || true
fi

echo -e "${GREEN}[5/8] Removendo regras de firewall...${NC}"
iptables -F 2>/dev/null || true
iptables -X 2>/dev/null || true
iptables -t nat -F 2>/dev/null || true
iptables -t nat -X 2>/dev/null || true
rm -f /etc/sysconfig/iptables 2>/dev/null || true
rm -f /etc/iptables/rules.v4 2>/dev/null || true

echo -e "${GREEN}[6/8] Removendo pacotes instalados...${NC}"
if command -v dnf &> /dev/null; then
    # RHEL/Fedora/CentOS
    dnf remove -y postgresql postgresql-server postgresql-contrib 2>/dev/null || true
    dnf remove -y iptables-services ipset 2>/dev/null || true
    dnf remove -y nodejs npm 2>/dev/null || true
elif command -v apt-get &> /dev/null; then
    # Debian/Ubuntu
    apt-get purge -y postgresql postgresql-contrib 2>/dev/null || true
    apt-get purge -y iptables-persistent ipset 2>/dev/null || true
    apt-get purge -y nodejs npm 2>/dev/null || true
    apt-get autoremove -y 2>/dev/null || true
fi

echo -e "${GREEN}[7/8] Removendo Java Oracle (se instalado via pacote)...${NC}"
if command -v dnf &> /dev/null; then
    dnf remove -y jdk-21 2>/dev/null || true
elif command -v dpkg &> /dev/null; then
    dpkg --purge jdk-21 2>/dev/null || true
fi

# Remove Java instalado manualmente em /opt
rm -rf /opt/jdk-21* 2>/dev/null || true
rm -f /usr/bin/java 2>/dev/null || true

echo -e "${GREEN}[8/8] Limpando diretório de desenvolvimento...${NC}"
read -p "Remover também o diretório ~/astral-plataform-hci? (s/N): " -n 1 -r
echo
if [[ $REPLY =~ ^[Ss]$ ]]; then
    rm -rf ~/astral-plataform-hci
    echo "Diretório removido."
else
    echo "Diretório mantido."
fi

echo ""
echo -e "${GREEN}===========================================${NC}"
echo -e "${GREEN}  DESINSTALAÇÃO CONCLUÍDA!${NC}"
echo -e "${GREEN}===========================================${NC}"
echo ""
echo "Todos os componentes da Astral Platform foram removidos."
echo "Se você quiser reinstalar, execute: sudo ./install-base.sh"
