package com.astral.fabric.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Familia de distribuicao detectada a partir de {@code /etc/os-release}.
 *
 * <p>Porte direto de {@code detect_os_family()} de
 * {@code fabric/network-firewall/config-wan.py}. A diferenca e' que aqui a
 * deteccao e' pura (sem subprocess) e devolve um enum: a logica de decidir o
 * comando de pacote a partir de um {@code String} e' o tipo de coisa que
 * quebra em silencio quando alguem troca de distro.
 */
public enum DistroFamily {
    REDHAT("dnf", "yum install -y", "iptables-services", "/etc/sysconfig/iptables"),
    DEBIAN("apt-get", "DEBIAN_FRONTEND=noninteractive apt-get install -y", "iptables-persistent", "/etc/iptables/rules.v4"),
    ARCH("pacman", "pacman -Sy --noconfirm --needed", "iptables-nft", "/etc/iptables/iptables.rules"),
    SUSE("zypper", "zypper --non-interactive install", "iptables", "/etc/sysconfig/iptables"),
    DESCONHECIDA("dnf", "yum install -y", "iptables-services", "/etc/sysconfig/iptables");

    private static final Map<String, DistroFamily> CHAVES = Map.ofEntries(
            Map.entry("fedora", REDHAT), Map.entry("rhel", REDHAT), Map.entry("centos", REDHAT),
            Map.entry("nobara", REDHAT), Map.entry("rocky", REDHAT), Map.entry("almalinux", REDHAT),
            Map.entry("amzn", REDHAT),
            Map.entry("debian", DEBIAN), Map.entry("ubuntu", DEBIAN), Map.entry("pop", DEBIAN),
            Map.entry("linuxmint", DEBIAN),
            Map.entry("arch", ARCH), Map.entry("endeavouros", ARCH), Map.entry("manjaro", ARCH),
            Map.entry("suse", SUSE), Map.entry("opensuse", SUSE), Map.entry("sles", SUSE),
            Map.entry("sled", SUSE));

    private final String gerenciador;
    private final String instalacao;
    private final String pacotePersistencia;
    private final String caminhoPersistencia;

    DistroFamily(String gerenciador, String instalacao, String pacotePersistencia, String caminhoPersistencia) {
        this.gerenciador = gerenciador;
        this.instalacao = instalacao;
        this.pacotePersistencia = pacotePersistencia;
        this.caminhoPersistencia = caminhoPersistencia;
    }

    public String gerenciador() { return gerenciador; }

    public String comandoInstalacao(String pacotes) { return instalacao + " " + pacotes; }

    public String pacotePersistencia() { return pacotePersistencia; }

    public String caminhoPersistencia() { return caminhoPersistencia; }

    public boolean suportada() { return this != DESCONHECIDA; }

    public static DistroFamily detectar() {
        return detectar(Path.of("/etc/os-release"));
    }

    static DistroFamily detectar(Path osRelease) {
        try {
            if (!Files.isRegularFile(osRelease)) return DESCONHECIDA;
            String id = "", idLike = "";
            for (String linha : Files.readAllLines(osRelease)) {
                if (linha.startsWith("ID=")) id = limpa(linha);
                else if (linha.startsWith("ID_LIKE=")) idLike = limpa(linha);
            }
            // ID_LIKE cobre derivatives: Pop!_OS declara "ubuntu debian",
            // Nobara declara "fedora". O ID proprio vem primeiro.
            for (String cand : new String[]{id, idLike}) {
                for (String token : cand.split("\\s+")) {
                    DistroFamily f = CHAVES.get(token.toLowerCase(Locale.ROOT));
                    if (f != null) return f;
                }
            }
        } catch (IOException ignorada) {
            // sem os-release nao ha como adivinhar: falha fechada em DESCONHECIDA.
        }
        return DESCONHECIDA;
    }

    private static String limpa(String linha) {
        int i = linha.indexOf('=');
        if (i < 0) return "";
        return linha.substring(i + 1).trim().replace("\"", "");
    }
}
