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
   // getSession(true) antes: changeSessionId() exige uma sessao ja existente e lancava
   // IllegalStateException em cliente sem cookie, ou seja, no primeiro login de qualquer
   // navegador novo -> 500 em vez de 200. A intentao (rotacionar o ID no login, contra
   // session fixation) continua valida; so faltava garantir a sessao.
   req.getSession(true);req.changeSessionId();
   SecurityContext ctx=SecurityContextHolder.createEmptyContext();ctx.setAuthentication(auth);SecurityContextHolder.setContext(ctx);repository.saveContext(ctx,req,res);
   String src=(auth.getPrincipal() instanceof com.astral.main.security.AstralPrincipal ap)?ap.getSource():"?";
   return ResponseEntity.ok(Map.of("authenticated",true,"username",auth.getName(),"source",src,"authorities",auth.getAuthorities()));
  // Fonte de autenticacao fora do ar (AD sem rede, CA do LDAP nao confia, banco fora):
  // antes caia no catch de baixo e respondia "Usuário ou senha inválidos.", o que e
  // mentira - a senha pode estar certa. Alem disso escondia a causa real no journal e
  // levava alguem a depurar credencial quando o problema era o servico. 503 e o correto:
  // o cliente sabe que pode tentar de novo depois.
  }catch(AuthenticationServiceException e){
   return ResponseEntity.status(503).body(Map.of("authenticated",false,"message","Autenticação indisponível no momento. Tente novamente."));
  }catch(AuthenticationException e){
   return ResponseEntity.status(401).body(Map.of("authenticated",false,"message","Usuário ou senha inválidos."));
  }
 }
 @GetMapping("/me")
 public ResponseEntity<?> me(Authentication auth){
  if(auth==null||!auth.isAuthenticated())return ResponseEntity.status(401).build();
  String src=(auth.getPrincipal() instanceof com.astral.main.security.AstralPrincipal ap)?ap.getSource():"?";
  return ResponseEntity.ok(Map.of("authenticated",true,"username",auth.getName(),"source",src,"authorities",auth.getAuthorities()));
 }
 @PostMapping("/logout")
 public ResponseEntity<?> logout(HttpServletRequest req){
  SecurityContextHolder.clearContext();HttpSession s=req.getSession(false);if(s!=null)s.invalidate();
  return ResponseEntity.ok(Map.of("authenticated",false));
 }
 public record LoginRequest(String username,String password){}
}
