package com.astral.main.controller;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.io.IOException;

@RestController
@RequestMapping("/api/firewall")
public class FirewallProxyController {
 private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
 private final String base;
 public FirewallProxyController(@Value("${astral.firewall.url:http://127.0.0.1:8040}")String base){this.base=base;}
 @RequestMapping(value="/**",method={RequestMethod.GET,RequestMethod.POST,RequestMethod.PUT,RequestMethod.DELETE})
 public ResponseEntity<byte[]> proxy(HttpServletRequest req,@RequestBody(required=false)byte[] body)throws IOException,InterruptedException{
  String prefix="/api/firewall";String path=req.getRequestURI().startsWith(prefix)?req.getRequestURI().substring(prefix.length()):"/";
  String target=base+"/api"+path+(req.getQueryString()==null?"":"?"+req.getQueryString());
  HttpRequest.BodyPublisher publisher=body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body);
  HttpRequest.Builder b=HttpRequest.newBuilder(URI.create(target)).timeout(Duration.ofSeconds(60)).method(req.getMethod(),publisher);
  if(req.getContentType()!=null)b.header("Content-Type",req.getContentType());
  String user=req.getRemoteUser();if(user!=null)b.header("X-Astral-User",user);
  HttpResponse<byte[]> r=client.send(b.build(),HttpResponse.BodyHandlers.ofByteArray());
  HttpHeaders h=new HttpHeaders();String ct=r.headers().firstValue("content-type").orElse(null);if(ct!=null)h.set("Content-Type",ct);
  return new ResponseEntity<>(r.body(),h,HttpStatusCode.valueOf(r.statusCode()));
 }
}
