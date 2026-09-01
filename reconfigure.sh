#!/bin/bash
# Script de Limpeza e Reconfiguração - Astral Platform
# Remove toda configuração antiga e reconfigura do zero
# NÃO remove pacotes instalados (Java, PostgreSQL, Maven, etc.)
#
# Uso: sudo ./reconfigure.sh

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

ASTRAL_GROUP="astral"

if [[ $EUID -ne 0 ]]; then
   echo -e "${RED}ERRO: Este script deve ser executado como root${NC}"
   exit 1
fi

echo -e "${BLUE}===========================================${NC}"
echo -e "${BLUE}  LIMPEZA E RECONFIGURAÇÃO - ASTRAL PLATFORM${NC}"
echo -e "${BLUE}===========================================${NC}"
echo ""

# Confirmação
read -p "Isso vai remover TODA configuração da Astral Platform e reconfigurar do zero. Pacotes NÃO serão removidos. Continuar? (s/N): " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Ss]$ ]]; then
    echo "Cancelado."
    exit 0
fi

echo -e "${GREEN}[1/8] Parando serviços...${NC}"
systemctl stop astral-platform 2>/dev/null || true
systemctl stop astral-firewall 2>/dev/null || true
echo "Serviços parados."

echo -e "${GREEN}[2/8] Removendo serviços do systemd...${NC}"
systemctl disable astral-platform 2>/dev/null || true
systemctl disable astral-firewall 2>/dev/null || true
rm -f /etc/systemd/system/astral-platform.service
rm -f /etc/systemd/system/astral-firewall.service
systemctl daemon-reload
echo "Serviços removidos do systemd."

echo -e "${GREEN}[3/8] Limpando diretórios de deploy...${NC}"
rm -rf /opt/astral-platform
rm -rf /opt/astral-firewall
mkdir -p /opt/astral-platform
mkdir -p /opt/astral-firewall
chown root:$ASTRAL_GROUP /opt/astral-platform /opt/astral-firewall
chmod 2770 /opt/astral-platform /opt/astral-firewall
echo "Diretórios de deploy limpos e recriados."

echo -e "${GREEN}[4/8] Limpando configurações em /etc/astral...${NC}"
rm -rf /etc/astral
mkdir -p /etc/astral/certs
chown root:$ASTRAL_GROUP /etc/astral /etc/astral/certs
chmod 2770 /etc/astral /etc/astral/certs
echo "Configurações em /etc/astral limpas."

echo -e "${GREEN}[5/8] Limpando banco de dados PostgreSQL...${NC}"
# Remove tabelas do firewall
sudo -u postgres psql -d astral -c "DROP TABLE IF EXISTS firewall_rule CASCADE;" 2>/dev/null || true
sudo -u postgres psql -d astral -c "DROP TABLE IF EXISTS port_forward CASCADE;" 2>/dev/null || true
sudo -u postgres psql -d astral -c "DROP TABLE IF EXISTS masquerade_rule CASCADE;" 2>/dev/null || true
sudo -u postgres psql -d astral -c "DROP TABLE IF EXISTS zone CASCADE;" 2>/dev/null || true
sudo -u postgres psql -d astral -c "DROP TABLE IF EXISTS zone_interface CASCADE;" 2>/dev/null || true
sudo -u postgres psql -d astral -c "DROP TABLE IF EXISTS host_group CASCADE;" 2>/dev/null || true
sudo -u postgres psql -d astral -c "DROP TABLE IF EXISTS host_group_cidrs CASCADE;" 2>/dev/null || true
sudo -u postgres psql -d astral -c "DROP TABLE IF EXISTS port_group CASCADE;" 2>/dev/null || true
sudo -u postgres psql -d astral -c "DROP TABLE IF EXISTS port_group_ports CASCADE;" 2>/dev/null || true
sudo -u postgres psql -d astral -c "DROP TABLE IF EXISTS schedule CASCADE;" 2>/dev/null || true
sudo -u postgres psql -d astral -c "DROP TABLE IF EXISTS rate_limit_policy CASCADE;" 2>/dev/null || true
sudo -u postgres psql -d astral -c "DROP TABLE IF EXISTS auto_ban_rule CASCADE;" 2>/dev/null || true
sudo -u postgres psql -d astral -c "DROP TABLE IF EXISTS threat_list CASCADE;" 2>/dev/null || true
sudo -u postgres psql -d astral -c "DROP TABLE IF EXISTS audit_log CASCADE;" 2>/dev/null || true
sudo -u postgres psql -d astral -c "DROP TABLE IF EXISTS firewall_snapshot CASCADE;" 2>/dev/null || true
sudo -u postgres psql -d astral -c "DROP TABLE IF EXISTS firewall_state CASCADE;" 2>/dev/null || true
echo "Tabelas do firewall removidas."

echo -e "${GREEN}[6/8] Limpando configurações SSL do PostgreSQL...${NC}"
# Detecta o diretório de dados do PostgreSQL
PG_DATA=$(sudo -u postgres psql -t -c "SHOW data_directory" 2>/dev/null | tr -d '[:space:]')
if [ -z "$PG_DATA" ]; then
    PG_DATA="/var/lib/pgsql/data"
fi

if [ -d "$PG_DATA" ]; then
    # Remove linhas de SSL do postgresql.conf
    if [ -f "$PG_DATA/postgresql.conf" ]; then
        sed -i '/# mTLS Astral Platform/d' "$PG_DATA/postgresql.conf"
        sed -i '/^ssl = on$/d' "$PG_DATA/postgresql.conf"
        sed -i '/^ssl_ca_file/d' "$PG_DATA/postgresql.conf"
        sed -i '/^ssl_cert_file/d' "$PG_DATA/postgresql.conf"
        sed -i '/^ssl_key_file/d' "$PG_DATA/postgresql.conf"
    fi

    # Remove linhas do pg_hba.conf
    if [ -f "$PG_DATA/pg_hba.conf" ]; then
        sed -i '/# Astral Platform/d' "$PG_DATA/pg_hba.conf"
        sed -i '/^hostssl astral/d' "$PG_DATA/pg_hba.conf"
    fi

    # Remove certificados SSL do PostgreSQL
    rm -f "$PG_DATA/root.crt" "$PG_DATA/server.crt" "$PG_DATA/server.key"

    # Reinicia PostgreSQL para aplicar mudanças
    systemctl restart postgresql
    sleep 3
    echo "Configurações SSL do PostgreSQL limpas."
else
    echo "Diretório do PostgreSQL não encontrado, pulando limpeza SSL."
fi

echo -e "${GREEN}[7/8] Limpando regras de firewall e ipset...${NC}"
# Limpa rulesets do iptables relacionados ao astral
iptables -F ASTRAL_BANNED 2>/dev/null || true
iptables -X ASTRAL_BANNED 2>/dev/null || true
iptables -F astral-threats 2>/dev/null || true
iptables -X astral-threats 2>/dev/null || true

# Remove ipsets do astral
ipset destroy astral-threats 2>/dev/null || true
for ipset_name in $(ipset list -name 2>/dev/null | grep -E '^hg_|^astral-'); do
    ipset destroy "$ipset_name" 2>/dev/null || true
done

# Limpa regras persistentes (RHEL e Debian)
rm -f /etc/sysconfig/iptables
rm -f /etc/iptables/rules.v4
rm -f /etc/iptables/iptables.rules

echo "Regras de firewall e ipset limpas."

echo -e "${GREEN}[8/8] Limpando configurações de rede (NetworkConfig)...${NC}"
# Remove configuração do dnsmasq
rm -f /etc/dnsmasq.d/lan-dhcp.conf
# Remove proteção do resolv.conf
rm -f /etc/NetworkManager/conf.d/90-dns-none.conf
# Remove configuração de IP forwarding
rm -f /etc/sysctl.d/99-ipforward.conf

# Recarrega NetworkManager e dnsmasq se estiverem rodando
systemctl reload NetworkManager 2>/dev/null || true
systemctl restart dnsmasq 2>/dev/null || true

echo "Configurações de rede limpas."

echo ""
echo -e "${BLUE}===========================================${NC}"
echo -e "${BLUE}  LIMPEZA CONCLUÍDA!${NC}"
echo -e "${BLUE}===========================================${NC}"
echo ""
echo -e "${YELLOW}Agora você pode executar os instaladores:${NC}"
echo "  1. Instalador principal: sudo java -cp ... com.astral.tools.Installer"
echo "  2. Instalador do firewall: sudo java -cp ... com.astral.tools.InstallerFirewall"
echo "  3. Configurador de rede: sudo java -cp ... com.astral.tools.NetworkConfig"
echo ""
echo -e "${GREEN}Diretórios limpos e prontos para reconfiguração:${NC}"
echo "  /opt/astral-platform"
echo "  /opt/astral-firewall"
echo "  /etc/astral"
