package com.astral.main.controller;

import com.astral.main.model.DashboardButton;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import java.util.List;

@Controller
public class HomeController {

    @GetMapping("/")
    public String root() {
        return "index"; // Procura src/main/resources/templates/index.html
    }

    @GetMapping("/inicio")
    public String home(Model model) {
        model.addAttribute("buttons", buildButtons());
        model.addAttribute("pageTitle", "ASTRAL PLATFORM");
        return "home"; // Procura src/main/resources/templates/home.html
    }

    private List<DashboardButton> buildButtons() {
        return List.of(
                new DashboardButton("dns", "DNS Management", "dns_management.png", "/dns"),
                new DashboardButton("firewall", "Firewall", "firewall.png", "/firewall"),
                new DashboardButton("proxy", "Proxy System", "proxy_system.png", "/proxy"),
                new DashboardButton("domain", "Domain Controllers", "domain_controllers.png", "/domain"),
                new DashboardButton("postgres", "PostgreSQL Admin", "postgresql_admin.png", "/postgres"),
                new DashboardButton("web", "Web Server Admin", "web_server_admin.png", "/web"),
                new DashboardButton("vm", "Virtual Machines", "virtual_machines.png", "/vm"),
                new DashboardButton("storage", "Storage", "storage.png", "/storage"),
                new DashboardButton("network", "Network Config & VLAN", "network_config_vlan.png", "/network"),
                new DashboardButton("terminal", "Terminal", "terminal.jpeg", "#terminal")
        );
    }
}
```[cite: 8]

        ### 2. AuthFilter.java (Liberando os caminhos estáticos padrão)
```java
package com.astral.main.filter;

import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import java.net.URI;

@Component
public class AuthFilter implements WebFilter {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // Como os assets estão em src/main/resources/static/, eles são servidos diretamente na raiz
        if (path.equals("/") || path.equals("/index.html") || path.startsWith("/api/auth/") ||
                path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/images/") ||
                path.startsWith("/fonts/") || path.equals("/terminal-popup.html")) {
            return chain.filter(exchange);
        }

        HttpCookie tokenCookie = exchange.getRequest().getCookies().getFirst("astral_token");
        if (tokenCookie == null || tokenCookie.getValue().isEmpty()) {
            exchange.getResponse().setStatusCode(HttpStatus.FOUND);
            exchange.getResponse().getHeaders().setLocation(URI.create("/"));
            return exchange.getResponse().setComplete();
        }

        return chain.filter(exchange);
    }
}