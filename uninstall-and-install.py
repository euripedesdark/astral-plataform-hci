import subprocess
import os
import glob
import shutil

def run_command(cmd):
    print(f"Executando: {' '.join(cmd)}")
    # Usamos shell=True para comandos com sudo ou caminhos complexos funcionarem melhor
    subprocess.run(" ".join(cmd), shell=True, check=True)

def find_java_bin(bin_name):
    # 1. Tenta achar globalmente
    path = shutil.which(bin_name)
    if path:
        return path

    # 2. Tenta achar nas pastas do Oracle JDK 21
    oracle_paths = glob.glob(f"/usr/lib/jvm/jdk-21*/bin/{bin_name}")
    if oracle_paths:
        return oracle_paths[0]

    # 3. Tenta achar nas pastas do OpenJDK 21
    open_paths = glob.glob(f"/usr/lib/jvm/java-21-openjdk*/bin/{bin_name}")
    if open_paths:
        return open_paths[0]

    # Fallback cego
    return bin_name

def main():
    # Caminho base fixo
    base_dir = os.path.expanduser("~/astral-plataform-hci")
    os.chdir(base_dir)

    # Busca os executáveis de forma inteligente
    javac = find_java_bin("javac")
    java = find_java_bin("java")
    jar = find_java_bin("jar")

    print(f"--- Binários detectados ---")
    print(f"JAVAC: {javac}")
    print(f"JAVA:  {java}")
    print(f"JAR:   {jar}")
    print(f"---------------------------")

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
