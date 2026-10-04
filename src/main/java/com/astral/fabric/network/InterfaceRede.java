package com.astral.fabric.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Uma interface configurada no formulario de enderecamento.
 *
 * <p>{@code enderecoCidr} e' o endereco COM mascara (192.168.2.10/24) porque e'
 * assim que o NetworkManager guarda e assim que o kernel entende. Guardar IP e
 * mascara em campos separados duplica a fonte de verdade e abre espaco para os
 * dois divergirem.
 */
public record InterfaceRede(
        String nome,
        String papel,
        String enderecoCidr,
        String gateway,
        boolean servidorDhcp,
        String dhcpInicio,
        String dhcpFim) {

    public static final String PAPEL_WAN = "WAN";
    public static final String PAPEL_LAN = "LAN";

    /** Interface valida: letras, digitos, ponto, hifen e o '.' do VLAN (ens224.10). */
    private static final Pattern IFACE = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,14}$");

    public boolean wan() {
        return PAPEL_WAN.equalsIgnoreCase(papel);
    }

    public List<String> validar() {
        List<String> erros = new ArrayList<>();
        if (nome == null || !IFACE.matcher(nome).matches()) {
            erros.add("nome de interface invalido: " + nome);
            return erros;
        }
        if (nome.equalsIgnoreCase("lo")) {
            erros.add("a interface de loopback nao pode ser configurada");
        }
        if (papel == null || !(wan() || PAPEL_LAN.equalsIgnoreCase(papel))) {
            erros.add("papel invalido em " + nome + " (esperado WAN ou LAN)");
        }

        EnderecamentoRede.IpCidr faixa = EnderecamentoRede.ipv4(enderecoCidr);
        if (faixa == null) {
            erros.add("endereco de " + nome + " precisa ser IPv4 com mascara CIDR (ex: 192.168.2.10/24)");
        } else {
            if (faixa.eBroadcast()) {
                erros.add("o endereco de " + nome + " e' o broadcast da rede " + faixa.redeCidr());
            }
            if (faixa.eRedePura()) {
                erros.add("o endereco de " + nome + " e' a identidade da rede (rede pura)");
            }
            if (gateway != null && !gateway.isBlank()) {
                EnderecamentoRede.IpCidr gw = EnderecamentoRede.ipv4(gateway);
                if (gw == null) {
                    erros.add("gateway de " + nome + " nao e' IPv4 valido: " + gateway);
                } else if (!faixa.contem(gateway)) {
                    erros.add("gateway " + gateway + " esta fora da rede " + faixa.redeCidr() + " de " + nome);
                }
            } else if (wan()) {
                erros.add("interface WAN " + nome + " precisa de gateway");
            }
        }

        if (servidorDhcp) {
            if (wan()) erros.add("WAN " + nome + " nao pode servir DHCP");
            EnderecamentoRede.IpCidr ini = EnderecamentoRede.ipv4(dhcpInicio);
            EnderecamentoRede.IpCidr fim = EnderecamentoRede.ipv4(dhcpFim);
            if (ini == null) erros.add("inicio do intervalo DHCP invalido em " + nome);
            if (fim == null) erros.add("fim do intervalo DHCP invalido em " + nome);
            if (ini != null && fim != null) {
                if (Integer.compareUnsigned(ini.endereco(), fim.endereco()) > 0) {
                    erros.add("intervalo DHCP de " + nome + " invertido (" + dhcpInicio + " > " + dhcpFim + ")");
                }
                if (faixa != null && (!faixa.contem(dhcpInicio) || !faixa.contem(dhcpFim))) {
                    erros.add("intervalo DHCP de " + nome + " fora da rede " + faixa.redeCidr());
                }
            }
        }
        return erros;
    }

    public String chave() {
        return nome == null ? "" : nome.toLowerCase(Locale.ROOT);
    }
}
