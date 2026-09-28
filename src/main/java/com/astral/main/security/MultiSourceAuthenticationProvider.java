package com.astral.main.security;
import com.unboundid.ldap.sdk.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.*;
import org.springframework.security.core.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import java.sql.*;
import java.util.*;
import javax.net.ssl.SSLSocketFactory;

@Component
public class MultiSourceAuthenticationProvider implements AuthenticationProvider {
 @Value("${astral.auth.ad.enabled:true}") boolean adEnabled;
 @Value("${astral.auth.ad.domain:srvcloud.cloud}") String adDomain;
 @Value("${astral.auth.ad.url:ldap://srvcloud.cloud:389}") String adUrl;
 @Value("${astral.auth.ad.base-dn:DC=srvcloud,DC=cloud}") String baseDn;
 @Value("${astral.auth.ad.admin-group:Domain Admins}") String adminGroup;
 @Value("${astral.auth.ad.use-tls:false}") boolean adTls;
 @Value("${astral.auth.ad.connect-timeout-ms:5000}") int timeout;
 @Value("${astral.auth.postgres.enabled:true}") boolean pgEnabled;
 @Value("${astral.auth.postgres.url:jdbc:postgresql://127.0.0.1:5432/astral}") String pgUrl;
 @Value("${astral.auth.postgres.admin-role:astral_admin}") String pgAdminRole;

 @Override public Authentication authenticate(Authentication input) {
  String u=input.getName(), p=String.valueOf(input.getCredentials());
  if(u==null||u.isBlank()||p.isBlank()) throw new BadCredentialsException("Credenciais vazias");
  if(adEnabled){try{Authentication a=ad(u,p);if(a!=null)return a;}catch(Exception ignored){}}
  if(pgEnabled){try{Authentication a=postgres(u,p);if(a!=null)return a;}catch(Exception ignored){}}
  throw new BadCredentialsException("Credenciais inválidas");
 }
 private Authentication ad(String u,String p)throws Exception{
  String noScheme=adUrl.replaceFirst("^[a-zA-Z]+://","");
  String[] hp=noScheme.split(":",2); String host=hp[0];
  int port=hp.length==2?Integer.parseInt(hp[1]):(adTls?636:389);
  LDAPConnection c=adTls?new LDAPConnection((SSLSocketFactory)SSLSocketFactory.getDefault(),host,port):new LDAPConnection(host,port);
  c.getConnectionOptions().setConnectTimeoutMillis(timeout);
  c.bind(u.contains("@")?u:u+"@"+adDomain,p);
  String sam=u.contains("@")?u.substring(0,u.indexOf('@')):u;
  SearchResult sr=c.search(baseDn,SearchScope.SUB,"(&(objectClass=user)(sAMAccountName="+escapeFilter(sam)+"))","sAMAccountName","memberOf");
  if(sr.getEntryCount()==0){c.close();return null;}
  Set<GrantedAuthority> roles=new HashSet<>();
  roles.add(new SimpleGrantedAuthority("ROLE_ASTRAL_USER"));
  SearchResultEntry e=sr.getSearchEntries().get(0);
  for(String g:e.getAttributeValues("memberOf"))
   if(g.toLowerCase(Locale.ROOT).contains("cn="+adminGroup.toLowerCase(Locale.ROOT)+",")) roles.add(new SimpleGrantedAuthority("ROLE_ASTRAL_ADMIN"));
  c.close(); return token(u,"AD",roles);
 }
 private Authentication postgres(String u,String p)throws Exception{
  try(Connection c=DriverManager.getConnection(pgUrl,u,p);
      PreparedStatement s=c.prepareStatement("select pg_has_role(current_user, ?, 'USAGE')")){
   s.setString(1,pgAdminRole);
   try(ResultSet rs=s.executeQuery()){
    if(!rs.next())return null;
    Set<GrantedAuthority> roles=new HashSet<>();
    roles.add(new SimpleGrantedAuthority("ROLE_ASTRAL_USER"));
    if(rs.getBoolean(1))roles.add(new SimpleGrantedAuthority("ROLE_ASTRAL_ADMIN"));
    return token(u,"POSTGRES",roles);
   }
  }
 }
 private Authentication token(String u,String source,Collection<? extends GrantedAuthority> roles){
  return new UsernamePasswordAuthenticationToken(new AstralPrincipal(u,source,roles),null,roles);
 }
 private static String escapeFilter(String s){return s.replace("\\","\\5c").replace("*","\\2a").replace("(","\\28").replace(")","\\29").replace("\0","\\00");}
 @Override public boolean supports(Class<?> c){return UsernamePasswordAuthenticationToken.class.isAssignableFrom(c);}
}
