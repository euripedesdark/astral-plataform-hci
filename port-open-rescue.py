import subprocess

# Lista de portas do anexo
ports = [
    8040, 5001, 9090, 8081, 5432,
    5173, 5000, 3000, 443, 80, 22
]

def run_cmd(cmd):
    print(f"Executando: {cmd}")
    subprocess.run(cmd, shell=True, check=True)

def main():
    # Limpa todas as regras
    run_cmd("iptables -F")
    run_cmd("iptables -X")
    run_cmd("iptables -t nat -F")
    run_cmd("iptables -t nat -X")
    run_cmd("iptables -t mangle -F")
    run_cmd("iptables -t mangle -X")
    run_cmd("iptables -P INPUT ACCEPT")
    run_cmd("iptables -P FORWARD ACCEPT")
    run_cmd("iptables -P OUTPUT ACCEPT")

    # Abre as portas necessárias
    for port in ports:
        run_cmd(f"iptables -A INPUT -p tcp --dport {port} -j ACCEPT")
        run_cmd(f"iptables -A INPUT -p udp --dport {port} -j ACCEPT")

    # Configura masquerade (NAT)
    run_cmd("iptables -t nat -A POSTROUTING -o eth0 -j MASQUERADE")

if __name__ == "__main__":
    main()
