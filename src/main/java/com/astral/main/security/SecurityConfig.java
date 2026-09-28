package com.astral.main.security;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.*;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

@Configuration
public class SecurityConfig {
 @Bean AuthenticationManager authenticationManager(MultiSourceAuthenticationProvider provider){return new ProviderManager(provider);}
 @Bean SecurityFilterChain securityFilterChain(HttpSecurity http)throws Exception{
  http.csrf(c->c.disable())
   .securityContext(c->c.securityContextRepository(new HttpSessionSecurityContextRepository()))
   .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
   .authorizeHttpRequests(a->a
    .requestMatchers("/app/**","/assets/**","/favicon.ico","/api/auth/login","/error").permitAll()
    .requestMatchers("/actuator/**").permitAll()
    .anyRequest().authenticated())
   .exceptionHandling(e->e.authenticationEntryPoint((req,res,ex)->res.sendError(401)));
  return http.build();
 }
}
