import subprocess
import os

def run_command(cmd, use_shell=False):
    print(f"Executando: {cmd}")
    result = subprocess.run(cmd, shell=use_shell)
    if result.returncode != 0:
        print(f"⚠️ Erro ao executar: {cmd}")

def main():
    # Caminho base
    base_dir = os.path.expanduser("~/astral-plataform-hci")
    os.chdir(base_dir)

    # Mata processo se existir
    run_command(["sudo", "pkill", "-f", "installer-firewall.jar"])

    # Remove arquivos antigos
    run_command(["sudo", "rm", "-rf", "tools-classes", "installer-firewall.jar"])

    # Recria pasta sem sudo
    os.makedirs("tools-classes", exist_ok=True)

    # Compila o Java
    javac = "/usr/lib/jvm/jdk-21.0.12.1-oracle-x64/bin/javac"
    run_command([javac, "-d", "tools-classes", "src/main/java/com/astral/tools/InstallerFirewall.java"])

    # Cria o JAR
    jar = "/usr/lib/jvm/jdk-21.0.12.1-oracle-x64/bin/jar"
    run_command([jar, "cfe", "installer-firewall.jar", "com.astral.tools.InstallerFirewall", "-C", "tools-classes", "."])

    # Executa como sudo
    java = "/usr/lib/jvm/jdk-21.0.12.1-oracle-x64/bin/java"
    run_command(["sudo", java, "-jar", "installer-firewall.jar"])

if __name__ == "__main__":
    main()
