#!/bin/bash
# Instalador base da Astral Platform
# Instala todas as dependências globalmente
# Uso: sudo ./install-base.sh
set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

if [[ $EUID -ne 0 ]]; then
    echo -e "${RED}ERRO: Este script deve ser executado como root${NC}"
    exit 1
fi

ASTRAL_GROUP="astral"
ASTRAL_USER="astral"

detect_distro() {
    if [ -f /etc/os-release ]; then
        . /etc/os-release
        if [[ "$ID" == "debian" || "$ID" == "ubuntu" ]]; then
            echo "debian"
        elif [[ "$ID" == "rhel" || "$ID" == "fedora" || "$ID" == "centos" || "$ID" == "rocky" ]]; then
            echo "rhel"
        elif [[ "$ID" == "arch" ]]; then
            echo "arch"
        else
            echo "unknown"
        fi
    else
        echo "unknown"
    fi
}

DISTRO=$(detect_distro)

echo -e "${BLUE}===========================================${NC}"
echo -e "${BLUE}  INSTALADOR BASE - ASTRAL PLATFORM${NC}"
echo -e "${BLUE}===========================================${NC}"
echo ""
echo -e "Distribuição detectada: ${YELLOW}$DISTRO${NC}"
echo ""

# Criar grupo e usuário astral
echo -e "${GREEN}[1/16] Criando grupo e usuário astral...${NC}"
if ! getent group $ASTRAL_GROUP &> /dev/null; then
    groupadd $ASTRAL_GROUP
    echo "Grupo '$ASTRAL_GROUP' criado."
else
    echo "Grupo '$ASTRAL_GROUP' já existe."
fi

if ! id -u $ASTRAL_USER &> /dev/null; then
    useradd -r -g $ASTRAL_GROUP -d /opt/astral -s /sbin/nologin $ASTRAL_USER
    echo "Usuário '$ASTRAL_USER' criado."
else
    echo "Usuário '$ASTRAL_USER' já existe."
fi

# Adicionar usuário atual ao grupo astral
CURRENT_USER=$(logname 2>/dev/null || echo "$SUDO_USER")
if [ -n "$CURRENT_USER" ] && [ "$CURRENT_USER" != "root" ]; then
    usermod -aG $ASTRAL_GROUP $CURRENT_USER 2>/dev/null || true
    echo "Usuário '$CURRENT_USER' adicionado ao grupo '$ASTRAL_GROUP'."
fi

# Criar diretórios globais
echo -e "${GREEN}[2/16] Criando estrutura de diretórios...${NC}"
mkdir -p /opt/astral-platform
mkdir -p /opt/astral-firewall
mkdir -p /opt/astral-proxy
mkdir -p /etc/astral/certs
mkdir -p /etc/astral/certs/proxy
chown -R root:$ASTRAL_GROUP /opt/astral-platform
chown -R root:$ASTRAL_GROUP /opt/astral-firewall
chown -R root:$ASTRAL_GROUP /opt/astral-proxy
chown -R root:$ASTRAL_GROUP /etc/astral
chmod 2770 /opt/astral-platform
chmod 2770 /opt/astral-firewall
chmod 2770 /opt/astral-proxy
chmod 2770 /etc/astral
echo "Diretórios criados com permissões adequadas."

# Sincronizar repositórios
echo -e "${GREEN}[3/16] Sincronizando repositórios de pacotes...${NC}"
case $DISTRO in
    debian)
        apt-get update -y
        ;;
    rhel)
        dnf makecache -y
        ;;
    arch)
        pacman -Sy
        ;;
esac

# Instalar PostgreSQL
echo -e "${GREEN}[4/16] Instalando PostgreSQL...${NC}"
case $DISTRO in
    debian)
        DEBIAN_FRONTEND=noninteractive apt-get install -y postgresql postgresql-contrib
        ;;
    rhel)
        dnf install -y postgresql postgresql-server postgresql-contrib
        ;;
    arch)
        pacman -S --noconfirm postgresql
        ;;
esac

# Inicializar PostgreSQL (RHEL)
if [ "$DISTRO" == "rhel" ]; then
    PG_DATA="/var/lib/pgsql/data"
    if [ ! -f "$PG_DATA/PG_VERSION" ]; then
        echo "Inicializando banco de dados PostgreSQL..."
        chown -R postgres:postgres /var/lib/pgsql
        /usr/bin/postgresql-setup --initdb
        chown -R postgres:postgres $PG_DATA
        chmod 700 $PG_DATA
    fi
fi

# Inicializar PostgreSQL (Arch)
if [ "$DISTRO" == "arch" ]; then
    PG_DATA="/var/lib/postgres/data"
    if [ ! -f "$PG_DATA/PG_VERSION" ]; then
        echo "Inicializando banco de dados PostgreSQL..."
        sudo -u postgres initdb -D $PG_DATA
    fi
fi

# Configurar PostgreSQL para escutar em todas as interfaces
echo "Configurando PostgreSQL..."
PG_DATA=""
if [ "$DISTRO" == "rhel" ]; then
    PG_DATA="/var/lib/pgsql/data"
elif [ "$DISTRO" == "debian" ]; then
    PG_DATA=$(sudo -u postgres psql -t -c "SHOW data_directory" | tr -d '[:space:]')
elif [ "$DISTRO" == "arch" ]; then
    PG_DATA="/var/lib/postgres/data"
fi

if [ -n "$PG_DATA" ] && [ -d "$PG_DATA" ]; then
    # Habilitar listen_addresses
    if ! grep -q "^listen_addresses" "$PG_DATA/postgresql.conf"; then
        echo "listen_addresses = '*'" >> "$PG_DATA/postgresql.conf"
    fi
    # Adicionar regra de acesso
    if ! grep -q "host.*all.*all.*127.0.0.1/32.*md5" "$PG_DATA/pg_hba.conf"; then
        sed -i "1i host all all 127.0.0.1/32 md5" "$PG_DATA/pg_hba.conf"
    fi
fi

# Habilitar e iniciar PostgreSQL
systemctl enable postgresql
systemctl start postgresql
echo "PostgreSQL instalado e configurado."

# Instalar iptables e ipset
echo -e "${GREEN}[5/16] Instalando iptables e ipset...${NC}"
case $DISTRO in
    debian)
        DEBIAN_FRONTEND=noninteractive apt-get install -y iptables-persistent ipset
        ;;
    rhel)
        dnf install -y iptables-services ipset
        ;;
    arch)
        pacman -S --noconfirm iptables-nft ipset
        ;;
esac
echo "iptables e ipset instalados."

# Instalar Node.js e npm
echo -e "${GREEN}[6/16] Instalando Node.js e npm...${NC}"
if ! command -v node &> /dev/null; then
    case $DISTRO in
        debian)
            DEBIAN_FRONTEND=noninteractive apt-get install -y nodejs npm curl
            ;;
        rhel)
            dnf install -y nodejs npm curl
            ;;
        arch)
            pacman -S --noconfirm nodejs npm curl
            ;;
    esac
    npm install -g pg express cors
    echo "Node.js e npm instalados."
else
    echo "Node.js já está instalado."
fi

# Instalar Oracle Java 25 LTS
echo -e "${GREEN}[7/16] Instalando Oracle Java 21 LTS...${NC}"
if ! java -version 2>&1 | grep -q "Oracle"; then
    JAVA_DIR="/usr/lib/jvm/jdk-21-oracle"
    case $DISTRO in
        debian)
            curl -s -L -o /tmp/jdk-21.deb https://download.oracle.com/java/21/latest/jdk-21_linux-x64_bin.deb
            dpkg -i /tmp/jdk-21.deb
            rm -f /tmp/jdk-21.deb
            ;;
        rhel)
            dnf install -y https://download.oracle.com/java/21/latest/jdk-21_linux-x64_bin.rpm
            ;;
        arch)
            curl -s -L -o /tmp/jdk-21.tar.gz https://download.oracle.com/java/21/latest/jdk-21_linux-x64_bin.tar.gz
            tar -xzf /tmp/jdk-21.tar.gz -C /opt/
            mv /opt/jdk-21* $JAVA_DIR
            rm -f /tmp/jdk-21.tar.gz
            ;;
    esac
    # Criar symlink global
    ln -sf $JAVA_DIR/bin/java /usr/bin/java
    ln -sf $JAVA_DIR/bin/javac /usr/bin/javac
    echo "Oracle Java 21 instalado em $JAVA_DIR"
else
    echo "Oracle Java já está instalado."
fi

# Instalar Maven
echo -e "${GREEN}[8/16] Instalando Apache Maven...${NC}"
if ! command -v mvn &> /dev/null; then
    case $DISTRO in
        debian)
            DEBIAN_FRONTEND=noninteractive apt-get install -y maven
            ;;
        rhel)
            dnf install -y maven
            ;;
        arch)
            pacman -S --noconfirm maven
            ;;
    esac
    echo "Maven instalado."
else
    echo "Maven já está instalado."
fi

# Instalar fontes Orbitron globalmente
echo -e "${GREEN}[9/16] Instalando fontes Orbitron...${NC}"
FONTS_DIR="/usr/share/fonts/truetype/astral"
mkdir -p $FONTS_DIR
curl -fsSL -o $FONTS_DIR/orbitron-bold.woff2 https://cdn.jsdelivr.net/fontsource/fonts/orbitron@latest/latin-700-normal.woff2
curl -fsSL -o $FONTS_DIR/orbitron-black.woff2 https://cdn.jsdelivr.net/fontsource/fonts/orbitron@latest/latin-900-normal.woff2
chown -R root:$ASTRAL_GROUP $FONTS_DIR
chmod 2775 $FONTS_DIR
chmod 0664 $FONTS_DIR/*.woff2

# Atualizar cache de fontes
if command -v fc-cache &> /dev/null; then
    fc-cache -f $FONTS_DIR
fi
echo "Fontes Orbitron instaladas em $FONTS_DIR"

# Configurar SELinux (se presente)
echo -e "${GREEN}[10/16] Ajustando SELinux...${NC}"
if command -v getenforce &> /dev/null; then
    setenforce 0 2>/dev/null || true
    if [ -f /etc/selinux/config ]; then
        sed -i 's/^SELINUX=.*/SELINUX=permissive/' /etc/selinux/config
    fi
    echo "SELinux configurado para permissive."
else
    echo "SELinux não está presente."
fi

# ============================================================
# NOVAS DEPENDÊNCIAS
# ============================================================

# Instalar Apache Traffic Server (para módulo proxy)
echo -e "${GREEN}[11/16] Instalando Apache Traffic Server...${NC}"
case $DISTRO in
    debian)
        DEBIAN_FRONTEND=noninteractive apt-get install -y trafficserver
        ;;
    rhel)
        dnf install -y epel-release 2>/dev/null || true
        dnf install -y trafficserver
        ;;
    arch)
        pacman -S --noconfirm trafficserver
        ;;
esac
systemctl enable trafficserver 2>/dev/null || true
echo "Apache Traffic Server instalado."

# Instalar Samba cliente + Kerberos (para Active Directory)
echo -e "${GREEN}[12/16] Instalando Samba cliente e Kerberos...${NC}"
case $DISTRO in
    debian)
        DEBIAN_FRONTEND=noninteractive apt-get install -y samba-common-bin krb5-user ldap-utils
        ;;
    rhel)
        dnf install -y samba-common-tools krb5-workstation openldap-clients
        ;;
    arch)
        pacman -S --noconfirm samba krb5 openldap
        ;;
esac
echo "Samba cliente e Kerberos instalados."

# Instalar Pi-hole (DNS/ad-blocker)
echo -e "${GREEN}[13/16] Instalando Pi-hole...${NC}"
if ! command -v pihole &> /dev/null; then
    echo "Instalando Pi-hole (instalação não-interativa)..."
    curl -sSL https://install.pi-hole.net | bash /dev/stdin --unattended
    systemctl enable pihole-FTL
    systemctl start pihole-FTL
    echo "Pi-hole instalado."
else
    echo "Pi-hole já está instalado."
fi

# Instalar Elasticsearch (para logs do proxy)
echo -e "${GREEN}[14/16] Instalando Elasticsearch...${NC}"
case $DISTRO in
    debian)
        wget -qO - https://artifacts.elastic.co/GPG-KEY-elasticsearch | apt-key add -
        echo "deb https://artifacts.elastic.co/packages/8.x/apt stable main" | tee -a /etc/apt/sources.list.d/elastic-8.x.list
        apt-get update
        DEBIAN_FRONTEND=noninteractive apt-get install -y elasticsearch
        ;;
    rhel)
        rpm --import https://artifacts.elastic.co/GPG-KEY-elasticsearch
        cat > /etc/yum.repos.d/elasticsearch.repo <<EOF
[elasticsearch]
name=Elasticsearch repository for 8.x packages
baseurl=https://artifacts.elastic.co/packages/8.x/yum
gpgcheck=1
gpgkey=https://artifacts.elastic.co/GPG-KEY-elasticsearch
enabled=1
autorefresh=1
type=rpm-md
EOF
        dnf install -y elasticsearch
        ;;
    arch)
        pacman -S --noconfirm elasticsearch
        ;;
esac
systemctl enable elasticsearch 2>/dev/null || true
echo "Elasticsearch instalado."

# Instalar QEMU/KVM/libvirt (para módulo VM/HCI)
echo -e "${GREEN}[15/16] Instalando QEMU/KVM/libvirt (VM/HCI)...${NC}"
case $DISTRO in
    debian)
        DEBIAN_FRONTEND=noninteractive apt-get install -y qemu-kvm libvirt-daemon-system libvirt-clients bridge-utils virt-manager cloud-init
        ;;
    rhel)
        dnf install -y qemu-kvm libvirt libvirt-daemon-kvm virt-install virt-manager bridge-utils cloud-init
        ;;
    arch)
        pacman -S --noconfirm qemu libvirt virt-manager bridge-utils cloud-init dnsmasq
        ;;
esac
systemctl enable libvirtd 2>/dev/null || true
systemctl start libvirtd 2>/dev/null || true
echo "QEMU/KVM/libvirt instalados."

# Instalar ClamAV (antivírus)
echo -e "${GREEN}[16/16] Instalando ClamAV...${NC}"
case $DISTRO in
    debian)
        DEBIAN_FRONTEND=noninteractive apt-get install -y clamav clamav-daemon
        systemctl stop clamav-freshclam
        freshclam
        systemctl start clamav-freshclam
        ;;
    rhel)
        dnf install -y epel-release 2>/dev/null || true
        dnf install -y clamav clamav-update clamd
        systemctl stop clamav-freshclam
        freshclam
        systemctl start clamav-freshclam
        ;;
    arch)
        pacman -S --noconfirm clamav
        systemctl stop clamav-freshclam
        freshclam
        systemctl start clamav-freshclam
        ;;
esac
systemctl enable clamav-daemon 2>/dev/null || systemctl enable clamd 2>/dev/null || true
echo "ClamAV instalado."

# Pré-download de dependências Maven (Reactor, Spring Boot, etc.)
echo -e "${BLUE}===========================================${NC}"
echo -e "${BLUE}  PRÉ-DOWNLOAD DE DEPENDÊNCIAS MAVEN${NC}"
echo -e "${BLUE}===========================================${NC}"
echo ""
echo "Baixando dependências do pom.xml para cache local..."
if [ -d "$HOME/astral-plataform-hci" ]; then
    cd "$HOME/astral-plataform-hci"
    if [ -f "pom.xml" ]; then
        sudo -u $CURRENT_USER mvn -B -q dependency:go-offline 2>/dev/null || echo "Aviso: Falha ao pré-baixar algumas dependências (serão baixadas no build)."
        echo "Dependências Maven pré-baixadas."
    fi
fi

echo ""
echo -e "${BLUE}===========================================${NC}"
echo -e "${BLUE}  INSTALAÇÃO BASE CONCLUÍDA!${NC}"
echo -e "${BLUE}===========================================${NC}"
echo ""
echo -e "${GREEN}Componentes instalados:${NC}"
echo "  ✓ PostgreSQL (banco de dados)"
echo "  ✓ iptables + ipset (firewall)"
echo "  ✓ Node.js + npm (runtime JavaScript)"
echo "  ✓ Oracle Java 21 LTS"
echo "  ✓ Apache Maven"
echo "  ✓ Fontes Orbitron"
echo "  ✓ Apache Traffic Server (proxy reverso/cache)"
echo "  ✓ Samba cliente + Kerberos (Active Directory)"
echo "  ✓ Pi-hole (DNS/ad-blocker)"
echo "  ✓ Elasticsearch (logs)"
echo "  ✓ QEMU/KVM/libvirt (VM/HCI)"
echo "  ✓ ClamAV (antivírus)"
echo ""
echo -e "${YELLOW}Próximos passos:${NC}"
echo "1. Faça logout e login novamente para aplicar as permissões de grupo"
echo "2. Execute os instaladores específicos dos módulos:"
echo "   - ./install-platform.sh"
echo "   - ./install-firewall.sh"
echo "   - ./install-proxy.sh"
echo ""
echo -e "${GREEN}Diretórios globais criados:${NC}"
echo "  /opt/astral-platform    (módulo principal)"
echo "  /opt/astral-firewall    (módulo firewall)"
echo "  /opt/astral-proxy       (módulo proxy)"
echo "  /etc/astral             (configurações e certificados)"
echo "  /usr/share/fonts/truetype/astral (fontes)"
