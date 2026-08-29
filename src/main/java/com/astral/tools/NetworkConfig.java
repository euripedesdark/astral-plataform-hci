package com.astral.tools;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Configurador de Rede - Astral Platform
 * Uso: sudo java -cp target/classes com.astral.tools.NetworkConfig
 *
 * Configura WAN, LAN, VLAN, DHCP (dnsmasq) e Firewall
 * Usa exclusivamente NetworkManager (nmcli) e iptables
 */
public class NetworkConfig {

    public static void main(String[] args) throws Exception {
        if (!isRoot()) {
            System.err.println("ERRO: Execute com sudo");
            System.exit(1);
        }

        Scanner scanner = new Scanner(System.in);
        System.out.println("=".repeat(60));
        System.out.println("[ASTRAL PLATFORM] CONFIGURADOR DE REDE");
        System.out.println("=".repeat(60));

        // 1. Exterminar resolved e netplan
        System.out.println("\n🧹 Limpando gerenciadores de rede conflitantes...");
        nukeResolvedAndNetplan();

        // 2. Configurar WAN
        System.out.println("\n🌐 Configurar WAN estática");
        String wanIface = askQuestion(scanner, "Interface WAN", autoDetectWAN());
        String publicIP = askQuestion(scanner, "IP público", "");
        String publicMask = askQuestion(scanner, "Máscara CIDR", "24");
        String publicGW = askQuestion(scanner, "Gateway", "");

        List<String> dnsServers = new ArrayList<>();
        if (askYesNo(scanner, "Configurar DNS?", true)) {
            dnsServers.add(askQuestion(scanner, "DNS primário", "8.8.8.8"));
            if (askYesNo(scanner, "DNS secundário?", false)) {
                dnsServers.add(askQuestion(scanner, "DNS secundário", "8.8.4.4"));
            }
        }

        configureWANStatic(wanIface, publicIP, publicMask, publicGW, dnsServers);

        // 3. Configurar LAN/VLAN
        System.out.println("\n🔌 Configurar interfaces LAN/VLAN");
        List<String> lanIfaces = new ArrayList<>();
        if (askYesNo(scanner, "Configurar interfaces LAN/VLAN?", true)) {
            String ifacesStr = askQuestion(scanner, "Interfaces (separadas por vírgula)", "");
            if (!ifacesStr.isEmpty()) {
                lanIfaces.addAll(Arrays.asList(ifacesStr.split(",")));
            }
        }

        boolean setupDHCP = false;
        if (!lanIfaces.isEmpty()) {
            setupDHCP = askYesNo(scanner, "Habilitar DHCP (dnsmasq) para essas interfaces?", true);
        }

        for (String iface : lanIfaces) {
            String ipCIDR = askQuestion(scanner, "IP com CIDR para " + iface.trim(), "192.168.10.1/24");
            processLANVLAN(iface.trim(), ipCIDR);
        }

        // 4. Configurar DHCP
        if (setupDHCP) {
            String domain = askQuestion(scanner, "Domínio DHCP", "astral.local");
            setupDHCP(lanIfaces, dnsServers, domain);
        }

        // 5. Aplicar firewall
        System.out.println("\n🔥 Aplicando regras de firewall...");
        applyFirewallRules(wanIface, lanIfaces, setupDHCP);

        System.out.println("\n" + "=".repeat(60));
        System.out.println("✅ Configuração de rede concluída!");
        System.out.println("=".repeat(60));

        scanner.close();
    }

    // ========== FUNÇÕES DE CONFIGURAÇÃO ==========

    private static void nukeResolvedAndNetplan() {
        runCmd("systemctl stop systemd-resolved 2>/dev/null || true", true);
        runCmd("systemctl disable systemd-resolved 2>/dev/null || true", true);

        if (Files.isSymbolicLink(Paths.get("/etc/resolv.conf"))) {
            try {
                Files.delete(Paths.get("/etc/resolv.conf"));
                System.out.println("✅ Symlink do /etc/resolv.conf removido");
            } catch (IOException e) {
                // Ignora
            }
        }

        runCmd("systemctl stop systemd-networkd 2>/dev/null || true", true);
        runCmd("systemctl disable systemd-networkd 2>/dev/null || true", true);

        if (Files.exists(Paths.get("/etc/netplan"))) {
            System.out.println("🗑️  Desativando configurações do Netplan...");
            runCmd("mkdir -p /etc/netplan/backup_disabled", false);
            runCmd("mv /etc/netplan/*.yaml /etc/netplan/backup_disabled/ 2>/dev/null || true", false);
        }

        runCmd("systemctl enable NetworkManager", true);
        runCmd("systemctl start NetworkManager", true);
    }

    private static String autoDetectWAN() {
        String output = runCmd("ip route show default", false);
        if (output != null) {
            Matcher m = Pattern.compile("dev\\s+(\\S+)").matcher(output);
            if (m.find()) {
                return m.group(1);
            }
        }
        return "ens160";
    }

    private static void configureWANStatic(String iface, String ip, String mask, String gw, List<String> dns) {
        System.out.println("\n⚙️  Configurando WAN estática em " + iface + "...");

        String cidr = ip + "/" + mask;
        String conName = getConnectionString(iface);

        if (conName.isEmpty()) {
            conName = "System_" + iface;
            runCmd("nmcli con add type ethernet ifname " + iface + " con-name " + conName, true);
        }

        runCmd("nmcli con mod " + conName + " ipv4.method manual ipv4.addresses " + cidr + " ipv4.gateway " + gw, true);

        if (!dns.isEmpty()) {
            String dnsStr = String.join(" ", dns);
            runCmd("nmcli con mod " + conName + " ipv4.dns \"" + dnsStr + "\" ipv4.ignore-auto-dns yes", true);
        }

        // Proteger /etc/resolv.conf
        disableNMDNSOverwrite(dns);

        runCmd("nmcli con up " + conName, true);
    }

    private static void disableNMDNSOverwrite(List<String> dnsServers) {
        try {
            Path confDir = Paths.get("/etc/NetworkManager/conf.d");
            Files.createDirectories(confDir);

            Path confFile = confDir.resolve("90-dns-none.conf");
            Files.writeString(confFile, "[main]\ndns=none\n");

            try (PrintWriter pw = new PrintWriter(new FileWriter("/etc/resolv.conf"))) {
                for (String server : dnsServers) {
                    pw.println("nameserver " + server);
                }
            }

            runCmd("systemctl reload NetworkManager", false);
        } catch (IOException e) {
            System.err.println("⚠️  Falha ao proteger /etc/resolv.conf: " + e.getMessage());
        }
    }

    private static void processLANVLAN(String iface, String ipCIDR) {
        System.out.println("\n🔌 Configurando interface: " + iface + " com IP " + ipCIDR);

        String conName = getConnectionString(iface);

        if (conName.isEmpty()) {
            if (iface.contains(".")) {
                // VLAN
                String[] parts = iface.split("\\.");
                String parent = parts[0];
                String vlanId = parts[1];
                runCmd("nmcli con add type vlan ifname " + iface + " dev " + parent + " id " + vlanId + " con-name " + iface, true);
            } else {
                runCmd("nmcli con add type ethernet ifname " + iface + " con-name " + iface, true);
            }
            conName = iface;
        }

        // Remover gateways extras
        runCmd("nmcli con mod " + conName + " ipv4.addresses " + ipCIDR + " ipv4.method manual ipv4.gateway \"\" ipv4.routes \"\"", true);
        runCmd("nmcli con up " + conName, true);

        // Limpar rotas default no kernel
        runCmd("ip route del default dev " + iface + " 2>/dev/null || true", false);
    }

    private static void setupDHCP(List<String> ifaces, List<String> dns, String domain) {
        System.out.println("\n📡 Configurando servidor DHCP (dnsmasq)...");

        // Instalar dnsmasq se necessário
        if (runCmd("which dnsmasq", false) == null) {
            String distro = detectDistro();
            switch (distro) {
                case "debian":
                    runCmd("DEBIAN_FRONTEND=noninteractive apt-get install -y dnsmasq", true);
                    break;
                case "rhel":
                    runCmd("dnf install -y dnsmasq", true);
                    break;
                case "arch":
                    runCmd("pacman -S --noconfirm dnsmasq", true);
                    break;
            }
        }

        try {
            Path confDir = Paths.get("/etc/dnsmasq.d");
            Files.createDirectories(confDir);

            StringBuilder content = new StringBuilder();
            content.append("domain-needed\nbogus-priv\n");

            if (!dns.isEmpty()) {
                content.append("dhcp-option=option:dns-server,").append(String.join(",", dns)).append("\n");
            } else {
                content.append("dhcp-option=option:dns-server,8.8.8.8,8.8.4.4\n");
            }

            if (!domain.isEmpty()) {
                content.append("domain=").append(domain).append("\n");
                content.append("dhcp-option=option:domain-name,").append(domain).append("\n");
            }

            for (String iface : ifaces) {
                String ifaceTrimmed = iface.trim();
                // Calcular range DHCP (.100 a .199)
                String baseIP = getNetworkBase(ifaceTrimmed);
                content.append("\n# Configuração para ").append(ifaceTrimmed).append("\n");
                content.append("interface=").append(ifaceTrimmed).append("\n");
                content.append("dhcp-range=").append(ifaceTrimmed).append(",")
                       .append(baseIP).append(".100,").append(baseIP).append(".199,12h\n");
                content.append("dhcp-option=").append(ifaceTrimmed).append(",option:router,").append(baseIP).append(".1\n");
            }

            Path confFile = confDir.resolve("lan-dhcp.conf");
            Files.writeString(confFile, content.toString());

            // Adicionar conf-dir ao dnsmasq.conf se necessário
            Path mainConf = Paths.get("/etc/dnsmasq.conf");
            if (Files.exists(mainConf)) {
                String mainContent = Files.readString(mainConf);
                if (!mainContent.contains("conf-dir=/etc/dnsmasq.d")) {
                    Files.writeString(mainConf, mainContent + "\nconf-dir=/etc/dnsmasq.d/,*.conf\n");
                }
            }

            runCmd("systemctl enable dnsmasq", true);
            String restartResult = runCmd("systemctl restart dnsmasq", true);
            if (restartResult != null) {
                System.out.println("✅ dnsmasq DHCP configurado e rodando!");
            } else {
                System.err.println("❌ dnsmasq falhou ao iniciar. Verifique: journalctl -xeu dnsmasq");
            }

        } catch (IOException e) {
            System.err.println("❌ Falha ao configurar DHCP: " + e.getMessage());
        }
    }

    private static void applyFirewallRules(String wanIface, List<String> lanIfaces, boolean setupDHCP) {
        System.out.println("\n🔥 Aplicando regras de firewall...");

        // Habilitar IP forwarding
        try {
            Path sysctlConf = Paths.get("/etc/sysctl.d/99-ipforward.conf");
            Files.writeString(sysctlConf, "net.ipv4.ip_forward=1\n");
            runCmd("sysctl -p /etc/sysctl.d/99-ipforward.conf", false);
        } catch (IOException e) {
            System.err.println("⚠️  Falha ao habilitar IP forwarding: " + e.getMessage());
        }

        // Limpar regras existentes
        runCmd("iptables -F", false);
        runCmd("iptables -X", false);
        runCmd("iptables -t nat -F", false);
        runCmd("iptables -t nat -X", false);

        // Políticas padrão
        runCmd("iptables -P INPUT DROP", false);
        runCmd("iptables -P FORWARD DROP", false);
        runCmd("iptables -P OUTPUT ACCEPT", false);

        // Loopback e conexões estabeecidas
        runCmd("iptables -A INPUT -i lo -j ACCEPT", false);
        runCmd("iptables -A INPUT -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT", false);

        // DHCP e DNS nas interfaces LAN
        if (setupDHCP) {
            for (String lan : lanIfaces) {
                String lanTrimmed = lan.trim();
                runCmd("iptables -A INPUT -i " + lanTrimmed + " -p udp -m multiport --dports 67,68 -j ACCEPT", false);
                runCmd("iptables -A INPUT -i " + lanTrimmed + " -p udp --dport 53 -j ACCEPT", false);
                runCmd("iptables -A INPUT -i " + lanTrimmed + " -p tcp --dport 53 -j ACCEPT", false);
            }
        }

        // NAT e forwarding
        for (String lan : lanIfaces) {
            String lanTrimmed = lan.trim();
            runCmd("iptables -A FORWARD -i " + lanTrimmed + " -o " + wanIface + " -j ACCEPT", false);
            runCmd("iptables -A FORWARD -i " + wanIface + " -o " + lanTrimmed + " -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT", false);
        }

        runCmd("iptables -t nat -A POSTROUTING -o " + wanIface + " -j MASQUERADE", false);

        // Persistir regras
        String distro = detectDistro();
        switch (distro) {
            case "debian":
                runCmd("apt-get install -y iptables-persistent", true);
                runCmd("mkdir -p /etc/iptables && iptables-save > /etc/iptables/rules.v4", false);
                runCmd("systemctl enable netfilter-persistent", false);
                break;
            case "arch":
                runCmd("pacman -S --noconfirm iptables-nft", true);
                runCmd("mkdir -p /etc/iptables && iptables-save > /etc/iptables/iptables.rules", false);
                runCmd("systemctl enable iptables", false);
                break;
            default:
                runCmd("mkdir -p /etc/sysconfig && iptables-save > /etc/sysconfig/iptables", false);
                runCmd("systemctl enable iptables", false);
                break;
        }

        System.out.println("✅ Regras de firewall salvas com sucesso!");
    }

    // ========== UTILITÁRIOS ==========

    private static String getConnectionString(String iface) {
        String output = runCmd("nmcli -t -f NAME,DEVICE con show | awk -F: '$2==\"" + iface + "\" {print $1}'", false);
        if (output != null && !output.trim().isEmpty()) {
            return output.trim().split("\n")[0];
        }
        return "";
    }

    private static String getNetworkBase(String iface) {
        String output = runCmd("ip -4 addr show dev " + iface + " | grep -oP '(?<=inet\\s)\\d+(\\.\\d+){3}/\\d+'", false);
        if (output != null && !output.trim().isEmpty()) {
            String ip = output.trim().split("/")[0];
            String[] parts = ip.split("\\.");
            return parts[0] + "." + parts[1] + "." + parts[2];
        }
        return "192.168.10";
    }

    private static String detectDistro() {
        try {
            String content = Files.readString(Paths.get("/etc/os-release")).toLowerCase();
            if (content.contains("debian") || content.contains("ubuntu")) return "debian";
            if (content.contains("rhel") || content.contains("fedora") ||
                content.contains("centos") || content.contains("rocky")) return "rhel";
            if (content.contains("arch")) return "arch";
        } catch (IOException e) {
            // Ignora
        }
        return "unknown";
    }

    private static String askQuestion(Scanner scanner, String prompt, String defaultValue) {
        String displayPrompt = defaultValue.isEmpty() ? prompt : prompt + " [" + defaultValue + "]";
        System.out.print(displayPrompt + ": ");
        String input = scanner.nextLine().trim();
        return input.isEmpty() ? defaultValue : input;
    }

    private static boolean askYesNo(Scanner scanner, String prompt, boolean defaultValue) {
        String display = defaultValue ? "Y/n" : "y/N";
        System.out.print(prompt + " (" + display + "): ");
        String input = scanner.nextLine().trim().toLowerCase();
        if (input.isEmpty()) return defaultValue;
        return input.startsWith("y");
    }

    private static String runCmd(String cmd, boolean log) {
        if (log) System.out.println("$ " + cmd);
        try {
            Process p = new ProcessBuilder("bash", "-c", cmd)
                .redirectErrorStream(true)
                .start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (log) System.out.println("  " + line);
                    output.append(line).append("\n");
                }
            }

            int exitCode = p.waitFor();
            if (exitCode != 0 && log) {
                System.err.println("  ⚠️  Comando retornou código: " + exitCode);
            }
            return exitCode == 0 ? output.toString() : null;
        } catch (Exception e) {
            if (log) e.printStackTrace();
            return null;
        }
    }

    private static boolean isRoot() {
        return System.getProperty("user.name").equals("root") ||
               ProcessHandle.current().info().user().orElse("").equals("root");
    }
}
