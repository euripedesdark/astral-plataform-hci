import subprocess
import os

def run_command(cmd):
    print(f"Executando: {' '.join(cmd)}")
    subprocess.run(cmd)

def main():
    # Caminho base fixo para evitar problemas com sudo
    base_dir = os.path.expanduser("~/astral-plataform-hci")
    os.chdir(base_dir)

    javac = "/usr/lib/jvm/jdk-21.0.12.7-oracle-x64/bin/javac"
    java = "/usr/lib/jvm/jdk-21.0.12.7-oracle-x64/bin/java"
    jar = "/usr/lib/jvm/jdk-21.0.12.7-oracle-x64/bin/jar"

    # 1. Compila e executa o Desinstalador
    os.makedirs("uninstaller-classes", exist_ok=True)
    run_command([javac, "-d", "uninstaller-classes", "src/main/java/com/astral/tools/Uninstaller.java"])
    run_command(["sudo", java, "-cp", "uninstaller-classes", "com.astral.tools.Uninstaller"])

    # 2. Limpa resíduos de compilação
    run_command(["sudo", "rm", "-rf", "main-classes", "uninstaller-classes", "installer.jar"])

    # 3. Compila e executa o Instalador Principal
    os.makedirs("main-classes", exist_ok=True)
    run_command([javac, "-d", "main-classes", "src/main/java/com/astral/tools/Installer.java"])
    run_command([jar, "cfe", "installer.jar", "com.astral.tools.Installer", "-C", "main-classes", "."])
    run_command(["sudo", java, "-jar", "installer.jar"])

if __name__ == "__main__":
    main()
