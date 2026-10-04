package com.astral.fabric.proxy;

import com.astral.main.security.AstralPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Resolve a identidade do pedido do {@code auth_request}.
 *
 * <p>Duas fontes, nessa ordem, cada uma com motivo proprio:
 *
 * <ol>
 *   <li><b>Bearer JWT</b> -- o contrato do diagrama: o Nginx repassa o
 *       {@code Authorization} do navegador e o motor verifica o HS256 com o
 *       segredo compartilhado com o BrasilCloud Auth Service. A verificacao e'
 *       local de proposito: pedir ao Auth Service para confirmar cada
 *       requisicao trocaria um motor de 2ms por uma chamada de rede por
 *       pagina, e o Auth Service fora de ar passaria a decidir navegacao.</li>
 *   <li><b>Sessao Astral</b> -- quem ja' logou no portal. E' o caminho que
 *       funciona HOJE, porque o Auth Service de :8181 ainda e' stateless com
 *       HTTP Basic e nao emite token (docs/GUIA-INTEGRACAO-AUTH-SERVICE.md,
 *       secao 28 -- "nao deve ser documentado como JWT"). Sem este fallback o
 *       motor so' funcionaria depois que o Auth Service ganhar emissao de
 *       token; com ele, funciona desde ja'.</li>
 * </ol>
 *
 * <p>"Fallback sobre dependencia" e' exatamente isto: nenhuma das duas fontes e'
 * obrigatoria isoladamente, e a recusa acontece so' quando as duas falham.
 */
@Component
public class AclIdentityResolver {

    private static final Logger log = LoggerFactory.getLogger(AclIdentityResolver.class);

    private final JwtDecoder decoder;

    public AclIdentityResolver(
            @Value("${astral.auth.jwt.enabled:false}") boolean tokenHabilitado,
            @Value("${astral.auth.jwt.secret:}") String segredo) {
        NimbusJwtDecoder c = null;
        boolean chaveForte = segredo != null && segredo.getBytes(StandardCharsets.UTF_8).length >= 32;
        if (tokenHabilitado && segredo != null && !segredo.isBlank()) {
            if (!chaveForte) {
                // HS256 com menos de 256 bits e' forca bruta no cafe da manha.
                // Preferimos nao validar a nada a validar mal.
                log.error("acl: astral.auth.jwt.secret com menos de 32 bytes; verificacao de Bearer DESLIGADA ate corrigir");
            } else {
                c = NimbusJwtDecoder.withSecretKey(
                                new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                        .macAlgorithm(MacAlgorithm.HS256)
                        .build();
                // JwtTimestampValidator ja' valida exp/iat; o que muda aqui e'
                // a tolerancia de relogio: 60s cobre NTP normal sem aceitar um
                // token que ja' venceu ha um minuto.
                c.setJwtValidator(new JwtTimestampValidator(Duration.ofSeconds(60)));
            }
        }
        this.decoder = c;
        if (decoder == null && tokenHabilitado) {
            log.warn("acl: Bearer JWT desligado; identidade vem da sessao do portal");
        }
    }

    /** {@code empty} = nao ha quem avaliar; o chamador responde 401. */
    public Optional<AclIdentity> resolve(HttpServletRequest req) {
        Optional<AclIdentity> token = viaBearer(req);
        if (token.isPresent()) return token;
        return viaSessao();
    }

    private Optional<AclIdentity> viaBearer(HttpServletRequest req) {
        if (decoder == null || req == null) return Optional.empty();
        String h = req.getHeader("Authorization");
        if (h == null || !h.regionMatches(true, 0, "Bearer ", 0, 7)) return Optional.empty();
        String bruto = h.substring(7).trim();
        if (bruto.isEmpty()) return Optional.empty();
        try {
            Jwt jwt = decoder.decode(bruto);
            String sub = jwt.getSubject();
            if (sub == null || sub.isBlank()) return Optional.empty();
            return Optional.of(new AclIdentity(sub, grupos(jwt), "JWT"));
        } catch (Exception e) {
            // Token expirado, assinatura errada ou segredo divergente: nao e'
            // erro do motor, e' credencial que nao passou. Cai para a sessao.
            log.debug("acl: Bearer recusado ({})", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Grupos do token. Aceita os tres nomes comuns de claim porque nao sabemos
     * qual o Auth Service vai escolher quando emitir; aceitar tres custa uma
     * lista e evita uma incompatibilidade silenciosa em producao.
     */
    private List<String> grupos(Jwt jwt) {
        for (String nome : new String[]{"groups", "roles", "memberOf"}) {
            List<String> l = texto(jwt.getClaim(nome));
            if (!l.isEmpty()) return l;
        }
        // Keycloak/compat: realm_access.roles
        Object realm = jwt.getClaim("realm_access");
        if (realm instanceof java.util.Map<?, ?> m) {
            List<String> l = texto(m.get("roles"));
            if (!l.isEmpty()) return l;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<String> texto(Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof List<?> l) {
            List<String> out = new ArrayList<>();
            for (Object o : l) if (o != null) out.add(String.valueOf(o));
            return out;
        }
        if (raw instanceof String s) {
            if (s.isBlank()) return List.of();
            return List.of(s.split("\\s*,\\s*"));
        }
        return List.of();
    }

    private Optional<AclIdentity> viaSessao() {
        Authentication auth;
        try {
            auth = SecurityContextHolder.getContext().getAuthentication();
        } catch (Exception e) {
            return Optional.empty();
        }
        if (auth == null || !auth.isAuthenticated()) return Optional.empty();
        String nome = auth.getName();
        if (nome == null || nome.isBlank() || "anonymousUser".equals(nome)) return Optional.empty();

        if (auth.getPrincipal() instanceof AstralPrincipal ap) {
            return Optional.of(new AclIdentity(ap.getUsername(), ap.getAdGroups(), "SESSAO/" + ap.getSource()));
        }
        // Fonte que nao carrega grupo (POSTGRES/LINUX): identidade vale, grupo
        // nao. A regra com curinga '*' e' o que cobre esse caso.
        List<String> grupos = auth.getAuthorities().stream()
                .map(a -> a.getAuthority().replaceFirst("^ROLE_", ""))
                .filter(g -> !g.equals("USER") && !g.equals("ADMIN"))
                .map(g -> g.toLowerCase(Locale.ROOT))
                .toList();
        return Optional.of(new AclIdentity(nome, grupos, "SESSAO"));
    }
}
