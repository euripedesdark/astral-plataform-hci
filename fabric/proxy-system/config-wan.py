#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
config-wan.py

Este script configura a interface de WAN em sistemas baseados em Fedora/RHEL.
Garante a persistência das configurações de Rede via NetworkManager e Firewall.

Autor: Eurípedes Batista
LinkedIn: https://www.linkedin.com/in/euripedes-batista-14235229/
"""

import argparse
import subprocess
import sys
import shlex
import os
import re
import shutil
from typing import List, Optional

# Funções auxiliares
def run_command(cmd: str, dry_run: bool = False, check: bool = True) -> subprocess.CompletedProcess:
    """Executa um comando no shell."""
    print(f"{'[dry-run]' if dry_run else '+'} {cmd}")
    if dry_run:
        return subprocess.CompletedProcess(args=cmd, returncode=0, stdout=b"", stderr=b"")
    return subprocess.run(cmd, shell=True, check=check, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

def get_command_output(cmd: str, dry_run: bool = False) -> str:
    """Executa um comando e retorna a saída como string."""
    p = run_command(cmd, dry_run=dry_run, check=False)
    return p.stdout.decode().strip() if p.stdout else ""

def require_root():
    """Verifica se o script está sendo executado como root."""
    if os.geteuid() != 0:
        print("Este script precisa ser executado como root. Use sudo.", file=sys.stderr)
        sys.exit(1)

def ask_question(prompt: str, default: Optional[str] = None) -> str:
    """Faz uma pergunta ao usuário e retorna a resposta."""
    if default is not None:
        prompt = f"{prompt} [{default}]: "
    else:
        prompt = f"{prompt}: "
    val = input(prompt).strip()
    return val if val else (default if default is not None else "")

def ask_yes_no(prompt: str, default: bool = True) -> bool:
    """Faz uma pergunta de sim/não ao usuário."""
    d = "Y/n" if default else "y/N"
    r = input(f"{prompt} ({d}): ").strip().lower()
    if r == "":
        return default
    return r.startswith("y")

# Funções de validação
def is_valid_ip(ip: str) -> bool:
    """Valida um endereço IP."""
    return bool(re.match(r"^\d{1,3}(\.\d{1,3}){3}$", ip))

def is_valid_cidr(cidr: str) -> bool:
    """Valida uma máscara de rede CIDR."""
    return bool(re.match(r"^\d{1,3}(\.\d{1,3}){3}/\d{1,2}$", cidr))

def normalize_mac_address(mac: str) -> Optional[str]:
    """Normaliza um endereço MAC."""
    m = mac.strip().lower().replace(":", "")
    if re.fullmatch(r"[0-9a-f]{12}", m):
        return ":".join(m[i:i+2] for i in range(0, 12, 2))
    return None

# Funções de configuração
def apply_firewall_rules(dry_run: bool, wan_iface: str, lan_ifaces: List[str]):
    """Aplica um conjunto robusto de regras de firewall e salva a persistência."""
    print("\n== Aplicando regras avançadas de firewall ==")

    run_command("iptables -F", dry_run=dry_run)
    run_command("iptables -X", dry_run=dry_run)
    run_command("iptables -t nat -F", dry_run=dry_run)
    run_command("iptables -t nat -X", dry_run=dry_run)

    run_command("iptables -P INPUT DROP", dry_run=dry_run)
    run_command("iptables -P FORWARD DROP", dry_run=dry_run)
    run_command("iptables -P OUTPUT ACCEPT", dry_run=dry_run)

    run_command("iptables -A INPUT -i lo -j ACCEPT", dry_run=dry_run)
    run_command("iptables -A INPUT -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT", dry_run=dry_run)

    run_command("iptables -A INPUT -p tcp --tcp-flags ALL NONE -j DROP", dry_run=dry_run)
    run_command("iptables -A INPUT -p tcp ! --syn -m conntrack --ctstate NEW -j DROP", dry_run=dry_run)
    run_command("iptables -A INPUT -p tcp --tcp-flags ALL ALL -j DROP", dry_run=dry_run)

    run_command("iptables -N syn_flood", dry_run=dry_run)
    run_command("iptables -A syn_flood -m limit --limit 1/s --limit-burst 3 -j RETURN", dry_run=dry_run)
    run_command("iptables -A syn_flood -j DROP", dry_run=dry_run)
    run_command("iptables -A INPUT -p tcp --syn -j syn_flood", dry_run=dry_run)

    run_command("iptables -N port_scan", dry_run=dry_run)
    run_command("iptables -A port_scan -p tcp --tcp-flags SYN,ACK,FIN,RST RST -m limit --limit 1/s -j RETURN", dry_run=dry_run)
    run_command("iptables -A port_scan -j DROP", dry_run=dry_run)
    run_command("iptables -A INPUT -p tcp --tcp-flags SYN,ACK,FIN,RST RST -j port_scan", dry_run=dry_run)

    for lan_iface in lan_ifaces:
        run_command(f"iptables -A FORWARD -i {shlex.quote(lan_iface)} -o {shlex.quote(wan_iface)} -j ACCEPT", dry_run=dry_run)
        run_command(f"iptables -A FORWARD -i {shlex.quote(wan_iface)} -o {shlex.quote(lan_iface)} -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT", dry_run=dry_run)

    run_command(f"iptables -t nat -A POSTROUTING -o {shlex.quote(wan_iface)} -j MASQUERADE", dry_run=dry_run)

    run_command("iptables -A INPUT -m limit --limit 5/min -j LOG --log-prefix \"iptables-denied: \" --log-level 7", dry_run=dry_run)
    run_command("iptables -A INPUT -j DROP", dry_run=dry_run)

    # Salvando as regras de firewall (Persistência no Red Hat)
    print("\n== Salvando regras de firewall para persistência (/etc/sysconfig/iptables) ==")
    if shutil.which("iptables-save"):
        run_command("mkdir -p /etc/sysconfig && iptables-save > /etc/sysconfig/iptables", dry_run=dry_run)
        run_command("systemctl enable iptables || true", dry_run=dry_run, check=False)
        print("✅ Regras de firewall aplicadas e salvas.")

def disable_nm_dns_overwrite(dry_run: bool, dns_servers: List[str]):
    """Configura o NetworkManager para não sobrescrever o /etc/resolv.conf globalmente e gerencia symlinks."""
    print("\n== Protegendo /etc/resolv.conf contra alterações do NetworkManager ==")
    nm_conf_dir = "/etc/NetworkManager/conf.d"
    dns_conf_file = os.path.join(nm_conf_dir, "90-dns-none.conf")

    if not dry_run:
        os.makedirs(nm_conf_dir, exist_ok=True)
        with open(dns_conf_file, "w") as f:
            f.write("[main]\ndns=none\n")
        print(f"Arquivo {dns_conf_file} criado com sucesso (dns=none).")

        # Resolve problema de internet caindo por causa de symlink do systemd-resolved
        if os.path.islink("/etc/resolv.conf"):
            os.unlink("/etc/resolv.conf")

        with open("/etc/resolv.conf", "w") as f:
            for server in dns_servers:
                f.write(f"nameserver {server}\n")
        print("Arquivo /etc/resolv.conf recriado fisicamente com DNS estáticos.")

        run_command("systemctl reload NetworkManager", dry_run=dry_run)
    else:
        print(f"[dry-run] Criaria o arquivo {dns_conf_file} com a instrução '[main]\\ndns=none'")
        print("[dry-run] Desvincularia symlinks e recriaria o /etc/resolv.conf.")

def configure_wan_static(dry_run: bool, wan_iface: str, public_ip: str, public_mask: str, public_gw: str, mac_spoof: Optional[str], dns_servers: List[str], protect_dns: bool):
    """Configura a interface WAN utilizando NetworkManager de forma nativa para garantir persistência."""
    print(f"\n== Configurando WAN estática em {wan_iface} ==")
    cidr = f"{public_ip}/{public_mask}"

    if shutil.which("nmcli"):
        print("NetworkManager detectado. Configurando perfil para persistência...")
        get_con_cmd = f"nmcli -t -f NAME,DEVICE con show | awk -F: '$2==\"{wan_iface}\" {{print $1}}'"
        con_name = get_command_output(get_con_cmd, dry_run=dry_run)

        # Se não existir conexão, cria uma
        if not con_name:
            print(f"Aviso: Conexão para device {wan_iface} não encontrada. Criando nova conexão...")
            con_name = f"System_{wan_iface}"
            run_command(f"nmcli con add type ethernet ifname {shlex.quote(wan_iface)} con-name {shlex.quote(con_name)}", dry_run=dry_run)

        # Modifica o perfil para Manual, definindo IP e Gateway e garantindo a gravação em disco
        run_command(f"nmcli con mod {shlex.quote(con_name)} ipv4.method manual ipv4.addresses {shlex.quote(cidr)} ipv4.gateway {shlex.quote(public_gw)}", dry_run=dry_run)

        if mac_spoof:
            mac = normalize_mac_address(mac_spoof)
            if mac:
                print(f"Aplicando MAC spoof {mac} via NetworkManager")
                run_command(f"nmcli con mod {shlex.quote(con_name)} ethernet.cloned-mac-address {shlex.quote(mac)}", dry_run=dry_run)

        if dns_servers:
            dns_str = " ".join(dns_servers)
            print(f"Salvando DNS no perfil do NetworkManager: {dns_str}")
            run_command(f"nmcli con mod {shlex.quote(con_name)} ipv4.dns \"{dns_str}\" ipv4.ignore-auto-dns yes", dry_run=dry_run)

            if protect_dns:
                disable_nm_dns_overwrite(dry_run, dns_servers)

        print("Aplicando e subindo a conexão de rede...")
        run_command(f"nmcli con up {shlex.quote(con_name)}", dry_run=dry_run)

    else:
        # Fallback (Somente para distribuições antigas sem NM)
        print("NetworkManager não encontrado. Usando comandos temporários via iproute2...")
        run_command(f"ip addr flush dev {shlex.quote(wan_iface)}", dry_run=dry_run)
        run_command(f"ip addr add {shlex.quote(cidr)} dev {shlex.quote(wan_iface)}", dry_run=dry_run)
        run_command(f"ip route replace default via {shlex.quote(public_gw)} dev {shlex.quote(wan_iface)}", dry_run=dry_run)
        if dns_servers:
            with open("/etc/resolv.conf", "w") as f:
                for server in dns_servers:
                    f.write(f"nameserver {server}\n")

    print("Testando ping para o gateway e 8.8.8.8...")
    run_command(f"ping -c 3 {shlex.quote(public_gw)} || true", dry_run=dry_run, check=False)
    run_command("ping -c 3 8.8.8.8 || true", dry_run=dry_run, check=False)

def main_interactive():
    """Função principal para execução interativa."""
    parser = argparse.ArgumentParser(description="Configuração interativa de rede WAN e Firewall (Fedora/RHEL)")
    parser.add_argument("--dry-run", action="store_true", help="Mostra as ações sem aplicar")
    args = parser.parse_args()
    dry_run = args.dry_run

    require_root()
    print("➡️  Modo Fedora/RHEL detectado (Garantindo persistência com nmcli e iptables-save)")

    print("\n== Configurar WAN estática ==")
    wan_iface = ask_question("Qual é a interface WAN (ex: ens160)", default="ens3")
    public_ip = ask_question("Digite o IP público (ex: 203.0.113.10)")
    while not is_valid_ip(public_ip):
        print("IP inválido.")
        public_ip = ask_question("Digite o IP público")

    public_mask = ask_question("Máscara CIDR (ex: 24)", default="24")
    while not public_mask.isdigit() or not (0 <= int(public_mask) <= 32):
        print("Máscara inválida.")
        public_mask = ask_question("Máscara CIDR (ex: 24)", default="24")

    public_gw = ask_question("Gateway público (ex: 203.0.113.1)")
    while not is_valid_ip(public_gw):
        print("Gateway inválido.")
        public_gw = ask_question("Gateway público")

    dns_servers = []
    protect_dns = False

    if ask_yes_no("Deseja configurar servidores DNS?"):
        dns1 = ask_question("DNS primário (ex: 213.186.33.99)", default="8.8.8.8")
        while not is_valid_ip(dns1):
            print("IP de DNS inválido.")
            dns1 = ask_question("DNS primário")
        dns_servers.append(dns1)

        if ask_yes_no("Deseja adicionar um DNS secundário?"):
            dns2 = ask_question("DNS secundário (ex: 8.8.4.4)", default="8.8.4.4")
            while not is_valid_ip(dns2):
                print("IP de DNS inválido.")
                dns2 = ask_question("DNS secundário")
            dns_servers.append(dns2)

        protect_dns = ask_yes_no("Deseja proibir permanentemente o NetworkManager de modificar o /etc/resolv.conf?", default=True)

    mac_spoof = None
    if ask_yes_no("Precisa clonar o MAC na WAN?", default=False):
        mac_raw = ask_question("MAC (sem ':' ou com):")
        mac_norm = normalize_mac_address(mac_raw)
        while not mac_norm:
            print("MAC inválido.")
            mac_raw = ask_question("MAC (sem ':' ou com):")
            mac_norm = normalize_mac_address(mac_raw)
        mac_spoof = mac_raw

    configure_wan_static(dry_run, wan_iface, public_ip, public_mask, public_gw, mac_spoof, dns_servers, protect_dns)

    lan_ifaces = []
    if ask_yes_no("Deseja configurar interfaces de LAN/VLAN?"):
        ifaces_str = ask_question("Digite as interfaces de LAN/VLAN separadas por vírgula (ex: eth1,eth2.10)")
        lan_ifaces = [iface.strip() for iface in ifaces_str.split(",")]

    apply_firewall_rules(dry_run, wan_iface, lan_ifaces)

    print("\n✅ Configuração da WAN e do Firewall concluída!")

if __name__ == "__main__":
    try:
        main_interactive()
    except KeyboardInterrupt:
        print("\nCancelado pelo usuário.")
        sys.exit(1)
    except Exception as e:
        print(f"Ocorreu um erro: {e}", file=sys.stderr)
        sys.exit(2)
