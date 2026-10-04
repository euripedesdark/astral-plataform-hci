package com.astral.fabric.network;

import com.astral.fabric.support.CommandResult;
import com.astral.fabric.support.ShellRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Le, aplica e desfaz enderecamento de rede via NetworkManager.
 *
 * <p>Porte direto de {@code configure_wan_static} e {@code process_lan_vlan} de
 * {@code fabric/network-firewall/config-wan.py}, com tres mudancas de
 * comportamento:
 *
 * <ol>
 *   <li><b>Nada de interativo.</b> O script pedia tudo por {@code input()}; aqui
 *       o sistema recebe um {@link EnderecamentoRede} pronto, validado, vindo
 *       da API. O {@code NetworkConfigCli} continua existindo para quem quiser
 *       a conversa no terminal, e chama este mesmo servico.</li>
 *   <li><b>Snapshot antes de aplicar.</b> E' o que torna o rollback possivel:
 *       os valores anteriores de cada perfil sao capturados ANTES do primeiro
 *       comando, e viram o {@code token} do {@link com.astral.fabric.reconciliation.Commit}.</li>
 *   <li><b>Diff antes do comando.</b> O {@code nmcli con mod} roda uma vez por
 *       interface; religar a mesma configuracao nao recicla a interface e nao
 *       derruba a sessao SSH no meio do commit.</li>
 * </ol>
 *
 * <p>DHCP/nao e' daqui: gerar {@code dnsmasq} e' papel do modulo {@code dns}.
 * Manter as duas coisas juntas e' o que fez o script legado ter 800 linhas.
 */
@Service
public class NetworkAddressingService {

    private static final Logger log = LoggerFactory.getLogger(NetworkAddressingService.class);

    private final ObjectMapper json;
    private final ShellRunner runner;

    public NetworkAddressingService(ObjectMapper json) {
        this.json = json;
        boolean dry = Boolean.parseBoolean(System.getProperty("astral.network.dryRun", "false"));
        this.runner = new ShellRunner(dry);
    }

    // ------------------------------------------------------------------ leitura

    /** Estado observado: a base do diff. */
    public Map<String, Object> observado() {
        Map<String, Object> estado = new LinkedHashMap<>();
        estado.put("interfaces", interfacesDoKernel());
        estado.put("perfis", perfisNmcli());
        estado.put("rotas", runner.saida("ip -4 route show"));
        estado.put("dns", resolvConf());
        return estado;
    }

    /** Estado atual no mesmo formato que a API devolve para a tela. */
    public EnderecamentoRede atual() {
        List<InterfaceRede> ifaces = new ArrayList<>();
        for (Map<String, Object> p : perfisNmcli()) {
            String device = String.valueOf(p.getOrDefault("device", ""));
            String addr = String.valueOf(p.getOrDefault("addresses", ""));
            if (device.isBlank() || device.equals("--") || addr.isBlank()) continue;
            ifaces.add(new InterfaceRede(
                    device,
                    "LAN",
                    addr.split(",")[0].trim(),
                    String.valueOf(p.getOrDefault("gateway", "")),
                    false, null, null));
        }
        List<String> dns = resolvConf();
        if (dns.isEmpty()) dns = List.of("1.1.1.1");
        return new EnderecamentoRede("manual", ifaces, dns, null, false);
    }

    public List<String> interfacesDoKernel() {
        List<String> out = new ArrayList<>();
        for (String l : runner.saida("ls /sys/class/net 2>/dev/null || true").split("\\R")) {
            if (!l.isBlank() && !l.equals("lo")) out.add(l.trim());
        }
        return out;
    }

    public List<String> resolvConf() {
        List<String> dns = new ArrayList<>();
        for (String linha : runner.saida("grep '^nameserver' /etc/resolv.conf 2>/dev/null || true").split("\\R")) {
            String[] partes = linha.trim().split("\\s+");
            if (partes.length == 2 && EnderecamentoRede.IPV4.matcher(partes[1]).matches()) dns.add(partes[1]);
        }
        return dns;
    }

    // ------------------------------------------------------------------ diff

    /** Diff declarativo: so' o que muda vira comando. */
    public List<String> plano(EnderecamentoRede desejo) {
        List<String> plano = new ArrayList<>();
        Map<String, Object> observado = observado();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> perfis = (List<Map<String, Object>>) observado.get("perfis");
        for (InterfaceRede i : desejo.interfaces()) {
            Map<String, Object> atual = perfis.stream()
                    .filter(p -> i.nome().equals(String.valueOf(p.get("device"))))
                    .findFirst().orElse(null);
            if (atual == null
                    || !i.enderecoCidr().equals(String.valueOf(atual.get("addresses")))
                    || !valor(i.gateway()).equals(valor(atual.get("gateway")))) {
                plano.addAll(comandosPara(i, desejo));
            }
        }
        return plano;
    }

    // ------------------------------------------------------------------ commit

    /**
     * Aplica. Devolve o snapshot capturado ANTES do primeiro comando; se algo
     * falhar no meio, desfaz o que deu tempo de aplicar e lanca.
     */
    public Snapshot aplica(EnderecamentoRede desejo) {
        Map<String, Object> antes = observado();
        List<String> plano = plano(desejo);
        List<String> aplicados = new ArrayList<>();
        List<String> falhas = new ArrayList<>();
        for (String c : plano) {
            CommandResult r = runner.sh(c);
            if (r.ok()) aplicados.add(c);
            else {
                falhas.add(c + " -> " + r.saidaUnica());
                break;
            }
        }
        if (!falhas.isEmpty()) {
            desfaz(plano, aplicados, antes);
            throw new IllegalStateException("Falha ao aplicar enderecamento: " + String.join(" | ", falhas));
        }
        log.info("enderecamento: {}/{} comandos aplicados", aplicados.size(), plano.size());
        return new Snapshot(codigo(antes), antes, aplicados);
    }

    /**
     * Rollback deterministico: reaplica o snapshot inteiro em vez de "desfazer
     * o ultimo comando". Ordem inversa da aplicacao, porque e' a unica ordem
     * que nao deixa perfil meio-configurado.
     */
    public void desfaz(Snapshot snapshot) {
        desfaz(snapshot.comandos(), snapshot.comandos(), snapshot.antes());
    }

    private void desfaz(List<String> plano, List<String> aplicados, Map<String, Object> antes) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> perfis = (List<Map<String, Object>>) antes.getOrDefault("perfis", List.of());
        for (int k = aplicados.size() - 1; k >= 0; k--) {
            String perfil = extraiPerfil(aplicados.get(k));
            if (perfil == null) continue;
            perfis.stream()
                    .filter(p -> perfil.equals(String.valueOf(p.get("name"))))
                    .findFirst()
                    .ifPresent(p -> runner.sh("nmcli con mod " + ShellRunner.escapesSeguro(perfil)
                            + " ipv4.method " + ShellRunner.escapesSeguro(valor(p.get("method")))
                            + " ipv4.addresses '" + ShellRunner.escapesSeguro(valor(p.get("addresses")))
                            + "' ipv4.gateway '" + ShellRunner.escapesSeguro(valor(p.get("gateway"))) + "'"));
        }
    }

    // ------------------------------------------------------------------ internals

    private List<String> comandosPara(InterfaceRede i, EnderecamentoRede desejo) {
        List<String> cmd = new ArrayList<>();
        String nome = ShellRunner.escapesSeguro(i.nome());
        String perfil = ShellRunner.escapesSeguro(perfilDa(i.nome()));
        boolean vlan = i.nome().contains(".");

        if (!existePerfil(perfil)) {
            if (vlan) {
                String[] partes = i.nome().split("\\.", 2);
                cmd.add("nmcli con add type vlan ifname " + nome + " dev "
                        + ShellRunner.escapesSeguro(partes[0]) + " id "
                        + ShellRunner.escapesSeguro(partes[1]) + " con-name " + perfil);
            } else {
                cmd.add("nmcli con add type ethernet ifname " + nome + " con-name " + perfil);
            }
        }
        String dns = ShellRunner.escapesSeguro(String.join(" ", desejo.dns() == null ? List.of() : desejo.dns()));
        String metodo = "auto".equals(desejo.metodo()) ? "auto" : "manual";
        cmd.add("nmcli con mod " + perfil
                + " ipv4.method " + metodo
                + " ipv4.addresses '" + ShellRunner.escapesSeguro(i.enderecoCidr()) + "'"
                + " ipv4.gateway '" + ShellRunner.escapesSeguro(valor(i.gateway())) + "'"
                + " ipv4.dns '" + dns + "'"
                + " ipv4.ignore-auto-dns yes");
        cmd.add("nmcli con up " + perfil);
        return cmd;
    }

    private String perfilDa(String iface) {
        return "astral-" + iface;
    }

    private boolean existePerfil(String perfil) {
        String saida = runner.saida("nmcli -t -f NAME connection show 2>/dev/null || true");
        for (String l : saida.split("\\R")) {
            if (l.strip().equals(perfil) || l.strip().replace("\\:", ":").equals(perfil)) return true;
        }
        return false;
    }

    private String extraiPerfil(String comando) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("nmcli con (?:mod|up) '?\"?([^'\"\\s]+)").matcher(comando);
        return m.find() ? m.group(1) : null;
    }

    private List<Map<String, Object>> perfisNmcli() {
        String saida = runner.saida("nmcli -t -f NAME,DEVICE,TYPE connection show 2>/dev/null || true");
        List<Map<String, Object>> out = new ArrayList<>();
        for (String linha : saida.split("\\R")) {
            if (linha.isBlank()) continue;
            String[] p = linha.split("(?<!\\\\):", -1);
            if (p.length < 3) continue;
            String tipo = p[2];
            if (!tipo.contains("ethernet") && !tipo.contains("vlan") && !tipo.contains("bridge")) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p[0].replace("\\:", ":"));
            m.put("device", p[1]);
            m.put("type", tipo);
            m.putAll(detalhes(p[0].replace("\\:", ":")));
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> detalhes(String perfil) {
        Map<String, Object> m = new LinkedHashMap<>();
        String q = perfil.replace("'", "");
        m.put("method", umaLinha("nmcli -g ipv4.method connection show '" + q + "'"));
        m.put("addresses", umaLinha("nmcli -g ipv4.addresses connection show '" + q + "'"));
        m.put("gateway", umaLinha("nmcli -g ipv4.gateway connection show '" + q + "'"));
        m.put("dns", umaLinha("nmcli -g ipv4.dns connection show '" + q + "'"));
        return m;
    }

    private String umaLinha(String cmd) {
        return runner.saida(cmd).replace("\n", ",").replace("'", "");
    }

    private static String valor(Object o) {
        String s = o == null ? "" : String.valueOf(o).trim();
        return s.equals("") || s.equals("(none)") || s.equals("--") ? "" : s;
    }

    private String codigo(Map<String, Object> antes) {
        try {
            return Integer.toHexString(json.writeValueAsString(antes).hashCode());
        } catch (Exception e) {
            return "sem-codigo";
        }
    }

    public record Snapshot(String token, Map<String, Object> antes, List<String> comandos) {
    }
}
