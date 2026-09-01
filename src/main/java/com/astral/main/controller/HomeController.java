package com.astral.main.controller;

import com.astral.main.model.DashboardButton;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * Renderiza o dashboard principal exibido apos o login.[cite: 10]
 * Cada botao corresponde a uma rota real do Nginx (astral.conf).[cite: 10]
 *
 * Para adicionar/remover/reordenar um card, mexa apenas em buildButtons();[cite: 10]
 * o template (home.html) so itera sobre essa lista.[cite: 10]
 */
@Controller
public class HomeController {

    @GetMapping("/")
    public String root() {
        return "redirect:/login/index.html";
    }

    @GetMapping("/inicio")
    public String home(Model model) {
        model.addAttribute("buttons", buildButtons());
        model.addAttribute("pageTitle", "ASTRAL PLATFORM");
        return "home";
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