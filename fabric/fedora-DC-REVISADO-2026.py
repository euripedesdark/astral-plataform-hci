#!/usr/bin/python3
# -*- coding: utf-8 -*-
"""
Script final e revisado para automatizar a instalação e configuração de um
Samba Active Directory Domain Controller no Fedora, com todas as etapas de
atualização e correção.

Autor: Eurípedes Batista
LinkedIn: https://www.linkedin.com/in/euripedes-batista-14235229/
"""
import os
import subprocess
import sys
import time
import textwrap

# --- VARIÁVEIS DE CONFIGURAÇÃO ---
HOSTNAME_COMPLETO = "dc1.astral.celeste"
NOME_NETBIOS = "ASTRAL"
REALM = "ASTRAL.CELESTE"
SENHA_ADMIN = "7xt0KtapoR1$"

# --- Funções Auxiliares ---

def run_command(command, shell=False, check=True):
    """Executa um comando no sistema."""
    try:
        cmd_str = command if isinstance(command, str) else ' '.join(command)
        print(f"🚀 Executando: {cmd_str}")
        subprocess.run(command, shell=shell, check=check, text=True, stdout=sys.stdout, stderr=sys.stderr)
        print("-" * 40)
        time.sleep(1)
    except subprocess.CalledProcessError as e:
        print(f"❌ ERRO ao executar comando: {e}")
        print("A operação foi interrompida.")
        raise e

def wait_for_enter(prompt="Pressione Enter para continuar..."):
    """Pausa a execução."""
    input(f"\n👉 {prompt}")

def create_file_with_content(filepath, content):
    """Cria um arquivo com o conteúdo especificado."""
    print(f"📝 Criando/Sobrescrevendo o arquivo: {filepath}")
    try:
        temp_filepath = f"/tmp/{os.path.basename(filepath)}.tmp"
        with open(temp_filepath, "w") as f:
            f.write(content)
        run_command(["sudo", "mv", temp_filepath, filepath])
        run_command(["sudo", "restorecon", filepath], check=False)
        print(f"✅ Arquivo {filepath} criado com sucesso.")
    except IOError as e:
        print(f"❌ ERRO ao escrever no arquivo {filepath}: {e}")
        sys.exit(1)

def update_smb_conf(params_to_add):
    """Atualiza a seção [global] do smb.conf de forma segura."""
    print("🔧 Atualizando /etc/samba/smb.conf...")
    smb_conf_path = "/etc/samba/smb.conf"
    with open(smb_conf_path, 'r') as f:
        lines = f.readlines()

    param_keys = [p.split('=')[0].strip() for p in params_to_add]
    new_lines = []
    in_global_section = False
    global_section_ended = False
    params_inserted = False

    for line in lines:
        stripped_line = line.strip()
        if stripped_line.lower() == '[global]':
            in_global_section = True
            new_lines.append(line)
            continue
        elif in_global_section and stripped_line.startswith('['):
            in_global_section = False
            global_section_ended = True

        if in_global_section:
            current_key = stripped_line.split('=')[0].strip()
            if any(key_to_check == current_key for key_to_check in param_keys):
                print(f"   - Removendo linha antiga/duplicada: {stripped_line}")
                continue

        if global_section_ended and not params_inserted:
            print("   + Adicionando novas configurações na seção [global]")
            for param in params_to_add:
                new_lines.append(f"\t{param}\n")
            params_inserted = True

        new_lines.append(line)

    if in_global_section and not params_inserted:
        print("   + Adicionando novas configurações no final da seção [global]")
        for param in params_to_add:
            new_lines.append(f"\t{param}\n")

    create_file_with_content(smb_conf_path, "".join(new_lines))

def obter_interfaces_disponiveis():
    """Retorna lista de interfaces de rede não-loopback."""
    interfaces = []
    for iface in os.listdir('/sys/class/net/'):
        if iface != 'lo':
            interfaces.append(iface)
    return sorted(interfaces)

def configurar_rede_e_iptables():
    """Configura rede com controle total: WAN, LANs, VLANs (existentes ou novas via trunk)."""
    print("\n--- FASE 2: CONFIGURAÇÃO AVANÇADA DE REDE ---")

    interfaces_fisicas = obter_interfaces_disponiveis()
    if not interfaces_fisicas:
        print("❌ Nenhuma interface de rede encontrada.")
        sys.exit(1)

    print("🔧 Interfaces físicas disponíveis:")
    for i, iface in enumerate(interfaces_fisicas, 1):
        print(f"  {i}) {iface}")

    # --- Escolher WAN ---
    while True:
        try:
            idx = int(input("\n👉 Escolha o número da interface WAN (saída para internet): ")) - 1
            if 0 <= idx < len(interfaces_fisicas):
                wan = interfaces_fisicas[idx]
                break
            else:
                print("⚠️  Número inválido.")
        except ValueError:
            print("⚠️  Digite um número.")

    # --- Escolher LANs (interfaces sem tag) ---
    lans = []
    print("\n🔧 Escolha as interfaces LAN (rede local sem VLAN).")
    print("Digite os números separados por vírgula (ex: 1,3) ou Enter para pular.")
    while True:
        entrada = input("👉 LANs: ").strip()
        if not entrada:
            break
        try:
            indices = [int(x.strip()) - 1 for x in entrada.split(',')]
            if all(0 <= i < len(interfaces_fisicas) for i in indices):
                lans = [interfaces_fisicas[i] for i in indices]
                break
            else:
                print("⚠️  Um ou mais números inválidos.")
        except ValueError:
            print("⚠️  Use apenas números separados por vírgula.")

    # --- VLANs ou TRUNK? ---
    vlan_interfaces = []  # Lista de nomes das interfaces VLAN criadas

    print("\n❓ Como deseja configurar as VLANs?")
    print("  1) Usar interfaces VLAN já existentes (ex: ens36.10)")
    print("  2) Criar VLANs novas a partir de uma TRUNK")
    print("  3) Não usar VLANs")

    while True:
        modo = input("👉 Escolha (1/2/3): ").strip()
        if modo == '1':
            print("\nDigite as interfaces VLAN como: nome (ex: ens36.10)")
            print("Uma por linha. Digite 'fim' quando terminar.")
            while True:
                nome = input("VLAN> ").strip()
                if nome.lower() == 'fim':
                    break
                # Não cria, só registra
                ip_cidr = input(f"  IP/máscara para {nome} (ex: 192.168.10.1/24): ").strip()
                if ip_cidr:
                    vlan_interfaces.append(nome)
                    run_command(["sudo", "ip", "addr", "add", ip_cidr, "dev", nome], check=False)
                    run_command(["sudo", "ip", "link", "set", nome, "up"], check=False)
            break

        elif modo == '2':
            while True:
                try:
                    idx = int(input("\n👉 Escolha a interface TRUNK (física): ")) - 1
                    if 0 <= idx < len(interfaces_fisicas):
                        trunk = interfaces_fisicas[idx]
                        break
                    else:
                        print("⚠️  Número inválido.")
                except ValueError:
                    print("⚠️  Digite um número.")

            print(f"\nCriando VLANs na trunk: {trunk}")
            print("Digite os IDs das VLANs (ex: 10,20,30).")
            while True:
                vlan_input = input("IDs> ").strip()
                try:
                    vids = [int(x.strip()) for x in vlan_input.split(',') if x.strip()]
                    if vids:
                        for vid in vids:
                            nome = f"{trunk}.{vid}"
                            ip_cidr = input(f"  IP/máscara para VLAN {vid} ({nome}): ").strip()
                            if ip_cidr:
                                run_command(["sudo", "ip", "link", "add", "link", trunk, "name", nome, "type", "vlan", "id", str(vid)], check=False)
                                run_command(["sudo", "ip", "addr", "add", ip_cidr, "dev", nome], check=False)
                                run_command(["sudo", "ip", "link", "set", nome, "up"], check=False)
                                vlan_interfaces.append(nome)
                        break
                except ValueError:
                    print("⚠️  Use apenas números separados por vírgula.")
            break

        elif modo == '3':
            break
        else:
            print("⚠️  Escolha 1, 2 ou 3.")

    # --- Atribuir IPs às LANs ---
    for lan in lans:
        ip_cidr = input(f"\n👉 IP/máscara para LAN {lan} (ex: 192.168.0.1/24): ").strip()
        if ip_cidr:
            run_command(["sudo", "ip", "addr", "add", ip_cidr, "dev", lan], check=False)
            run_command(["sudo", "ip", "link", "set", lan, "up"], check=False)

    # --- Pré-visualização ---
    todas_interfaces = lans + vlan_interfaces
    print("\n" + "="*60)
    print("🎯 CONFIGURAÇÃO FINAL:")
    print(f"   WAN: {wan}")
    for lan in lans:
        print(f"   LAN: {lan}")
    for vlan in vlan_interfaces:
        print(f"   VLAN: {vlan}")
    print("="*60)

    if input("\n👉 CONFIRMAR? (s/N): ").strip().lower() != 's':
        print("❌ Cancelado.")
        sys.exit(1)

    # --- Configurar iptables ---
    print("\n🔧 Configurando firewall para o Samba DC...")
    
    # Adiciona uma chain para as regras do Samba para melhor organização
    run_command(["sudo", "iptables", "-N", "SAMBA_DC_RULES"], check=False)
    run_command(["sudo", "iptables", "-F", "SAMBA_DC_RULES"], check=False) # Limpa apenas as regras do Samba
    run_command(["sudo", "iptables", "-A", "INPUT", "-j", "SAMBA_DC_RULES"], check=False)


    # Portas: inclui SSH (22) + todas do AD
    ports = {
        'tcp': [22, 53, 88, 135, 139, 389, 445, 464, 636, 3268, 3269, 5353, '49152:65535'],
        'udp': [53, 88, 123, 137, 138, 389, 464, '49152:65535']
    }
    for proto, port_list in ports.items():
        for port in port_list:
            run_command(["sudo", "iptables", "-A", "SAMBA_DC_RULES", "-p", proto, "--dport", str(port), "-j", "ACCEPT"])

    # Salvar
    run_command("sudo sh -c 'iptables-save > /etc/sysconfig/iptables'", shell=True)
    run_command(["sudo", "systemctl", "enable", "iptables"], check=False)
    run_command(["sudo", "systemctl", "restart", "iptables"], check=False)

    print("\n✅ Rede configurada com sucesso.")
    
    # Coletar redes no formato 192.168.x.0/24
    redes_cidr = []
    for lan in lans:
        # Obter IP da interface
        result = subprocess.run(["ip", "addr", "show", lan], capture_output=True, text=True)
        for line in result.stdout.splitlines():
            if "inet " in line:
                ip_part = line.strip().split()[1]  # ex: 192.168.0.1/24
                if "/" in ip_part:
                    ip, mask = ip_part.split("/")
                    if mask == "24":
                        net = ".".join(ip.split(".")[:3]) + ".0/24"
                        redes_cidr.append(net)
                        break

    for vlan in vlan_interfaces:
        result = subprocess.run(["ip", "addr", "show", vlan], capture_output=True, text=True)
        for line in result.stdout.splitlines():
            if "inet " in line:
                ip_part = line.strip().split()[1]
                if "/" in ip_part:
                    ip, mask = ip_part.split("/")
                    if mask == "24":
                        net = ".".join(ip.split(".")[:3]) + ".0/24"
                        redes_cidr.append(net)
                        break

    return {
        'wan': wan,
        'lans': lans,
        'vlan_interfaces': vlan_interfaces,
        'redes_cidr': redes_cidr
    }

# --- Funções do Script ---

def limpeza_previa():
    """Para e remove configurações e dados de instalações anteriores."""
    print("\n--- FASE 0: LIMPANDO INSTALAÇÃO ANTERIOR ---")
    wait_for_enter("AVISO: A próxima etapa removerá dados e configurações existentes.")
    servicos = ["samba", "named", "chronyd"]
    for servico in servicos:
        run_command(["sudo", "systemctl", "stop", servico], check=False)

    locais_para_limpar = [
        "/etc/samba/smb.conf", "/etc/krb5.conf", "/etc/named.conf", "/etc/chrony.conf",
        "/var/lib/samba/*", "/var/cache/samba/*", "/var/log/samba/*", "/var/named/*"
    ]
    for local in locais_para_limpar:
        print(f"Removendo {local}...")
        run_command(f"sudo rm -rf {local}", shell=True, check=False)
    print("✅ Limpeza concluída.")

def preparacao_sistema_fedora():
    """Configura o sistema base e instala os pacotes."""
    print("\n--- FASE 1: PREPARAÇÃO E INSTALAÇÃO ---")
    run_command(["sudo", "hostnamectl", "set-hostname", HOSTNAME_COMPLETO])

    # /etc/hosts será atualizado depois com IPs reais, então pula por enquanto
    hosts_content = "127.0.0.1   localhost\n"
    create_file_with_content("/etc/hosts", hosts_content)

    run_command(["sudo", "systemctl", "stop", "firewalld"], check=False)
    run_command(["sudo", "systemctl", "disable", "firewalld"], check=False)

    packages = ["samba-dc", "samba-client", "bind", "chrony", "iptables-services", "patch", "python3-markdown"]
    run_command(["sudo", "dnf", "install", "-y"] + packages)
    print("✅ Preparação do sistema concluída.")

def configurar_ntp_fedora():
    """Configura o NTP (Chrony)."""
    print("\n--- FASE 3: CONFIGURANDO NTP (CHRONY) ---")
    chrony_conf_content = textwrap.dedent("""
        pool 2.br.pool.ntp.org iburst
        driftfile /var/lib/chrony/drift
        makestep 1.0 3
        rtcsync
        local stratum 10
        ntpsigndsocket /var/lib/samba/ntp_signd
    """)
    create_file_with_content("/etc/chrony.conf", chrony_conf_content)
    run_command(["sudo", "install", "-d", "-o", "root", "-g", "chrony", "-m", "750", "/var/lib/samba/ntp_signd"])
    print("✅ Configuração NTP concluída.")

def provisionar_dominio():
    """Executa o provisionamento do domínio."""
    print("\n--- FASE 4: PROVISIONANDO O DOMÍNIO ---")
    provision_command = [
        "sudo", "samba-tool", "domain", "provision", "--use-rfc2307",
        "--realm", REALM, "--domain", NOME_NETBIOS, "--server-role=dc",
        "--dns-backend=SAMBA_INTERNAL", "--adminpass", SENHA_ADMIN
    ]
    run_command(provision_command)
    print("✅ Provisionamento do domínio concluído.")

def configurar_samba_e_bind(interfaces_samba_str):
    """Configura o smb.conf e o BIND com interfaces fornecidas."""
    print("\n--- FASE 5: CONFIGURANDO SAMBA E BIND ---")

    smb_params = [
        f"dns forwarder = 127.0.0.1:5353",
        f"interfaces = {interfaces_samba_str}",
        f"bind interfaces only = yes"
    ]
    update_smb_conf(smb_params)

    run_command(["sudo", "mkdir", "-p", "/var/named/data"])
    run_command(["sudo", "chown", "-R", "named:named", "/var/named"])

    named_conf_content = textwrap.dedent("""
        options {
            directory "/var/named";
            listen-on port 5353 { 127.0.0.1; };
            listen-on-v6 { none; };
            allow-query { localhost; };
            recursion yes;
            forwarders { 8.8.8.8; 1.1.1.1; };
            dnssec-validation no;
        };
        logging {
            channel default_debug { file "data/named.run"; severity dynamic; };
        };
    """)
    create_file_with_content("/etc/named.conf", named_conf_content)
    create_file_with_content("/etc/sysconfig/named", 'OPTIONS="-4"')
    print("✅ Configuração do Samba e BIND concluída.")

def integracao_final():
    """Realiza a integração final, gerando um krb5.conf robusto e inicializando os serviços."""
    print("\n--- FASE 6: INTEGRAÇÃO FINAL E INICIALIZAÇÃO ---")

    print("Gerando arquivo de configuração Kerberos (/etc/krb5.conf) robusto...")
    krb5_conf_content = textwrap.dedent(f"""
[libdefaults]
    default_realm = {REALM.upper()}
    dns_lookup_realm = false
    dns_lookup_kdc = true

[realms]
    {REALM.upper()} = {{
        kdc = {HOSTNAME_COMPLETO.lower()}
        admin_server = {HOSTNAME_COMPLETO.lower()}
        default_domain = {REALM.lower()}
    }}

[domain_realm]
    .{REALM.lower()} = {REALM.upper()}
    {REALM.lower()} = {REALM.upper()}
""")
    create_file_with_content("/etc/krb5.conf", krb5_conf_content)

    resolv_content = f"search {REALM.lower()}\nnameserver 127.0.0.1\n#nameserver 1.1.1.1\n"
    run_command(["sudo", "chattr", "-i", "/etc/resolv.conf"], check=False)
    create_file_with_content("/etc/resolv.conf", resolv_content)
    run_command(["sudo", "chattr", "+i", "/etc/resolv.conf"], check=False)

    services = ["chronyd", "named", "samba"]
    for service in services:
        print(f"Habilitando e reiniciando o serviço: {service}")
        run_command(["sudo", "systemctl", "enable", service])
        run_command(["sudo", "systemctl", "restart", service])
    print("✅ Servidor pronto e serviços iniciados.")

def atualizar_e_elevar_niveis():
    """Atualiza schema e eleva níveis funcionais seguindo o fluxo especificado pelo usuário."""
    print("\n--- FASE 7: ATUALIZANDO SCHEMA E NÍVEIS FUNCIONAIS ---")

    warning = textwrap.dedent("""
        AVISO: A próxima etapa realizará modificações IRREVERSÍVEIS no seu
        Active Directory (atualização de schema e elevação de níveis funcionais).
    """)
    print(warning)
    wait_for_enter("Pressione Enter para confirmar e continuar, ou Ctrl+C para cancelar.")

    print("Adicionando 'ad dc functional level' ao smb.conf (passo do tutorial)...")
    update_smb_conf(["ad dc functional level = 2016"])

    print("Reiniciando o serviço Samba...")
    run_command(["sudo", "systemctl", "restart", "samba"])

    print("Parando o serviço Samba para realizar as atualizações...")
    run_command(["sudo", "systemctl", "stop", "samba"])

    print("Atualizando o schema do domínio para 2019...")
    run_command(["sudo", "samba-tool", "domain", "schemaupgrade", "--schema=2019"])

    print("Preparando o domínio para o nível funcional 2016...")
    run_command(["sudo", "samba-tool", "domain", "functionalprep", "--function-level=2016"])

    print("Elevando os níveis de DOMÍNIO e FLORESTA para 2016...")
    run_command(["sudo", "samba-tool", "domain", "level", "raise", "--domain-level=2016", "--forest-level=2016"])

    print("Reiniciando o serviço Samba após as atualizações...")
    run_command(["sudo", "systemctl", "start", "samba"])
    print("Verificando a base de dados após a elevação...")
    run_command(["sudo", "samba-tool", "dbcheck", "--cross-ncs", "--fix", "--yes"])

    print("✅ Etapa de atualização e elevação concluída.")

def corrigir_particoes_dns():
    """Verifica e corrige o atributo msDS-SDReferenceDomain nas partições DNS do AD."""
    print("\n--- FASE 9: CORRIGINDO PARTIÇÕES DE DNS ---")
    wait_for_enter("O próximo passo irá verificar e corrigir um atributo importante nas zonas de DNS do AD.")

    fix_script_path = "/tmp/fix_dns_partitions.py"
    fix_script_content = textwrap.dedent(f"""
#!/usr/bin/python3
# -*- coding: utf-8 -*-
import sys
from samba.auth import system_session
from samba.credentials import Credentials
from samba.samdb import SamDB
import optparse
import samba.getopt as options

try:
    parser = optparse.OptionParser("fix_dns_partitions.py")
    sambaopts = options.SambaOptions(parser)
    lp = sambaopts.get_loadparm()
    creds = Credentials()
    creds.guess(lp)
    samdb = SamDB(session_info=system_session(), credentials=creds, lp=lp)

    domain_dn = samdb.get_default_basedn()
    domain_name = lp.get('realm').lower()
    partitions_dn = "CN=Partitions," + str(samdb.get_config_basedn())
    search_filter = f"(|(dnsRoot=DomainDnsZones.{{domain_name}})(dnsRoot=ForestDnsZones.{{domain_name}}))"

    print(f"Verificando partições DNS para o domínio {{domain_name}}...")
    partitions = samdb.search(base=partitions_dn, expression=search_filter, scope=1)

    for p in partitions:
        if 'msDS-SDReferenceDomain' not in p:
            print(f"  -> Corrigindo partição: {{p['dn']}}")
            ldif_data = (
                f"dn: {{p['dn']}}\\n"
                f"changetype: modify\\n"
                f"replace: msDS-SDReferenceDomain\\n"
                f"msDS-SDReferenceDomain: {{str(domain_dn)}}\\n"
            )
            samdb.modify_ldif(ldif_data)
            print(f"     ... Atributo 'msDS-SDReferenceDomain' adicionado com sucesso.")
        else:
            print(f"  -> Partição já está correta: {{p['dn']}}")

    print("Verificação e correção concluídas.")
    sys.exit(0)
except Exception as e:
    print(f"Ocorreu um erro ao corrigir partições: {{e}}")
    sys.exit(1)
    """)
    create_file_with_content(fix_script_path, fix_script_content)
    run_command(["sudo", "python3", fix_script_path])
    run_command(["sudo", "rm", "-f", fix_script_path])
    print("✅ Correção das partições de DNS concluída.")

def main():
    """Função principal que orquestra toda a instalação."""
    if os.geteuid() != 0:
        print("\n❌ ERRO: Este script precisa ser executado como root ou com 'sudo'.")
        sys.exit(1)

    clear = lambda: os.system('clear')
    clear()
    print("======================================================")
    print(" Script Final de Instalação e Atualização do Samba AD DC")
    print("======================================================")
    wait_for_enter()

    try:
        limpeza_previa()
        preparacao_sistema_fedora()

        # Fase 2: Configuração INTERATIVA de rede
        topologia = configurar_rede_e_iptables()

        # Monta a string de interfaces para o Samba: lo + LANs + VLANs
        interfaces_samba_lista = ['lo'] + topologia['lans'] + topologia['vlan_interfaces']
        interfaces_samba_str = ' '.join(interfaces_samba_lista)

        configurar_ntp_fedora()
        provisionar_dominio()
        configurar_samba_e_bind(interfaces_samba_str)
        integracao_final()

        print("\n🎉 Verificando status pós-instalação...")
        run_command(["sudo", "systemctl", "status", "samba", "named", "chronyd", "--no-pager"], check=False)

        atualizar_e_elevar_niveis()
        corrigir_particoes_dns()

        print("\n======================================================")
        print("✅ SUCESSO! Seu Domain Controller foi configurado e otimizado.")
        print("Schema: 2019, Níveis Funcionais: 2016, Partições DNS: Corrigidas.")
        print("======================================================")

    except Exception as e:
        print(f"\n❌ Ocorreu um erro inesperado durante a instalação: {e}")
        sys.exit(1)

if __name__ == "__main__":
    main()
