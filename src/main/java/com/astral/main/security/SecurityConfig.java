package com.astral.main.security;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.*;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
 @Bean AuthenticationManager authenticationManager(MultiSourceAuthenticationProvider provider){return new ProviderManager(provider);}
 @Bean SecurityFilterChain securityFilterChain(HttpSecurity http)throws Exception{
  http.csrf(c->c.disable())
   .securityContext(c->c.securityContextRepository(new HttpSessionSecurityContextRepository()))
   .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
   .authorizeHttpRequests(a->a
    // A "/" entra porque a raiz e a casca do mesmo SPA que ja e publico em /app/**.
    // Sem ela, a unica pagina de entrada do Astral devolvia 401 e nao havia caminho
    // funcional para a tela. /login, /home e /inicio sao os outros apelidos do mesmo
    // HomeController e tinham o mesmo problema. A API continua protegida por
    // anyRequest().authenticated().
    .requestMatchers("/","/login","/home","/inicio","/app/**","/assets/**","/favicon.ico","/api/auth/login","/api/certs/ca/download","/error").permitAll()
    .requestMatchers("/actuator/**").permitAll()
    .anyRequest().authenticated())
   .exceptionHandling(e->e.authenticationEntryPoint((req,res,ex)->res.sendError(401)));
  return http.build();
 }
}
