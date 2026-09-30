package com.astral.firewall.ws;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.*;
import reactor.core.publisher.Flux; import reactor.core.publisher.Mono;
import java.io.InputStream; import java.nio.charset.StandardCharsets;
@Component
public class FirewallLogsWebSocketHandler implements WebSocketHandler {
@Override public Mono<Void> handle(WebSocketSession session){
final Process proc; try{ proc=new ProcessBuilder("bash","-c","journalctl -k -f --output=cat 2>/dev/null | grep --line-buffered 'ASTRAL-FW'").start(); }
catch(Exception e){ return session.close(); }
Flux<WebSocketMessage> out=Flux.create(sink->{ Thread t=new Thread(()->{ try(InputStream in=proc.getInputStream()){
byte[] buf=new byte[4096]; int n; while((n=in.read(buf))!=-1) sink.next(session.textMessage(new String(buf,0,n,StandardCharsets.UTF_8))); }catch(Exception ignored){} sink.complete(); }); t.setDaemon(true); t.start(); });
return session.send(out).then(Mono.<Void>never()).onErrorResume(e->Mono.empty()).doFinally(s->proc.destroyForcibly());
}
}
