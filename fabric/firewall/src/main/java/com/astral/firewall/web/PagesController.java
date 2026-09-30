package com.astral.firewall.web;
import org.springframework.stereotype.Controller; import org.springframework.web.bind.annotation.GetMapping;
@Controller public class PagesController { @GetMapping({"/", "/firewall", "/firewall/"}) public String page(){ return "firewall"; } }
