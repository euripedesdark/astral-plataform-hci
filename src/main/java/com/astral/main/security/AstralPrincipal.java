package com.astral.main.security;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;
import java.util.Collection;
public class AstralPrincipal extends User {
 private final String source;
 public AstralPrincipal(String username,String source,Collection<? extends GrantedAuthority> authorities){
  super(username,"",true,true,true,true,authorities); this.source=source;
 }
 public String getSource(){return source;}
}
