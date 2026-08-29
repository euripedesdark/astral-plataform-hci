#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Desinstalador Completo - Astral Platform HCI
Uso: sudo python3 uninstall.py

Remove completamente todos os serviços, pacotes, bancos de dados,
configurações de proxy, regras de firewall e reseta o repositório Git
puxando a versão mais limpa diretamente do repositório remoto.
"""

import os
import sys
import subprocess
import shutil

APP_DIR = os.path.dirname(os.path.abspath(__file__))

def run_cmd(cmd, ignore_error=True):
    print(f"[SISTEMA] Executando: {cmd}")
    res = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    if res.returncode != 0 and not ignore_error:
        print(f"[AVISO] Comando retornou erro: {res.stderr.strip()}")
    return res.returncode == 0

def main():
    if os.geteuid() != 0:
        print("[ERRO] Este script de desinstalação deve ser executado com sudo.")
        print("Uso correto: sudo python3 uninstall.py")
        sys.exit(1)

    print("\n" + "="*60)
    print("[DESINSTALAÇÃO] Removendo todos os componentes do Astral Platform...")
    print("="*60 + "\n")

    # 1. Para e remove o serviço do Spring Boot (systemd)
    print("\n--- 1. Removendo serviço do Spring Boot ---")
    run_cmd("systemctl stop astral-platform.service")
    run_cmd("systemctl disable astral-platform.service")
    if os.path.exists("/etc/systemd/system/astral-platform.service"):
        os.remove("/etc/systemd/system/astral-platform.service")
        print("[OK] Arquivo systemd do astral-platform removido.")
    run_cmd("systemctl daemon-reload")

    # 2. Para e remove o Nginx e suas configurações
    print("\n--- 2. Removendo Nginx ---")
    run_cmd("systemctl stop nginx")
    run_cmd("systemctl disable nginx")
    run_cmd("dnf remove -y nginx || apt-get purge -y nginx || pacman -R --noconfirm nginx")
    run_cmd("rm -f /etc/nginx/conf.d/astral.conf")
    run_cmd("rm -f /etc/nginx/sites-available/astral.conf")
    run_cmd("rm -f /etc/nginx/sites-enabled/astral.conf")
    run_cmd("systemctl restart nginx", ignore_error=True)

    # 3. Para e remove o PostgreSQL e apaga os diretórios de dados
    print("\n--- 3. Removendo PostgreSQL e Bases de Dados ---")
    run_cmd("systemctl stop postgresql || systemctl stop postgresql-server")
    run_cmd("systemctl disable postgresql || systemctl disable postgresql-server")
    run_cmd("dnf remove -y postgresql postgresql-server postgresql-contrib || apt-get purge -y postgresql postgresql-contrib || pacman -R --noconfirm postgresql")
    run_cmd("rm -rf /var/lib/pgsql/data")
    run_cmd("rm -rf /var/lib/postgres/data")
    run_cmd("rm -rf /var/lib/pgsql")

    # 4. Remove Maven, Oracle JDK 21 e Node.js/pacotes globais
    print("\n--- 4. Removendo Compiladores e Runtimes (Java, Maven, Node.js) ---")
    run_cmd("dnf remove -y maven || apt-get purge -y maven || pacman -R --noconfirm maven")
    run_cmd("dnf remove -y jdk-21* oracle-java* || dpkg -P jdk-21 || true")
    run_cmd("rm -rf /opt/jdk-21*")
    run_cmd("npm uninstall -g pg express cors 2>/dev/null || true")
    run_cmd("dnf remove -y nodejs npm || apt-get purge -y nodejs npm || pacman -R --noconfirm nodejs npm")

    # 5. Remove dependências de Python globais (Flask, Psycopg2)
    print("\n--- 5. Removendo dependências globais de Python ---")
    run_cmd("pip3 uninstall -y flask psycopg2 psycopg2-binary 2>/dev/null || true")
    run_cmd("dnf remove -y python3-flask python3-psycopg2 python3-pip || apt-get purge -y python3-flask python3-psycopg2 python3-pip || true")

    # 6. Limpa arquivos gerados pelo Maven e compilação na raiz do projeto
    print("\n--- 6. Removendo artefatos de build locais ---")
    artifacts = ["pom.xml", "target", "src", ".mvn", "mvnw", "mvnw.cmd"]
    for item in artifacts:
        target_path = os.path.join(APP_DIR, item)
        if os.path.isdir(target_path):
            shutil.rmtree(target_path, ignore_errors=True)
            print(f"[OK] Diretório removido: {item}")
        elif os.path.isfile(target_path):
            os.remove(target_path)
            print(f"[OK] Arquivo removido: {item}")

    # 7. Restaura as portas no firewall (iptables)
    print("\n--- 7. Limpando regras de portas do iptables ---")
    ports_to_clean = [22, 80, 443, 3000, 5000, 5173, 5432, 8081, 9090]
    for p in ports_to_clean:
        while True:
            res = subprocess.run(['iptables', '-D', 'INPUT', '-p', 'tcp', '--dport', str(p), '-j', 'ACCEPT'], capture_output=True)
            if res.returncode != 0:
                break
    print("[OK] Portas limpas do iptables.")

    # 8. Reseta o repositório Git local e baixa tudo de novo do repositório remoto
    print("\n--- 8. Redefinindo repositório Git e baixando tudo do zero ---")
    if os.path.isdir(os.path.join(APP_DIR, ".git")):
        run_cmd(f"cd {APP_DIR} && git reset --hard HEAD")
        run_cmd(f"cd {APP_DIR} && git clean -fd")
        run_cmd(f"cd {APP_DIR} && git pull origin main")
        print("[OK] Repositório atualizado e limpo via Git com sucesso!")
    else:
        print("[AVISO] Diretório atual não é um repositório Git rastreado.")

    print("\n" + "="*60)
    print("[SUCESSO] Desinstalação concluída e projeto clonado/atualizado do Git!")
    print("O ambiente está totalmente limpo. Você já pode rodar:")
    print("sudo python3 install.py")
    print("="*60 + "\n")

if __name__ == '__main__':
    main()
