package com.astral.fabric.network;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Enderecamento de rede declarativo: o que o operador QUER, nao o que o
 * sistema tem.
 *
 * <p>E' o contrato do formulario da UI e do payload da intencao de reconciliacao
 * ao mesmo tempo. Uma unica forma de descrever a rede para os dois caminhos e'
 * a garantia de que a tela e o motor estao falando da mesma coisa.
 *
 * <p>A validacao e' pura e sem side-effect: roda ANTES de qualquer comando,
 * que e' o passo de "Validação" do ciclo declarativo. Reprovar aqui custa
 * zero; reprovar dentro de um {@code nmcli con up} custa a conectividade.
 */
public record EnderecamentoRede(
        String metodo,
        List<InterfaceRede> interfaces,
        List<String> dns,
        String dominioDhcp,
        boolean protegerResolvConf) {

    public static final Pattern IPV4 = Pattern.compile(
            "^(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)$");

    public List<String> validar() {
        List<String> erros = new ArrayList<>();
        if (metodo == null || !(metodo.equals("manual") || metodo.equals("auto"))) {
            erros.add("metodo precisa ser 'manual' ou 'auto'");
        }
        if (interfaces == null || interfaces.isEmpty()) {
            erros.add("ao menos uma interface precisa ser configurada");
            return erros;
        }

        Set<String> vistos = new LinkedHashSet<>();
        List<String> redes = new ArrayList<>();
        int wans = 0;
        for (InterfaceRede i : interfaces) {
            erros.addAll(i.validar());
            if (i.wan()) wans++;
            if (!vistos.add(i.chave())) {
                erros.add("interface duplicada: " + i.nome());
            }
            IpCidr c = ipv4(i.enderecoCidr());
            if (c == null) continue;
            if (redes.contains(c.redeCidr())) {
                // Sobreposicao de sub-rede: quase sempre erro de digitacao, e o
                // sintoma so' aparece em producao como rota ambigua.
                erros.add("rede " + c.redeCidr() + " usada por mais de uma interface");
            }
            redes.add(c.redeCidr());
        }
        if (wans > 1) erros.add("mais de uma interface marcada como WAN");

        if (dns == null || dns.isEmpty()) {
            erros.add("ao menos um servidor DNS e' obrigatorio");
        } else {
            for (String d : dns) {
                if (d == null || !IPV4.matcher(d.trim()).matches()) {
                    erros.add("DNS invalido: " + d);
                }
            }
            if (dns.size() > 3) erros.add("no maximo 3 servidores DNS");
        }

        if (dominioDhcp != null && !dominioDhcp.isBlank()
                && !dominioDhcp.matches("^[a-zA-Z0-9]([a-zA-Z0-9.-]{0,252}[a-zA-Z0-9])?$")) {
            erros.add("dominio DHCP invalido: " + dominioDhcp);
        }
        return erros;
    }

    public boolean valida() {
        return validar().isEmpty();
    }

    /** Converte "1.2.3.4/24" (ou so' o IP, /32 implicito). {@code null} se invalido. */
    public static IpCidr ipv4(String valor) {
        if (valor == null) return null;
        String v = valor.trim();
        int barra = v.indexOf('/');
        String ip = barra < 0 ? v : v.substring(0, barra);
        int mask = 32;
        if (barra >= 0) {
            try {
                mask = Integer.parseInt(v.substring(barra + 1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (mask < 0 || mask > 32 || !IPV4.matcher(ip).matches()) return null;
        long bruto = 0;
        for (String p : ip.split("\\.")) {
            bruto = (bruto << 8) | Integer.parseInt(p);
        }
        int addr = (int) bruto;
        int rede = mask == 0 ? 0 : (addr & (0xffffffff << (32 - mask)));
        int broadcast = rede | ~redeComMask(mask);
        return new IpCidr(ip, mask, addr, rede, broadcast);
    }

    private static int redeComMask(int mask) {
        return mask == 0 ? 0 : (0xffffffff << (32 - mask));
    }

    /**
     * IP + mascara + a rede derivada, tudo em int sem sinal logico.
     *
     * <p>Aritmetica inteira de proposito: {@code InetAddress} nao expoe mascara,
     * nao calcula broadcast e nao tem "contem". Fazer isso com objetos de rede
     * virava reflexao ou string-split repetido em cinco lugares.
     */
    public record IpCidr(String ip, int mask, int endereco, int rede, int broadcast) {

        public String redeCidr() {
            return texto(rede) + "/" + mask;
        }

        public String broadcastTexto() {
            return texto(broadcast);
        }

        /** O endereco e' a identidade de rede (192.168.2.0/24)? */
        public boolean eRedePura() {
            return endereco == rede && mask < 31;
        }

        public boolean eBroadcast() {
            return endereco == broadcast && mask < 31;
        }

        /** Contem o IP (sem ou com mascara) passado? */
        public boolean contem(String outro) {
            IpCidr o = ipv4(outro);
            if (o == null) return false;
            return (o.endereco & redeComMask(mask)) == rede;
        }

        /** Deltas para o plano de DHCP: .100 a .199 como nos scripts legados. */
        public String ipNaFaixa(int deslocamento) {
            long alvo = (rede & redeComMask(mask)) | (deslocamento & ~redeComMask(mask));
            return texto((int) alvo);
        }

        private static String texto(int addr) {
            return ((addr >>> 24) & 0xff) + "." + ((addr >>> 16) & 0xff)
                    + "." + ((addr >>> 8) & 0xff) + (addr & 0xff);
        }
    }
}
