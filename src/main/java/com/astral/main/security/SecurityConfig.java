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
    // HomeController e tinham o mesmo problema. /images/** entra pela mesma
    // razao: o fundo da tela de login e as capas dos modulos sao estaticos, e
    // bloquear imagem para quem ainda nao logou e' a porta trancada por dentro
    // outra vez. ESTA lista e a astral.acl.public-paths tem que concordar --
    // uma e' o portao do Spring, a outra e' o que o motor de ACL pergunta.
    // A API continua protegida por anyRequest().authenticated().
    .requestMatchers("/","/login","/home","/inicio","/app/**","/assets/**","/images/**","/favicon.ico","/api/auth/login","/api/certs/ca/download","/error").permitAll()
    .requestMatchers("/actuator/**").permitAll()
    // O auth_request do Nginx chega aqui sem sessao: o subrequest nao carrega
    // cookie nenhum. Quem responde e o proprio motor, que recusa com 401 quando
    // nao ha identidade -- nao ha fail-open em liberar a rota, ha exatamente o
    // contrario: sem esta permitAll o Nginx devolveria 401 para TODO mundo.
    // O CRUD em /api/v1/acl/{categories,domains,overrides,rules} continua
    // em anyRequest().authenticated() + @PreAuthorize hasRole('ASTRAL_ADMIN').
    .requestMatchers("/api/v1/acl/check").permitAll()
    // Documentacao OpenAPI: o front consome /v3/api-docs para montar forms, e
    // Swagger aberto nao expoe dado -- expoe contrato. Proteger isso com login
    // faria o build do front depender de sessao.
    .requestMatchers("/v3/api-docs/**","/swagger-ui/**","/swagger-ui.html").permitAll()
    .anyRequest().authenticated())
   .exceptionHandling(e->e.authenticationEntryPoint((req,res,ex)->res.sendError(401)));
  return http.build();
 }
}
