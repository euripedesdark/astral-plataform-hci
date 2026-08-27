#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
config-wan.py

Script Universal de configuração de rede (WAN/LAN/VLAN), DHCP (Kea) e Firewall.
Suporta: RHEL, Fedora, Nobara, Arch, EndeavourOS, Debian, Ubuntu, Pop!_OS e SUSE.
Força o uso exclusivo do NetworkManager, exterminando systemd-resolved e netplan.

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
import ipaddress
import json
from typing import List, Optional, Tuple

# ==========================================
# DETECÇÃO DE SISTEMA E GERENCIADOR DE PACOTES
# ==========================================
def detect_os_family() -> str:
    os_id = ""
    os_like = ""
    try:
        with open("/etc/os-release") as f:
            for line in f:
                if line.startswith("ID="):
                    os_id = line.strip().split("=")[1].strip('"').lower()
                elif line.startswith("ID_LIKE="):
                    os_like = line.strip().split("=")[1].strip('"').lower()
    except FileNotFoundError:
        pass

    combo = f"{os_id} {os_like}"

    if any(x in combo for x in ["fedora", "rhel", "centos", "nobara", "rocky", "almalinux"]):
        return "redhat"
    elif any(x in combo for x in ["debian", "ubuntu", "pop"]):
        return "debian"
    elif any(x in combo for x in ["arch", "endeavouros"]):
        return "arch"
    elif any(x in combo for x in ["suse", "opensuse"]):
        return "suse"
    else:
        return "unknown"

def install_packages(packages: List[str], os_family: str, dry_run: bool):
    if not packages:
        return
    pkgs_str = " ".join(packages)
    print(f"📦 Instalando pacotes nativos ({pkgs_str})...")

    if os_family == "redhat":
        run_command(f"yum install -y {pkgs_str}", dry_run=dry_run)
    elif os_family == "debian":
        run_command("apt-get update", dry_run=dry_run, check=False)
        run_command(f"DEBIAN_FRONTEND=noninteractive apt-get install -y {pkgs_str}", dry_run=dry_run)
    elif os_family == "arch":
        run_command(f"pacman -Sy --noconfirm --needed {pkgs_str}", dry_run=dry_run)
    elif os_family == "suse":
        run_command(f"zypper install -y {pkgs_str}", dry_run=dry_run)
    else:
        print(f"Aviso: Família de SO '{os_family}' não suportada para instalação automática de '{pkgs_str}'.")

# ==========================================
# EXTERMÍNIO DE SERVIÇOS CONFLITANTES
# ==========================================
def nuke_resolved_and_netplan(dry_run: bool):
    print("\n== Exterminando systemd-resolved, Netplan e systemd-networkd ==")

    run_command("systemctl stop systemd-resolved || true", dry_run=dry_run, check=False)
    run_command("systemctl disable systemd-resolved || true", dry_run=dry_run, check=False)

    if os.path.islink("/etc/resolv.conf"):
        if not dry_run:
            os.unlink("/etc/resolv.conf")
        print("🔗 Symlink do /etc/resolv.conf removido para evitar sequestro de DNS.")

    run_command("systemctl stop systemd-networkd || true", dry_run=dry_run, check=False)
    run_command("systemctl disable systemd-networkd || true", dry_run=dry_run, check=False)

    if os.path.exists("/etc/netplan"):
        print("🗑️  Desativando configurações do Netplan...")
        if not dry_run:
            os.makedirs("/etc/netplan/backup_disabled", exist_ok=True)
        run_command("mv /etc/netplan/*.yaml /etc/netplan/backup_disabled/ 2>/dev/null || true", dry_run=dry_run, check=False)

    run_command("systemctl enable NetworkManager", dry_run=dry_run, check=False)
    run_command("systemctl start NetworkManager", dry_run=dry_run, check=False)
    print("✅ NetworkManager definido como o único gerenciador de rede.")

# ==========================================
# FUNÇÕES AUXILIARES COMUNS
# ==========================================
def run_command(cmd: str, dry_run: bool = False, check: bool = True) -> subprocess.CompletedProcess:
    print(f"{'[dry-run]' if dry_run else '+'} {cmd}")
    if dry_run:
        return subprocess.CompletedProcess(args=cmd, returncode=0, stdout=b"", stderr=b"")
    return subprocess.run(cmd, shell=True, check=check, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

def get_command_output(cmd: str, dry_run: bool = False) -> str:
    p = run_command(cmd, dry_run=dry_run, check=False)
    return p.stdout.decode().strip() if p.stdout else ""

def require_root():
    if os.geteuid() != 0:
        print("Este script precisa ser executado como root. Use sudo.", file=sys.stderr)
        sys.exit(1)

def ask_question(prompt: str, default: Optional[str] = None) -> str:
    prompt = f"{prompt} [{default}]: " if default is not None else f"{prompt}: "
    val = input(prompt).strip()
    return val if val else (default if default is not None else "")

def ask_yes_no(prompt: str, default: bool = True) -> bool:
    d = "Y/n" if default else "y/N"
    r = input(f"{prompt} ({d}): ").strip().lower()
    if r == "": return default
    return r.startswith("y")

def is_valid_ip(ip: str) -> bool:
    return bool(re.match(r"^\d{1,3}(\.\d{1,3}){3}$", ip))

def normalize_mac_address(mac: str) -> Optional[str]:
    m = mac.strip().lower().replace(":", "")
    if re.fullmatch(r"[0-9a-f]{12}", m):
        return ":".join(m[i:i+2] for i in range(0, 12, 2))
    return None

def get_network_details(cidr_ip: str) -> Tuple[str, str, str, str]:
    iface_obj = ipaddress.IPv4Interface(cidr_ip)
    network_cidr = str(iface_obj.network)
    router_ip = str(iface_obj.ip)
    base_ip = ".".join(str(iface_obj.network.network_address).split('.')[:3])
    return network_cidr, router_ip, f"{base_ip}.100", f"{base_ip}.199"

def check_open_ports() -> bool:
    print("\n== Verificando portas abertas (Serviços em escuta) ==")
    out = get_command_output("ss -tulpn | grep LISTEN")
    if out:
        print(out)
        return ask_yes_no("\n⚠️  Foram encontrados serviços em escuta. Deseja continuar com as alterações de rede e firewall?", default=False)
    return True

# ==========================================
# CONFIGURAÇÃO CORE (REDE E SERVIÇOS)
# ==========================================
def disable_nm_dns_overwrite(dry_run: bool, dns_servers: List[str]):
    print("\n== Protegendo /etc/resolv.conf contra alterações do NM ==")
    nm_conf_dir = "/etc/NetworkManager/conf.d"
    dns_conf_file = os.path.join(nm_conf_dir, "90-dns-none.conf")

    if not dry_run:
        os.makedirs(nm_conf_dir, exist_ok=True)
        with open(dns_conf_file, "w") as f:
            f.write("[main]\ndns=none\n")

        with open("/etc/resolv.conf", "w") as f:
            for server in dns_servers:
                f.write(f"nameserver {server}\n")

        run_command("systemctl reload NetworkManager", dry_run=dry_run, check=False)
    else:
        print(f"[dry-run] Criaria o arquivo {dns_conf_file} com a instrução '[main]\\ndns=none'")

def configure_wan_static(dry_run: bool, wan_iface: str, public_ip: str, public_mask: str, public_gw: str, mac_spoof: Optional[str], dns_servers: List[str], protect_dns: bool):
    print(f"\n== Configurando WAN estática em {wan_iface} via NetworkManager ==")
    cidr = f"{public_ip}/{public_mask}"

    get_con_cmd = f"nmcli -t -f NAME,DEVICE con show | awk -F: '$2==\"{wan_iface}\" {{print $1}}'"
    con_name_out = get_command_output(get_con_cmd, dry_run=dry_run)
    con_name = con_name_out.split('\n')[0].strip() if con_name_out else ""

    if not con_name:
        con_name = f"System_{wan_iface}"
        run_command(f"nmcli con add type ethernet ifname {shlex.quote(wan_iface)} con-name {shlex.quote(con_name)}", dry_run=dry_run)

    run_command(f"nmcli con mod {shlex.quote(con_name)} ipv4.method manual ipv4.addresses {shlex.quote(cidr)} ipv4.gateway {shlex.quote(public_gw)}", dry_run=dry_run)

    if mac_spoof:
        mac = normalize_mac_address(mac_spoof)
        if mac: run_command(f"nmcli con mod {shlex.quote(con_name)} ethernet.cloned-mac-address {shlex.quote(mac)}", dry_run=dry_run)

    if dns_servers:
        dns_str = " ".join(dns_servers)
        run_command(f"nmcli con mod {shlex.quote(con_name)} ipv4.dns \"{dns_str}\" ipv4.ignore-auto-dns yes", dry_run=dry_run)
        if protect_dns:
            disable_nm_dns_overwrite(dry_run, dns_servers)

    run_command(f"nmcli con up {shlex.quote(con_name)}", dry_run=dry_run)

def process_lan_vlan(dry_run: bool, iface: str) -> str:
    print(f"\n== Analisando a interface interna: {iface} ==")

    ip_out = get_command_output(f"ip -4 addr show dev {shlex.quote(iface)} | grep -oP '(?<=inet\\s)\\d+(\\.\\d+){{3}}/\\d+'", dry_run=False)
    current_ip = ip_out.split('\n')[0].strip() if ip_out else None

    if not current_ip:
        nm_ip_out = get_command_output(f"nmcli -g ipv4.addresses device show {shlex.quote(iface)}", dry_run=False)
        current_ip = nm_ip_out.split('\n')[0].strip() if nm_ip_out else None

    final_ip = None
    if current_ip:
        if ask_yes_no(f"A interface {iface} já possui o IP {current_ip}. Deseja mudar?", default=False):
            final_ip = ask_question(f"Digite o novo IP com CIDR para {iface} (ex: 192.168.10.1/24)")
        else:
            final_ip = current_ip
            print(f"👉 Mantendo o IP {current_ip}. Iniciando limpeza de rotas...")
    else:
        final_ip = ask_question(f"Nenhum IP detectado. Digite o IP com CIDR (ex: 192.168.10.1/24)")

    while not final_ip or "/" not in final_ip:
        print("IP inválido! O IP deve conter a máscara (ex: /24).")
        final_ip = ask_question(f"Digite o IP com CIDR para {iface} (ex: 192.168.10.1/24)")

    con_name_out = get_command_output(f"nmcli -t -f NAME,DEVICE con show | awk -F: '$2==\"{iface}\" {{print $1}}'")
    con_name = con_name_out.split('\n')[0].strip() if con_name_out else ""

    if not con_name:
        print(f"🛠️  Criando novo perfil no NM para {iface}...")
        if "." in iface:
            parent = iface.split(".")[0]
            vlan_id = iface.split(".")[1]
            run_command(f"nmcli con add type vlan ifname {shlex.quote(iface)} dev {shlex.quote(parent)} id {vlan_id} con-name {shlex.quote(iface)}", dry_run=dry_run)
        else:
            run_command(f"nmcli con add type ethernet ifname {shlex.quote(iface)} con-name {shlex.quote(iface)}", dry_run=dry_run)
        con_name = iface

    print(f"🧹 Forçando remoção de gateways extras no perfil '{con_name}'...")
    run_command(f"nmcli con mod {shlex.quote(con_name)} ipv4.addresses {shlex.quote(final_ip)} ipv4.method manual ipv4.gateway \"\" ipv4.routes \"\"", dry_run=dry_run)
    run_command(f"nmcli con up {shlex.quote(con_name)}", dry_run=dry_run)

    print(f"🧹 Limpando resíduos de rotas default no kernel para {iface}...")
    run_command(f"ip route del default dev {shlex.quote(iface)} 2>/dev/null || true", dry_run=dry_run, check=False)

    return final_ip

def setup_kea_dhcp_server(dry_run: bool, dhcp_configs: List[Tuple[str, str, str, str, str]], dns_servers: List[str], domain_name: str, os_family: str):
    print("\n== Configurando Servidor DHCP (Kea) ==")

    kea_pkgs = "kea-dhcp4-server" if os_family == "debian" else "kea kea-dhcp4"
    kea_svc = "kea-dhcp4-server" if os_family == "debian" else "kea-dhcp4"

    # Checagem blindada no Systemd em vez de olhar arquivo solto
    svc_check = run_command(f"systemctl list-unit-files {kea_svc}.service", check=False, dry_run=dry_run)
    if svc_check.returncode != 0:
        install_packages(kea_pkgs.split(), os_family, dry_run)

    kea_conf_dir = "/etc/kea"
    kea_conf_file = os.path.join(kea_conf_dir, "kea-dhcp4.conf")

    kea_config = {
        "Dhcp4": {
            "interfaces-config": {"interfaces": [cfg[0] for cfg in dhcp_configs]},
            "lease-database": {
                "type": "memfile", "persist": True, "name": "/var/lib/kea/kea-leases4.csv", "lfc-interval": 3600
            },
            "valid-lifetime": 43200,
            "option-data": [{"name": "domain-name-servers", "data": ", ".join(dns_servers) if dns_servers else "8.8.8.8, 8.8.4.4"}],
            "subnet4": []
        }
    }

    if domain_name:
        kea_config["Dhcp4"]["option-data"].append({"name": "domain-name", "data": domain_name})

    for iface, network_cidr, router_ip, start_ip, end_ip in dhcp_configs:
        kea_config["Dhcp4"]["subnet4"].append({
            "subnet": network_cidr,
            "pools": [{"pool": f"{start_ip} - {end_ip}"}],
            "option-data": [{"name": "routers", "data": router_ip}]
        })

    json_output = json.dumps(kea_config, indent=4)

    if not dry_run:
        os.makedirs(kea_conf_dir, exist_ok=True)
        os.makedirs("/var/lib/kea", exist_ok=True)

        # Garante que o arquivo de leases não tome erro de permissão
        run_command("chmod 777 /var/lib/kea", dry_run=dry_run, check=False)

        with open(kea_conf_file, "w") as f:
            f.write(json_output)

        # Configurado para NÃO crashear o script se o Kea falhar
        run_command(f"systemctl enable {kea_svc}", dry_run=dry_run, check=False)
        restart_check = run_command(f"systemctl restart {kea_svc}", dry_run=dry_run, check=False)

        if restart_check.returncode == 0:
            print(f"✅ Kea DHCP configurado e rodando ({kea_svc})!")
        else:
            print(f"❌ Aviso: Kea DHCP falhou ao iniciar. Verifique com: journalctl -xeu {kea_svc}")
    else:
        print(f"[dry-run] Criaria o arquivo {kea_conf_file} com configuração JSON.")

def apply_firewall_rules(dry_run: bool, wan_iface: str, lan_ifaces: List[str], setup_dhcp: bool, os_family: str):
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

    if setup_dhcp:
        for lan in lan_ifaces:
            run_command(f"iptables -A INPUT -i {shlex.quote(lan)} -p udp -m multiport --dports 67,68 -j ACCEPT", dry_run=dry_run)
            run_command(f"iptables -A INPUT -i {shlex.quote(lan)} -p udp --dport 53 -j ACCEPT", dry_run=dry_run)

    for lan in lan_ifaces:
        run_command(f"iptables -A FORWARD -i {shlex.quote(lan)} -o {shlex.quote(wan_iface)} -j ACCEPT", dry_run=dry_run)
        run_command(f"iptables -A FORWARD -i {shlex.quote(wan_iface)} -o {shlex.quote(lan)} -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT", dry_run=dry_run)

    run_command(f"iptables -t nat -A POSTROUTING -o {shlex.quote(wan_iface)} -j MASQUERADE", dry_run=dry_run)

    print("\n== Salvando regras de firewall para persistência ==")
    if os_family == "debian":
        install_packages(["iptables-persistent"], os_family, dry_run)
        run_command("mkdir -p /etc/iptables && iptables-save > /etc/iptables/rules.v4", dry_run=dry_run)
        run_command("systemctl enable netfilter-persistent", dry_run=dry_run, check=False)
    elif os_family == "arch":
        install_packages(["iptables-nft"], os_family, dry_run)
        run_command("mkdir -p /etc/iptables && iptables-save > /etc/iptables/iptables.rules", dry_run=dry_run)
        run_command("systemctl enable iptables", dry_run=dry_run, check=False)
    else:
        run_command("mkdir -p /etc/sysconfig && iptables-save > /etc/sysconfig/iptables", dry_run=dry_run)
        run_command("systemctl enable iptables || true", dry_run=dry_run, check=False)

    print("✅ Regras de firewall salvas com sucesso.")

def main_interactive():
    parser = argparse.ArgumentParser(description="Configuração inteligente WAN/LAN/DHCP(Kea)/Firewall Multidistro")
    parser.add_argument("--dry-run", action="store_true", help="Mostra as ações sem aplicar")
    args = parser.parse_args()
    dry_run = args.dry_run

    require_root()

    os_family = detect_os_family()
    print(f"➡️  Família de SO detectada: {os_family.upper()}")

    if os_family == "unknown":
        if not ask_yes_no("Não foi possível determinar a distribuição. O comportamento de persistência do firewall e instalação de pacotes pode falhar. Deseja continuar?", default=False):
            sys.exit(1)

    nuke_resolved_and_netplan(dry_run)

    if not check_open_ports():
        print("\nConfiguração cancelada pelo usuário.")
        sys.exit(0)

    print("\n== Configurar WAN estática ==")
    wan_iface = ask_question("Qual é a interface WAN (ex: ens160)", default="ens3")
    public_ip = ask_question("Digite o IP público (ex: 203.0.113.10)")
    while not is_valid_ip(public_ip):
        public_ip = ask_question("IP inválido. Digite o IP público")

    public_mask = ask_question("Máscara CIDR (ex: 24)", default="24")
    public_gw = ask_question("Gateway público (ex: 203.0.113.1)")

    dns_servers = []
    protect_dns = False
    if ask_yes_no("Deseja configurar servidores DNS?"):
        dns_servers.append(ask_question("DNS primário", default="8.8.8.8"))
        if ask_yes_no("Deseja adicionar um DNS secundário?"):
            dns_servers.append(ask_question("DNS secundário", default="8.8.4.4"))
        protect_dns = ask_yes_no("Deseja proibir permanentemente o NM de modificar o /etc/resolv.conf?", default=True)

    mac_spoof = ask_question("MAC (sem ':' ou com):") if ask_yes_no("Precisa clonar o MAC na WAN?", default=False) else None

    configure_wan_static(dry_run, wan_iface, public_ip, public_mask, public_gw, mac_spoof, dns_servers, protect_dns)

    lan_ifaces = []
    dhcp_configs = []
    setup_dhcp_flag = False
    domain_name = ""

    if ask_yes_no("\nDeseja configurar ou AUDITAR as interfaces de LAN/VLAN? (Responda Y para instalar o Kea)"):
        ifaces_str = ask_question("Digite as interfaces separadas por vírgula (ex: ens224,ens256.10)")
        lan_ifaces = [iface.strip() for iface in ifaces_str.split(",")]

        setup_dhcp_flag = ask_yes_no("Deseja habilitar servidor DHCP Kea (.100 a .199) para essas interfaces?", default=True)
        if setup_dhcp_flag:
            domain_name = ask_question("Digite o nome de domínio DHCP (ex: srvcloud.cloud) [deixe em branco para pular]", default="")

        for iface in lan_ifaces:
            ip_cidr = process_lan_vlan(dry_run, iface)
            if setup_dhcp_flag:
                network_cidr, router_ip, start_ip, end_ip = get_network_details(ip_cidr)
                dhcp_configs.append((iface, network_cidr, router_ip, start_ip, end_ip))

    if setup_dhcp_flag and dhcp_configs:
        setup_kea_dhcp_server(dry_run, dhcp_configs, dns_servers, domain_name, os_family)

    apply_firewall_rules(dry_run, wan_iface, lan_ifaces, setup_dhcp_flag, os_family)

    print("\n✅ Configuração Multidistro de Rede e Firewall concluída com sucesso!")

if __name__ == "__main__":
    try:
        main_interactive()
    except KeyboardInterrupt:
        print("\nCancelado pelo usuário.")
        sys.exit(1)
    except Exception as e:
        print(f"Ocorreu um erro: {e}", file=sys.stderr)
        sys.exit(2)
