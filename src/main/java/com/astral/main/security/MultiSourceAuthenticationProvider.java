package com.astral.main.security;
import com.unboundid.ldap.sdk.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 private static final Logger log=LoggerFactory.getLogger(MultiSourceAuthenticationProvider.class);
 @Value("${astral.auth.ad.enabled:true}") boolean adEnabled;
 @Value("${astral.auth.ad.domain:srvcloud.cloud}") String adDomain;
 @Value("${astral.auth.ad.url:ldaps://srvcloud.cloud:636}") String adUrl;
 @Value("${astral.auth.ad.base-dn:DC=srvcloud,DC=cloud}") String baseDn;
 @Value("${astral.auth.ad.admin-group:Domain Admins}") String adminGroup;
 @Value("${astral.auth.ad.use-tls:true}") boolean adTls;
 @Value("${astral.auth.ad.connect-timeout-ms:5000}") int timeout;
 @Value("${astral.auth.postgres.enabled:true}") boolean pgEnabled;
 @Value("${astral.auth.postgres.url:jdbc:postgresql://127.0.0.1:5432/astral}") String pgUrl;
 @Value("${astral.auth.postgres.admin-role:astral_admin}") String pgAdminRole;

 /**
  * SEM fallback silencioso entre fontes.
  *
  * Antes cada fonte envolvia tudo em catch(Exception ignored), entao senha errada no AD
  * levantava LDAPException, o ignored engolia, e a autenticacao caia no Postgres - que
  * valida usuario/senha do proprio banco. Um role do Postgres com o mesmo nome de uma
  * conta do AD entrava com a senha do Postgres, sem passar pelo AD.
  *
  * Agora a distincao e explicita:
  *   - "esta fonte nao conhece este usuario" -> devolve null, tenta a proxima (legitimo)
  *   - "a senha esta errada" ou "a fonte esta fora" -> lanca, e NAO tenta a proxima
  * Ausencia de contexto de confianca e erro, nunca queda silenciosa. (Mesma regra que
  * o BUG 2 do ERP precisa: empresaParaGravar() nulo tem que estourar, nao virar 409 depois.)
  */
 @Override public Authentication authenticate(Authentication input) {
  String u=input.getName(), p=String.valueOf(input.getCredentials());
  if(u==null||u.isBlank()||p.isBlank()) throw new BadCredentialsException("Credenciais vazias");
  if(adEnabled){
   try{ Authentication a=ad(u,p); if(a!=null) return a; log.debug("auth: usuario nao existe no AD, seguindo para a proxima fonte"); }
   // Senha errada: sobe como esta. NAO cai na fonte seguinte.
   catch(BadCredentialsException e){ log.info("auth: senha invalida no AD para o usuario {}",u); throw e; }
   // AD fora do ar, DNS, TLS, timeout: isto e a FONTE quebrada, nao "usuario desconhecido".
   // AuthenticationServiceException e o que o ProviderManager trata como falha de servico,
   // e o que impede a queda silenciosa para a proxima fonte.
   catch(Exception e){ log.error("auth: fonte AD indisponivel ({}: {})",e.getClass().getSimpleName(),e.getMessage()); throw new AuthenticationServiceException("Fonte AD indisponível",e); }
  }
  if(pgEnabled){
   try{ Authentication a=postgres(u,p); if(a!=null) return a; log.debug("auth: usuario nao existe no Postgres, nenhuma fonte serviu"); }
   catch(BadCredentialsException e){ throw e; }
   catch(Exception e){ log.error("auth: fonte Postgres indisponivel ({}: {})",e.getClass().getSimpleName(),e.getMessage()); throw new AuthenticationServiceException("Fonte Postgres indisponível",e); }
  }
  log.info("auth: recusada, usuario nao autenticado em nenhuma fonte");
  throw new BadCredentialsException("Credenciais inválidas");
 }

 private Authentication ad(String u,String p)throws Exception{
  // O nome vem do corpo do request e entra num bind DN/UPN. Sem esta validacao, "u" com
  // virgula ou igual mudaria o DN e o bind apontaria para outra entrada do diretorio.
  if(u.length()>256||!u.matches("[A-Za-z0-9._@\\\\-]+"))return null;
  String noScheme=adUrl.replaceFirst("^[a-zA-Z]+://","");
  String[] hp=noScheme.split(":",2); String host=hp[0];
  int port=hp.length==2?Integer.parseInt(hp[1]):(adTls?636:389);
  LDAPConnection c=adTls?new LDAPConnection((SSLSocketFactory)SSLSocketFactory.getDefault(),host,port):new LDAPConnection(host,port);
  try{
   c.getConnectionOptions().setConnectTimeoutMillis(timeout);
   // Bind PRIMEIRO, depois busca. A ordem inversa nao funciona: o Samba AD nao permite
   // busca anonima ("Operation unavailable without authentication"), e foi assim que a
   // busca-antes-do-bind quebrou o login. E a busca precisa do bind para ler o memberOf.
   String bindDn=u.contains("@")?u:u+"@"+adDomain;
   try{ c.bind(bindDn,p); }
   catch(LDAPException le){
    ResultCode rc=le.getResultCode();
    // 49 invalidCredentials: o AD responde 49 tanto para "nao existe" quanto para "senha
    // errada" (de proposito, para nao enumerar usuarios). Nao da para distinguir, entao o
    // certo e NAO cair na fonte seguinte: se Presented uma identidade no formato do AD e
    // ela falhou, o erro e do usuario. Caiu aqui antes, e o Postgres validava com a senha
    // dele - um role com o mesmo nome de uma conta do AD entrava sem passar pelo AD.
    if(rc==ResultCode.INVALID_CREDENTIALS) throw new BadCredentialsException("Senha inválida");
    // 32 noSuchObject / 34 invalidDNSyntax: esse nome nao e uma identidade do AD.
    if(rc==ResultCode.NO_SUCH_OBJECT||rc==ResultCode.INVALID_DN_SYNTAX) return null;
    throw le;
   }
   // A busca agora roda autenticada. Se o login funcionou mas nao ha entrada de user,
   // e um login do Samba que nao corresponde a um objeto de usuario.
   String sam=u.contains("@")?u.substring(0,u.indexOf('@')):u;
   SearchResult sr=c.search(baseDn,SearchScope.SUB,"(&(objectClass=user)(sAMAccountName="+escapeFilter(sam)+"))","memberOf");
   Set<GrantedAuthority> roles=new HashSet<>();
   roles.add(new SimpleGrantedAuthority("ROLE_ASTRAL_USER"));
   if(sr.getEntryCount()>0){
    for(String g:sr.getSearchEntries().get(0).getAttributeValues("memberOf"))
     if(g.toLowerCase(Locale.ROOT).contains("cn="+adminGroup.toLowerCase(Locale.ROOT)+",")) roles.add(new SimpleGrantedAuthority("ROLE_ASTRAL_ADMIN"));
   }
   return token(u,"AD",roles);
  }finally{ c.close(); }
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
  }catch(SQLException e){
   // 28P01/28000 = role inexistente ou senha errada => nao e desta fonte, segue e falha no fim.
   // Qualquer outro SQLState e infraestrutura (banco fora, socket, timeout) e NAO pode virar
   // "sem credencial": subiria falso negativo em vez de erro.
   String st=e.getSQLState();
   if("28P01".equals(st)||"28000".equals(st))return null;
   throw e;
  }
 }

 private Authentication token(String u,String source,Collection<? extends GrantedAuthority> roles){
  return new UsernamePasswordAuthenticationToken(new AstralPrincipal(u,source,roles),null,roles);
 }
 private static String escapeFilter(String s){return s.replace("\\","\\5c").replace("*","\\2a").replace("(","\\28").replace(")","\\29").replace("\0","\\00");}
 @Override public boolean supports(Class<?> c){return UsernamePasswordAuthenticationToken.class.isAssignableFrom(c);}
}
