#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Desinstalador da Astral Platform HCI
Remove todos os artefatos criados pelo install.py:
- Nginx (proxy reverso)
- PostgreSQL (banco de dados e diretório de dados)
- Oracle JDK 21 + Maven + dependências Spring Boot
- Node.js + pacotes npm globais (pg/express/cors)
- Python (Flask/Psycopg2)
- Arquivos de configuração gerados (pom.xml, application.properties, etc.)
- Regras injetadas no iptables
- JAVA_HOME em /etc/profile.d

Uso: sudo python3 uninstall.py
"""

import os
import sys
import subprocess
import shutil

# ============================================================
# UTILITÁRIOS
# ============================================================

def detect_distro():
    try:
        with open('/etc/os-release', 'r') as f:
            content = f.read().lower()
            if 'debian' in content or 'ubuntu' in content:
                return 'debian'
            elif 'rhel' in content or 'fedora' in content or 'almalinux' in content or 'centos' in content or 'rocky' in content:
                return 'rhel'
            elif 'arch' in content or 'manjaro' in content:
                return 'arch'
    except FileNotFoundError:
        pass
    return 'unknown'

def run(cmd, ignore_error=True):
    """Executa comando shell. Se ignore_error=True, não quebra em falhas."""
    r = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    if r.returncode != 0 and not ignore_error:
        print(f"    ⚠️  Comando falhou: {cmd}")
        if r.stderr.strip():
            print(f"       {r.stderr.strip()[:200]}")
    return r.returncode == 0

def step(msg):
    print(f"\n\033[1;36m→ {msg}\033[0m")

def ok(msg):
    print(f"  \033[32m✔ {msg}\033[0m")

def warn(msg):
    print(f"  \033[33m⚠ {msg}\033[0m")

def info(msg):
    print(f"  \033[34mℹ {msg}\033[0m")

def confirm():
    print("\n" + "="*70)
    print("\033[1;31m⚠️  ATENÇÃO: ESTA OPERAÇÃO É DESTRUTIVA!\033[0m")
    print("="*70)
    print("O desinstalador vai remover:")
    print("  • Nginx e suas configurações")
    print("  • PostgreSQL e TODO o conteúdo do banco de dados 'astral'")
    print("  • Oracle JDK 21, Maven e as dependências do Spring Boot")
    print("  • Node.js e pacotes npm globais (pg/express/cors)")
    print("  • Flask, Psycopg2 e arquivos de projeto (pom.xml, application.properties)")
    print("  • Regras injetadas no iptables pelas portas do instalador")
    print("  • JAVA_HOME de /etc/profile.d/java_home.sh")
    print("\n\033[1;31mEsta ação NÃO PODE ser desfeita.\033[0m")
    print("="*70)
    resp = input("\nDigite \033[1mSIM\033[0m (em maiúsculas) para confirmar: ")
    return resp.strip() == "SIM"

# ============================================================
# ETAPAS DE DESINSTALAÇÃO
# ============================================================

APP_DIR = os.path.dirname(os.path.abspath(__file__))
distro = detect_distro()

# ------- 1. NGINX -------
def remove_nginx():
    step("Parando e removendo Nginx + configurações")
    run("systemctl stop nginx")
    run("systemctl disable nginx")

    if distro == 'debian':
        run("DEBIAN_FRONTEND=noninteractive apt-get purge -y nginx nginx-common nginx-core")
        run("apt-get autoremove -y")
    elif distro == 'rhel':
        run("dnf remove -y nginx")
    elif distro == 'arch':
        run("pacman -Rns --noconfirm nginx")

    # Remove arquivos de configuração do projeto
    paths = [
        "/etc/nginx/conf.d/astral.conf",
        "/etc/nginx/sites-available/astral.conf",
        "/etc/nginx/sites-enabled/astral.conf",
    ]
    for p in paths:
        if os.path.exists(p):
            os.remove(p)
            info(f"Removido: {p}")
    ok("Nginx removido")

# ------- 2. POSTGRESQL -------
def remove_postgresql():
    step("Parando e removendo PostgreSQL + diretórios de dados")

    # Tenta parar todos os nomes possíveis do serviço
    for svc in ["postgresql", "postgresql-server", "postgresql-16", "postgresql-15", "postgresql-14"]:
        run(f"systemctl stop {svc}")
        run(f"systemctl disable {svc}")

    if distro == 'debian':
        run("DEBIAN_FRONTEND=noninteractive apt-get purge -y postgresql postgresql-*")
        run("apt-get autoremove -y")
    elif distro == 'rhel':
        run("dnf remove -y postgresql postgresql-server postgresql-contrib")
    elif distro == 'arch':
        run("pacman -Rns --noconfirm postgresql")

    # Remove diretórios de dados
    data_paths = ["/var/lib/pgsql", "/var/lib/postgres"]
    for p in data_paths:
        if os.path.exists(p):
            shutil.rmtree(p, ignore_errors=True)
            info(f"Removido: {p}")

    # Remove usuário 'postgres' do sistema (se existir)
    run("userdel -r postgres 2>/dev/null || true")
    ok("PostgreSQL removido")

# ------- 3. JAVA + MAVEN + NODE -------
def remove_java_maven_node():
    step("Removendo Oracle JDK 21, Maven e Node.js")

    # Maven
    if distro == 'debian':
        run("apt-get remove -y maven")
    elif distro == 'rhel':
        run("dnf remove -y maven")
    elif distro == 'arch':
        run("pacman -Rns --noconfirm maven")

    # Oracle JDK instalado pelo instalador
    run("dnf remove -y jdk-21* oracle-java* 2>/dev/null || true")
    run("dpkg -r jdk-21* 2>/dev/null || true")

    # Remove tarball extraído em /opt
    for d in [p for p in os.listdir("/opt") if p.startswith("jdk-21") or p.startswith("jdk-")]:
        shutil.rmtree(os.path.join("/opt", d), ignore_errors=True)
        info(f"Removido: /opt/{d}")

    # Remove links simbólicos do instalador
    for lnk in ["/usr/bin/java", "/usr/bin/javac"]:
        if os.path.islink(lnk):
            target = os.readlink(lnk)
            if "jdk-21" in target or "/opt/" in target:
                os.remove(lnk)
                info(f"Removido symlink: {lnk}")

    # Remove JAVA_HOME persistente
    jh_file = "/etc/profile.d/java_home.sh"
    if os.path.exists(jh_file):
        os.remove(jh_file)
        info(f"Removido: {jh_file}")

    # Node.js + pacotes npm globais
    run("npm uninstall -g pg express cors 2>/dev/null || true")
    if distro == 'debian':
        run("apt-get remove -y nodejs npm")
    elif distro == 'rhel':
        run("dnf remove -y nodejs nodejs-npm npm")
    elif distro == 'arch':
        run("pacman -Rns --noconfirm nodejs npm")
    ok("Java 21, Maven e Node.js removidos")

# ------- 4. PYTHON + ARQUIVOS DO PROJETO -------
def remove_python_and_project():
    step("Removendo Flask, Psycopg2 e arquivos gerados do projeto")

    # Pip uninstall (com --break-system-packages se necessário)
    run("pip3 uninstall -y flask psycopg2 psycopg2-binary --break-system-packages 2>/dev/null || "
        "pip3 uninstall -y flask psycopg2 psycopg2-binary 2>/dev/null || true")

    # Remove arquivos gerados na raiz do projeto
    files_to_remove = [
        os.path.join(APP_DIR, "pom.xml"),
        os.path.join(APP_DIR, "src", "main", "resources", "application.properties"),
        #os.path.join(APP_DIR, "fabric", "frontend", "install.html"),
    ]
    for f in files_to_remove:
        if os.path.exists(f):
            os.remove(f)
            info(f"Removido: {f}")

    # Remove diretórios Maven vazios
    maven_dirs = [
        os.path.join(APP_DIR, "target"),
        os.path.join(APP_DIR, "src"),
    ]
    for d in maven_dirs:
        if os.path.exists(d) and not os.listdir(d) if os.path.isdir(d) else False:
            shutil.rmtree(d, ignore_errors=True)
            info(f"Removido diretório vazio: {d}")
    ok("Artefatos Python e de projeto removidos")

# ------- 5. IPTABLES -------
def clean_iptables():
    step("Limpando portas injetadas no iptables")
    ports = [80, 443, 3000, 5000, 5173, 5432, 8081, 9090]
    cleaned = 0
    for p in ports:
        # Remove todas as ocorrências da regra
        while True:
            r = subprocess.run(
                ['iptables', '-D', 'INPUT', '-p', 'tcp', '--dport', str(p), '-j', 'ACCEPT'],
                capture_output=True
            )
            if r.returncode != 0:
                break
            cleaned += 1

    # Persiste a limpeza
    if cleaned > 0:
        if os.path.exists('/etc/iptables/rules.v4'):
            run("sh -c 'iptables-save > /etc/iptables/rules.v4'")
        elif os.path.exists('/etc/sysconfig/iptables'):
            run("sh -c 'iptables-save > /etc/sysconfig/iptables'")
        ok(f"{cleaned} regras removidas do iptables")
    else:
        info("Nenhuma regra do instalador encontrada no iptables")

# ------- 6. LIMPEZA FINAL -------
def final_cleanup():
    step("Limpeza final do sistema")
    if distro == 'debian':
        run("apt-get autoremove -y")
        run("apt-get autoclean")
    elif distro == 'rhel':
        run("dnf autoremove -y")
        run("dnf clean all")
    elif distro == 'arch':
        run("pacman -Rns $(pacman -Qdtq) --noconfirm 2>/dev/null || true")
    ok("Cache de pacotes limpo")

# ============================================================
# BLOCO PRINCIPAL
# ============================================================

if __name__ == '__main__':
    if os.geteuid() != 0:
        print("\033[1;31mERRO: Este script deve ser executado com sudo.\033[0m")
        print("Uso correto: \033[1msudo python3 uninstall.py\033[0m")
        sys.exit(1)

    print("\n" + "="*70)
    print("\033[1;31m  DESINSTALADOR ASTRAL PLATFORM HCI\033[0m")
    print("="*70)
    info(f"Distro detectada: \033[1m{distro}\033[0m")
    info(f"Diretório do projeto: \033[1m{APP_DIR}\033[0m")

    if not confirm():
        print("\n\033[33mOperação cancelada pelo usuário. Nada foi alterado.\033[0m")
        sys.exit(0)

    print("\n\033[1;33mIniciando desinstalação...\033[0m")

    try:
        remove_nginx()
        remove_postgresql()
        remove_java_maven_node()
        remove_python_and_project()
        clean_iptables()
        final_cleanup()

        print("\n" + "="*70)
        print("\033[1;32m✔ DESINSTALAÇÃO CONCLUÍDA COM SUCESSO!\033[0m")
        print("="*70)
        print("O sistema está limpo e pronto para uma nova execução do instalador.")
        print("\nComandos de validação rápida:")
        print("  nginx -v                  # deve dar 'command not found'")
        print("  psql --version            # deve dar 'command not found'")
        print("  java -version             # deve dar 'command not found'")
        print("  mvn -v                    # deve dar 'command not found'")
        print("  node -v                   # deve dar 'command not found'")
        print("  python3 -c 'import flask' # deve dar 'ModuleNotFoundError'")
        print("="*70 + "\n")

    except KeyboardInterrupt:
        print("\n\n\033[33m⚠️  Desinstalação interrompida pelo usuário (Ctrl+C).\033[0m")
        print("   O sistema pode estar em estado parcial. Execute novamente para completar.")
        sys.exit(130)
    except Exception as e:
        print(f"\n\033[1;31m❌ Erro inesperado durante a desinstalação: {e}\033[0m")
        sys.exit(1)
