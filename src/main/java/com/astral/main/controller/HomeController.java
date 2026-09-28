package com.astral.main.controller;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
@Controller
public class HomeController {
 @GetMapping({"/","/login","/home","/inicio"})
 public String app(){return "redirect:/app/";}
}
