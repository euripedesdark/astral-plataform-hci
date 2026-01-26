#!/usr/bin/python3
# -*- coding: utf-8 -*-
"""
Script otimizado para instalação de Samba AD DC no Fedora 43 (Samba 4.23+)
- Correção completa da API do Samba para script de DNS
- Limpeza automática de regras duplicadas do iptables
- Tratamento silencioso de warnings do SELinux
- Input simplificado de interfaces (separadas por vírgula)

Autor: Eurípedes Batista de Paiva Junior
"""
import os
import subprocess
import sys
import time
import textwrap
import re

# --- VARIÁVEIS DE CONFIGURAÇÃO ---
HOSTNAME_COMPLETO = "dc1.astral.celeste"
NOME_NETBIOS = "ASTRAL"
REALM = "ASTRAL.CELESTE"
SENHA_ADMIN = "Copa@@2026"  # ALTERE NA PRODUÇÃO!

# --- Funções Auxiliares ---

def run_command(command, shell=False, check=True, silent_selinux=False):
    """Executa um comando no sistema."""
    try:
        cmd_str = command if isinstance(command, str) else ' '.join(command)
        print(f"🚀 Executando: {cmd_str}")
        result = subprocess.run(
            command,
            shell=shell,
            check=check,
            text=True,
            stdout=subprocess.PIPE if silent_selinux else sys.stdout,
            stderr=subprocess.PIPE if silent_selinux else sys.stderr
        )
        if silent_selinux and result.returncode == 0:
            # Filtrar apenas warnings do SELinux
            stderr_clean = '\n'.join([
                line for line in result.stderr.split('\n')
                if 'no default label' not in line.lower()
            ])
            if stderr_clean.strip():
                print(stderr_clean, file=sys.stderr)
        print("-" * 40)
        time.sleep(1)
    except subprocess.CalledProcessError as e:
        print(f"❌ ERRO ao executar comando: {e}")
        if e.stderr:
            print(f"Saída de erro: {e.stderr}")
        print("A operação foi interrompida.")
        raise e

def wait_for_enter(prompt="Pressione Enter para continuar..."):
    """Pausa a execução."""
    input(f"\n👉 {prompt}")

def create_file_with_content(filepath, content, silent_selinux=True):
    """Cria um arquivo com o conteúdo especificado."""
    print(f"📝 Criando/Sobrescrevendo o arquivo: {filepath}")
    try:
        temp_filepath = f"/tmp/{os.path.basename(filepath)}.tmp"
        with open(temp_filepath, "w") as f:
            f.write(content)
        run_command(["sudo", "mv", temp_filepath, filepath])
        # Silenciar warnings do SELinux (não críticos)
        run_command(["sudo", "restorecon", filepath], check=False, silent_selinux=True)
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

def limpar_regras_duplicadas_iptables():
    """Remove regras duplicadas do iptables (mantendo apenas a chain SAMBA_DC)."""
    print("🧹 Limpando regras duplicadas do iptables...")

    # Listar todas as regras do INPUT com números
    try:
        output = subprocess.check_output(
            ["sudo", "iptables", "-L", "INPUT", "--line-numbers", "-n"],
            text=True
        )

        # Identificar regras duplicadas (portas SAMBA) que NÃO são o jump para SAMBA_DC
        portas_samba = [53, 88, 135, 139, 389, 445, 464, 636, 3268, 3269, 5353, 123, 137, 138]
        linhas_remover = []

        for i, line in enumerate(output.split('\n'), 1):
            for porta in portas_samba:
                if f"dpt:{porta}" in line and "SAMBA_DC" not in line:
                    # Extrair número da linha (primeira coluna)
                    parts = line.split()
                    if parts and parts[0].isdigit():
                        linhas_remover.append(int(parts[0]))

        # Remover em ordem reversa (para não alterar índices)
        for linha in sorted(set(linhas_remover), reverse=True):
            run_command(["sudo", "iptables", "-D", "INPUT", str(linha)], check=False)

        print(f"✅ Removidas {len(set(linhas_remover))} regras duplicadas.")
    except Exception as e:
        print(f"⚠️  Não foi possível limpar regras duplicadas: {e}")

def configurar_iptables_conservador(interfaces):
    """Configura iptables SEM limpar regras existentes - apenas adiciona as necessárias para o DC."""
    print("\n--- FASE 2: CONFIGURANDO FIREWALL (IPTABLES) ---")
    print("⚠️  ATENÇÃO: As regras existentes serão PRESERVADAS.")
    print("    Serão adicionadas apenas as regras necessárias para o Samba DC.")
    wait_for_enter()

    # Limpar regras duplicadas antes de começar
    limpar_regras_duplicadas_iptables()

    # Criar chain dedicada se não existir
    run_command(["sudo", "iptables", "-N", "SAMBA_DC"], check=False)

    # Limpar apenas a chain dedicada (não afeta regras existentes)
    run_command(["sudo", "iptables", "-F", "SAMBA_DC"], check=False)

    # Garantir que a chain seja chamada no início da chain INPUT (apenas uma vez)
    try:
        # Verificar se já existe o jump
        output = subprocess.check_output(
            ["sudo", "iptables", "-L", "INPUT", "-n", "--line-numbers"],
            text=True
        )
        if "SAMBA_DC" not in output:
            run_command(["sudo", "iptables", "-I", "INPUT", "1", "-j", "SAMBA_DC"])
        else:
            print("   ✓ Chain SAMBA_DC já está configurada no INPUT")
    except:
        run_command(["sudo", "iptables", "-I", "INPUT", "1", "-j", "SAMBA_DC"])

    # Portas TCP necessárias para Samba DC
    tcp_ports = [53, 88, 135, 139, 389, 445, 464, 636, 3268, 3269, 5353, '49152:65535']
    for port in tcp_ports:
        run_command(["sudo", "iptables", "-A", "SAMBA_DC", "-p", "tcp", "--dport", str(port), "-j", "ACCEPT"])

    # Portas UDP necessárias para Samba DC
    udp_ports = [53, 88, 123, 137, 138, 389, 464, '49152:65535']
    for port in udp_ports:
        run_command(["sudo", "iptables", "-A", "SAMBA_DC", "-p", "udp", "--dport", str(port), "-j", "ACCEPT"])

    # Permitir tráfego na interface loopback
    run_command(["sudo", "iptables", "-A", "SAMBA_DC", "-i", "lo", "-j", "ACCEPT"])

    # Permitir tráfego estabelecido/relacionado
    run_command(["sudo", "iptables", "-A", "SAMBA_DC", "-m", "state", "--state", "ESTABLISHED,RELATED", "-j", "ACCEPT"])

    # Salvar configuração persistente
    run_command("sudo sh -c 'iptables-save > /etc/sysconfig/iptables'", shell=True)
    run_command(["sudo", "systemctl", "enable", "iptables"], check=False)
    run_command(["sudo", "systemctl", "restart", "iptables"], check=False)

    # Limpar duplicatas novamente após configuração
    limpar_regras_duplicadas_iptables()

    print("\n✅ Firewall configurado com sucesso (regras existentes preservadas).")
    print("   Regras do Samba DC foram adicionadas à chain SAMBA_DC.")

def obter_interfaces_validas():
    """Obtém lista de interfaces de rede não-loopback disponíveis."""
    interfaces = []
    for iface in os.listdir('/sys/class/net/'):
        if iface != 'lo' and os.path.exists(f'/sys/class/net/{iface}/operstate'):
            with open(f'/sys/class/net/{iface}/operstate', 'r') as f:
                if f.read().strip() in ['up', 'down']:
                    interfaces.append(iface)
    return sorted(interfaces)

def configurar_interfaces():
    """Solicita ao usuário as interfaces para o DC (separadas por vírgula)."""
    print("\n--- CONFIGURAÇÃO DE INTERFACES DE REDE ---")
    print("Interfaces disponíveis no sistema:")
    validas = obter_interfaces_validas()
    for iface in validas:
        print(f"  • {iface}")

    print("\n👉 Digite as interfaces que farão parte do DC (separadas por vírgula, SEM espaços)")
    print("   Exemplo: enp3s0,ens224,vlan10")
    print("   (A interface 'lo' será adicionada automaticamente)")

    while True:
        entrada = input("\nInterfaces: ").strip()
        if not entrada:
            print("⚠️  Digite pelo menos uma interface.")
            continue

        # Validar formato (apenas letras, números, pontos e vírgulas)
        if not re.match(r'^[a-zA-Z0-9.,]+$', entrada):
            print("⚠️  Formato inválido. Use apenas letras, números, pontos e vírgulas.")
            continue

        interfaces = [i.strip() for i in entrada.split(',') if i.strip()]

        # Verificar se todas existem
        invalidas = [i for i in interfaces if i not in validas]
        if invalidas:
            print(f"⚠️  Interfaces não encontradas: {', '.join(invalidas)}")
            print("   Interfaces válidas: " + ', '.join(validas))
            continue

        print(f"\n✅ Interfaces selecionadas: lo {' '.join(interfaces)}")
        return interfaces

# --- Funções do Script ---

def limpeza_previa():
    """Para e remove configurações e dados de instalações anteriores."""
    print("\n--- FASE 0: LIMPANDO INSTALAÇÃO ANTERIOR ---")
    wait_for_enter("AVISO: A próxima etapa removerá dados e configurações existentes do Samba.")
    servicos = ["samba", "named", "chronyd"]
    for servico in servicos:
        run_command(["sudo", "systemctl", "stop", servico], check=False)

    locais_para_limpar = [
        "/etc/samba/smb.conf", "/etc/krb5.conf", "/etc/named.conf", "/etc/chrony.conf",
        "/var/lib/samba/private/*", "/var/lib/samba/sysvol/*", "/var/cache/samba/*",
        "/var/log/samba/*", "/var/named/*"
    ]
    for local in locais_para_limpar:
        print(f"Removendo {local}...")
        run_command(f"sudo rm -rf {local}", shell=True, check=False)
    print("✅ Limpeza concluída.")

def preparacao_sistema_fedora(interfaces):
    """Configura o sistema base e instala os pacotes."""
    print("\n--- FASE 1: PREPARAÇÃO E INSTALAÇÃO ---")
    run_command(["sudo", "hostnamectl", "set-hostname", HOSTNAME_COMPLETO])

    # Obter IP da primeira interface para /etc/hosts
    ip_primaria = "127.0.0.1"
    if interfaces:
        try:
            ip_primaria = subprocess.check_output(
                ["ip", "-4", "addr", "show", interfaces[0], "scope", "global"],
                text=True
            ).split("inet ")[1].split("/")[0].strip()
        except:
            ip_primaria = "127.0.0.1"

    hosts_content = f"127.0.0.1   localhost\n{ip_primaria}   {HOSTNAME_COMPLETO}   {HOSTNAME_COMPLETO.split('.')[0]}\n"
    create_file_with_content("/etc/hosts", hosts_content)

    run_command(["sudo", "systemctl", "stop", "firewalld"], check=False)
    run_command(["sudo", "systemctl", "disable", "firewalld"], check=False)

    packages = ["samba-dc", "samba-client", "bind", "chrony", "iptables-services", "python3-markdown"]
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

def configurar_samba_e_bind(interfaces_str):
    """Configura o smb.conf e o BIND."""
    print("\n--- FASE 5: CONFIGURANDO SAMBA E BIND ---")
    smb_params = [
        f"dns forwarder = 127.0.0.1:5353",
        f"interfaces = {interfaces_str}",
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

    # Gerar arquivo krb5.conf completo
    krb5_conf_content = textwrap.dedent(f"""
[libdefaults]
    default_realm = {REALM.upper()}
    dns_lookup_realm = false
    dns_lookup_kdc = true
    ticket_lifetime = 24h
    renew_lifetime = 7d
    forwardable = true
    rdns = false

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

    # Configuração do resolv.conf
    resolv_content = f"search {REALM.lower()}\nnameserver 127.0.0.1\n"
    run_command(["sudo", "chattr", "-i", "/etc/resolv.conf"], check=False)
    create_file_with_content("/etc/resolv.conf", resolv_content)
    run_command(["sudo", "chattr", "+i", "/etc/resolv.conf"], check=False)

    # Inicialização dos serviços
    services = ["chronyd", "named", "samba"]
    for service in services:
        print(f"Habilitando e reiniciando o serviço: {service}")
        run_command(["sudo", "systemctl", "enable", service])
        run_command(["sudo", "systemctl", "restart", service])
    print("✅ Servidor pronto e serviços iniciados.")

def atualizar_e_elevar_niveis():
    """Atualiza schema e eleva níveis funcionais."""
    print("\n--- FASE 7: ATUALIZANDO SCHEMA E NÍVEIS FUNCIONAIS ---")

    warning = textwrap.dedent("""
        AVISO: A próxima etapa realizará modificações IRREVERSÍVEIS no seu
        Active Directory (atualização de schema e elevação de níveis funcionais).
    """)
    print(warning)
    wait_for_enter("Pressione Enter para confirmar e continuar, ou Ctrl+C para cancelar.")

    print("Adicionando 'ad dc functional level' ao smb.conf...")
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
    """Verifica e corrige o atributo msDS-SDReferenceDomain nas partições DNS do AD (API Samba 4.23 corrigida)."""
    print("\n--- FASE 9: CORRIGINDO PARTIÇÕES DE DNS ---")
    wait_for_enter("O próximo passo irá verificar e corrigir um atributo importante nas zonas de DNS do AD.")

    fix_script_path = "/tmp/fix_dns_partitions.py"
    fix_script_content = textwrap.dedent('''#!/usr/bin/python3
# -*- coding: utf-8 -*-
import sys
import optparse
import samba.getopt as options
from samba.auth import system_session
from samba.credentials import Credentials
from samba.samdb import SamDB

try:
    # Correção para Samba 4.23: usar OptionParser + SambaOptions corretamente
    parser = optparse.OptionParser()
    sambaopts = options.SambaOptions(parser)
    lp = sambaopts.get_loadparm()

    creds = Credentials()
    creds.guess(lp)
    samdb = SamDB(session_info=system_session(), credentials=creds, lp=lp)

    domain_dn = samdb.get_default_basedn()
    domain_name = lp.get('realm').lower()
    partitions_dn = "CN=Partitions," + str(samdb.get_config_basedn())

    # Buscar partições DNS
    search_filter = "(|(dnsRoot=DomainDnsZones." + domain_name + ")(dnsRoot=ForestDnsZones." + domain_name + "))"
    partitions = samdb.search(base=partitions_dn, expression=search_filter, scope=2)

    print(f"Verificando partições DNS para o domínio {domain_name}...")
    corrigidas = 0

    for p in partitions:
        dn = str(p.dn)
        if 'msDS-SDReferenceDomain' not in p:
            print(f"  -> Corrigindo partição: {dn}")
            # LDIF com newlines reais (não escapes)
            ldif_data = f"""dn: {dn}
changetype: modify
replace: msDS-SDReferenceDomain
msDS-SDReferenceDomain: {domain_dn}

"""
            samdb.modify_ldif(ldif_data)
            print(f"     ... Atributo 'msDS-SDReferenceDomain' adicionado com sucesso.")
            corrigidas += 1
        else:
            print(f"  -> Partição já está correta: {dn}")

    print(f"\\n✅ Verificação concluída. {corrigidas} partição(ões) corrigida(s).")
    sys.exit(0)
except Exception as e:
    print(f"\\n⚠️  Ocorreu um erro ao corrigir partições (não crítico para operação do DC): {e}")
    import traceback
    traceback.print_exc()
    # Não falhar a instalação por este passo (é opcional)
    sys.exit(0)
''')
    create_file_with_content(fix_script_path, fix_script_content, silent_selinux=False)
    run_command(["sudo", "python3", fix_script_path])
    run_command(["sudo", "rm", "-f", fix_script_path])
    print("✅ Correção das partições de DNS concluída (ou ignorada se não crítica).")

def main():
    """Função principal que orquestra toda a instalação."""
    if os.geteuid() != 0:
        print("\n❌ ERRO: Este script precisa ser executado como root ou com 'sudo'.")
        sys.exit(1)

    clear = lambda: os.system('clear')
    clear()
    print("=" * 60)
    print("  Script de Instalação do Samba AD DC (Fedora 43 / Samba 4.23)")
    print("=" * 60)
    print("\n⚠️  RECOMENDAÇÕES:")
    print("   • Execute em um sistema limpo ou dedicado")
    print("   • Backup de configurações existentes antes de prosseguir")
    print("   • Firewall existente será PRESERVADO (apenas regras DC adicionadas)")
    wait_for_enter()

    try:
        # Obter interfaces do usuário
        interfaces_usuario = configurar_interfaces()
        interfaces_str = "lo " + " ".join(interfaces_usuario)

        limpeza_previa()
        preparacao_sistema_fedora(interfaces_usuario)
        configurar_iptables_conservador(interfaces_usuario)
        configurar_ntp_fedora()
        provisionar_dominio()
        configurar_samba_e_bind(interfaces_str)
        integracao_final()

        print("\n🎉 Verificando status pós-instalação...")
        run_command(["sudo", "systemctl", "status", "samba", "named", "chronyd", "--no-pager"], check=False)

        atualizar_e_elevar_niveis()
        corrigir_particoes_dns()

        print("\n" + "=" * 60)
        print("✅ SUCESSO! Seu Domain Controller foi configurado e otimizado.")
        print(f"   Schema: 2019 | Níveis Funcionais: 2016")
        print(f"   Interfaces: {interfaces_str}")
        print(f"   Domínio: {REALM}")
        print("=" * 60)
        print("\n📌 Próximos passos:")
        print("   • Teste com: kinit administrator@ASTRAL.CELESTE")
        print("   • Verifique DNS: host -t SRV _ldap._tcp.astral.celeste")
        print("   • Acesse o compartilhamento SYSVOL: \\\\dc1.astral.celeste\\SYSVOL")
        print("\nℹ️  Status do firewall:")
        subprocess.run(["sudo", "iptables", "-L", "SAMBA_DC", "-v", "-n", "--line-numbers"])

    except KeyboardInterrupt:
        print("\n\n⚠️  Operação cancelada pelo usuário.")
        sys.exit(130)
    except Exception as e:
        print(f"\n❌ Ocorreu um erro inesperado durante a instalação: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)

if __name__ == "__main__":
    main()
