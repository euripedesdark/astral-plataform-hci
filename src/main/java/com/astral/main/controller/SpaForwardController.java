package com.astral.main.controller;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaForwardController {
 // "/" NAO e mapeado aqui de proposito: o HomeController ja faz redirect de "/" para
 // "/app/". Mapear "/" aqui tambem dava "Ambiguous handler methods mapped for '/'"
 // e a raiz passava a responder 500.
 //
 // O que faltava era o "/app/", porque o Spring nao resolve indice de diretorio: ele
 // so serve /app/index.html. A cadeia de entrada era "/" -> 302 -> "/app/" -> 404, e
 // como "/" nao estava no permitAll, nem arrives nela sem estar autenticado. Este
 // controller fecha o buraco sem depender do meta-refresh do index.html da raiz.
 @GetMapping({"/app","/app/"})
 public String index(){ return "forward:/app/index.html"; }
}
