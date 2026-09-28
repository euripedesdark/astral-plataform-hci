package com.astral.main.controller;
import jakarta.servlet.http.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.*;
import org.springframework.security.core.*;
import org.springframework.security.core.context.*;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class LoginController {
 private final AuthenticationManager manager;
 private final HttpSessionSecurityContextRepository repository=new HttpSessionSecurityContextRepository();
 public LoginController(AuthenticationManager manager){this.manager=manager;}
 @PostMapping("/login")
 public ResponseEntity<?> login(@RequestBody LoginRequest body,HttpServletRequest req,HttpServletResponse res){
  try{
   Authentication auth=manager.authenticate(new UsernamePasswordAuthenticationToken(body.username(),body.password()));
   req.changeSessionId();
   SecurityContext ctx=SecurityContextHolder.createEmptyContext();ctx.setAuthentication(auth);SecurityContextHolder.setContext(ctx);repository.saveContext(ctx,req,res);
   return ResponseEntity.ok(Map.of("authenticated",true,"username",auth.getName(),"authorities",auth.getAuthorities()));
  }catch(AuthenticationException e){return ResponseEntity.status(401).body(Map.of("authenticated",false,"message","Usuário ou senha inválidos."));}
 }
 @GetMapping("/me")
 public ResponseEntity<?> me(Authentication auth){
  if(auth==null||!auth.isAuthenticated())return ResponseEntity.status(401).build();
  return ResponseEntity.ok(Map.of("authenticated",true,"username",auth.getName(),"authorities",auth.getAuthorities()));
 }
 @PostMapping("/logout")
 public ResponseEntity<?> logout(HttpServletRequest req){
  SecurityContextHolder.clearContext();HttpSession s=req.getSession(false);if(s!=null)s.invalidate();
  return ResponseEntity.ok(Map.of("authenticated",false));
 }
 public record LoginRequest(String username,String password){}
}
