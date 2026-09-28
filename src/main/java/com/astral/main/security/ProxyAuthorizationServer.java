package com.astral.main.security;
import com.sun.net.httpserver.*;
import jakarta.annotation.*;
import org.springframework.security.authentication.*;
import org.springframework.stereotype.Component;
import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;import java.util.Base64;

@Component
public class ProxyAuthorizationServer {
 private final AuthenticationManager manager; private HttpServer server;
 public ProxyAuthorizationServer(AuthenticationManager manager){this.manager=manager;}
 @PostConstruct public void start()throws IOException{
  int port=Integer.parseInt(System.getenv().getOrDefault("ASTRAL_PROXY_AUTH_PORT","8091"));
  server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),port),32);
  server.createContext("/",this::handle);
  server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());server.start();
 }
 private void handle(HttpExchange ex)throws IOException{
  String h=ex.getRequestHeaders().getFirst("Proxy-Authorization");
  if(h==null)h=ex.getRequestHeaders().getFirst("Authorization");
  if(h==null||!h.startsWith("Basic ")){ex.getResponseHeaders().set("WWW-Authenticate","Basic realm=\\"Astral Proxy\\"");ex.sendResponseHeaders(401,-1);return;}
  try{
   String raw=new String(Base64.getDecoder().decode(h.substring(6)),StandardCharsets.UTF_8);int i=raw.indexOf(':');if(i<1)throw new BadCredentialsException("bad");
   manager.authenticate(new UsernamePasswordAuthenticationToken(raw.substring(0,i),raw.substring(i+1)));
   ex.sendResponseHeaders(200,-1);
  }catch(Exception e){ex.sendResponseHeaders(403,-1);}
 }
 @PreDestroy public void stop(){if(server!=null)server.stop(1);}
}
