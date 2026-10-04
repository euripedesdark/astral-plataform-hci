package com.astral.fabric.proxy;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Endpoint consumido pelo {@code auth_request} do Nginx.
 *
 * <pre>
 * location / {
 *     auth_request /acl-check;
 *     error_page 403 = @acl-deny;
 * }
 * location = /acl-check {
 *     proxy_pass http://127.0.0.1:8082/api/v1/acl/check;
 *     proxy_set_header X-Original-Host $host;
 *     ...
 * }
 * </pre>
 *
 * <p>O contrato e' o do proprio Nginx: <b>2xx deixa passar, 403 nega, 401
 * pede autenticacao</b>. Qualquer outro codigo faria o Nginx devolver 500 na
 * pagina do usuario, entao nunca saira outro. O corpo e' informativo: o
 * subrequest do {@code auth_request} descarta o corpo, mas ele e' o que torna
 * o endpoint testavel so' com curl -- e endpoint de seguranca que nao da para
 * testar sem navegador vira achado no proximo audit.
 */
@RestController
@RequestMapping("/api/v1/acl")
public class AclCheckController {

    private static final Logger log = LoggerFactory.getLogger(AclCheckController.class);

    /**
     * Caminhos publicos: sem eles o Nginx exigiria identidade para CHEGAR ao
     * login, que e' como se trancasse a porta por dentro.
     *
     * <p>A lista espelha o {@code permitAll} do {@link com.astral.main.security.SecurityConfig}
     * de proposito: os dois preciso' concordar sobre o que e' publico. Se
     * divergirem, nao e' furo de seguranca (o Spring Security continua sendo o
     * portao de verdade das APIs) -- e' no maximo um login bloqueado, e essa
     * lista e' a primeira coisa a conferir.
     *
     * <p>Configuravel por {@code astral.acl.public-paths} (prefixos separados
     * por virgula) justamente para nao depender de redeploy quando um caminho
     * novo for publicado.
     */
    private final List<String> publicos;

    private final AclIdentityResolver identidades;
    private final AclEvaluatorService motor;

    public AclCheckController(AclIdentityResolver identidades,
                              AclEvaluatorService motor,
                              @Value("${astral.acl.public-paths:/,/login,/home,/inicio,/app/,/assets/,/images/,/favicon.ico,/api/auth/login,/api/certs/ca/download,/error,/actuator/,/v3/api-docs/,/swagger-ui/}")
                              String publicos) {
        this.identidades = identidades;
        this.motor = motor;
        this.publicos = List.of(publicos.split("\\s*,\\s*"));
    }

    @RequestMapping(value = "/check", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<Map<String, Object>> check(HttpServletRequest req) {
        String host = host(req);
        String uri = uri(req);
        String ip = ip(req);

        // Caminho publico responde ANTES de olhar identidade: nao ha o que
        // avaliar, e devolver 401 aqui impediria o proprio login de abrir.
        if (publico(uri)) {
            return ResponseEntity.ok(Map.of(
                    "status", 200, "action", AclDecision.ALLOW,
                    "host", AclDomain.normaliza(host), "uri", uri == null ? "" : uri,
                    "origem", "PUBLICA", "motivo", "caminho publico (sem ACL)",
                    "ip", ip == null ? "-" : ip));
        }

        Optional<AclIdentity> identidade = identidades.resolve(req);
        AclDecision d = identidade
                .map(i -> motor.avalia(i, host, ip))
                .orElseGet(() -> AclDecision.unauth("IDENTIDADE",
                        "sem Bearer valido e sem sessao do portal", 0));

        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("status", d.status());
        corpo.put("action", d.action());
        corpo.put("host", AclDomain.normaliza(host));
        corpo.put("uri", uri);
        corpo.put("categoria", d.categoria());
        corpo.put("grupo", d.grupo());
        corpo.put("origem", d.origem());
        corpo.put("motivo", d.motivo());
        corpo.put("usuario", identidade.map(AclIdentity::usuario).orElse(null));
        corpo.put("fonte", identidade.map(AclIdentity::source).orElse(null));
        corpo.put("ip", ip);
        corpo.put("ms", d.ms());

        if (d.status() != 200) {
            // Log estruturado em WARN tambem: decisao negada e' o evento que o
            // operador procura primeiro quando "algo foi bloqueado".
            log.info("acl check {} -> {} ({})", AclDomain.normaliza(host), d.status(), d.motivo());
        }
        return ResponseEntity.status(d.status()).body(corpo);
    }

    private String host(HttpServletRequest req) {
        String h = header(req, "X-Original-Host");
        if (h == null || h.isBlank()) h = header(req, "X-Forwarded-Host");
        if (h == null || h.isBlank()) h = req.getHeader("Host");
        // Porta nao faz parte do nome do host para efeito de politica:
        // "exemplo.com:443" e "exemplo.com" sao o mesmo dono.
        if (h != null && h.contains(":")) h = h.substring(0, h.indexOf(':'));
        return h;
    }

    /**
     * Caminho pedido, sem query string. O Nginx manda {@code $request_uri}, que
     * inclui a query; comparar com query transformaria
     * {@code /login?next=x} em caminho desconhecido -- e caminho desconhecido
     * e' exatamente o que nao pode ser publico por acidente.
     */
    private String uri(HttpServletRequest req) {
        String u = header(req, "X-Original-URI");
        if (u == null || u.isBlank()) u = req.getRequestURI();
        if (u == null) return "/";
        int q = u.indexOf('?');
        return q > 0 ? u.substring(0, q) : u;
    }

    /** Prefixo: {@code /app/} cobre tudo dentro, {@code /login} cobre o caminho exato. */
    private boolean publico(String uri) {
        if (uri == null || uri.isBlank()) return false;
        for (String p : publicos) {
            if (p == null || p.isBlank()) continue;
            if (p.equals("/")) {
                // A raiz e' publica; ESTE e' o caso em que um "startsWith("/")"
                // viraria "tudo e' publico" e a ACL viraria decoração. Barra
                // sozinha casa com a barra sozinha, ponto final.
                if (uri.equals("/")) return true;
            } else if (p.endsWith("/")) {
                if (uri.startsWith(p) || uri.equals(p.substring(0, p.length() - 1))) return true;
            } else if (uri.equals(p) || uri.startsWith(p + "/")) {
                return true;
            }
        }
        return false;
    }

    private String header(HttpServletRequest req, String nome) {
        String v = req.getHeader(nome);
        return v == null ? null : v.trim();
    }

    private String ip(HttpServletRequest req) {
        String enc = header(req, "X-Forwarded-For");
        if (enc != null && !enc.isBlank()) {
            int virgula = enc.indexOf(',');
            return (virgula > 0 ? enc.substring(0, virgula) : enc).trim();
        }
        return req.getRemoteAddr();
    }
}
