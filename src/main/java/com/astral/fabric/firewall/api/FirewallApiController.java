package com.astral.fabric.firewall.api;
import com.astral.fabric.firewall.model.*; import com.astral.fabric.firewall.repo.*; import com.astral.fabric.firewall.service.*;
import org.springframework.http.MediaType; import org.springframework.web.bind.annotation.*; import org.springframework.security.access.prepost.PreAuthorize;
import java.net.InetAddress; import java.util.*; import java.util.concurrent.Callable;
@RestController @RequestMapping("/api/firewall")
@PreAuthorize("hasRole('ASTRAL_ADMIN')")
public class FirewallApiController {
private static final String NL=String.valueOf((char)10);
private final IptablesService ipt; private final StatsService stats; private final AuditService audit; private final BackupService backup;
private final FirewallRuleRepo rules; private final PortForwardRepo forwards; private final MasqueradeRuleRepo masq;
private final ZoneRepo zones; private final ZoneInterfaceRepo zifaces; private final HostGroupRepo hgroups; private final PortGroupRepo pgroups;
private final ScheduleRepo schedules; private final RateLimitPolicyRepo rates; private final AutoBanRuleRepo bans; private final ThreatListRepo threats;
private final AuditLogRepo audits; private final FirewallSnapshotRepo snaps;
public FirewallApiController(IptablesService i,StatsService s,AuditService a,BackupService b,FirewallRuleRepo r,PortForwardRepo f,MasqueradeRuleRepo m,ZoneRepo z,ZoneInterfaceRepo zi,HostGroupRepo hg,PortGroupRepo pg,ScheduleRepo sc,RateLimitPolicyRepo rl,AutoBanRuleRepo ab,ThreatListRepo tl,AuditLogRepo al,FirewallSnapshotRepo sn){ipt=i;stats=s;audit=a;backup=b;rules=r;forwards=f;masq=m;zones=z;zifaces=zi;hgroups=hg;pgroups=pg;schedules=sc;rates=rl;bans=ab;threats=tl;audits=al;snaps=sn;}
// Antes isto era Mono.fromCallable(...).subscribeOn(boundedElastic): o modulo
// solto era webflux e o retorno reativo era o que impedia iptables/journalctl
// de bloquear no event loop. No MVC servlet com virtual threads do Java 21 a
// chamada direta ja' e' a thread certa -- nao ha event loop para bloquear, e
// manter o retorno reativo so custaria o reactor no classpath.
private <T> T call(Callable<T> c){
 try{ return c.call(); }
 catch(RuntimeException e){ throw e; }
 catch(Exception e){ throw new IllegalStateException(e.getMessage()==null?e.toString():e.getMessage(), e); }
}
@GetMapping("/status") public Map<String,Object> status(){return call(()->{Map<String,Object> m=new HashMap<>();m.put("active",true);m.put("motor","iptables");m.put("panicActive",ipt.panicActive());m.put("rulesActive",rules.count());m.put("pending",0);return m;});}
@GetMapping("/stats") public Map<String,Object> stats(){return call(() -> stats.stats());}
@GetMapping("/ifaces") public List<String> ifaces(){return call(()->{String out=ipt.sh("ls /sys/class/net/");List<String> r=new ArrayList<>();r.add("any");if(out!=null)for(String i:out.split(NL))if(!i.isBlank()&&!i.trim().equals("lo"))r.add(i.trim());return r;});}
@GetMapping("/ui-data") public Map<String,Object> uiData(){return call(()->{
Map<String,Object> m=new HashMap<>(); String out=ipt.sh("ls /sys/class/net/"); List<String> r=new ArrayList<>(); r.add("any");
if(out!=null)for(String i:out.split(NL))if(!i.isBlank()&&!i.trim().equals("lo"))r.add(i.trim());
m.put("ifaces", r); m.put("hostGroups", hgroups.findAll()); m.put("portGroups", pgroups.findAll()); return m;
});}
@PostMapping("/panic") public Map<String,Object> panic(@RequestParam(defaultValue="false") boolean confirm){return call(() -> {if(!confirm) throw new IllegalStateException("panic exige confirm=true"); return ipt.panic("admin");});}
@PostMapping("/panic/revert") public Map<String,Object> revert(){return call(() -> ipt.revert("admin"));}
@GetMapping("/rules") public List<FirewallRule> rules(){return call(() -> { ipt.syncFromRuntime(); return rules.findAll(); });}
@PostMapping("/rules") public Map<String,Object> saveRule(@RequestBody FirewallRule r){return call(()->{
String chain = (r.chain != null && !r.chain.isBlank()) ? r.chain : "INPUT";
String proto = (r.protocol != null && !r.protocol.isBlank()) ? r.protocol.toLowerCase() : "tcp";
String action = (r.action != null && !r.action.isBlank()) ? r.action : "ACCEPT";
String cmd = "-I " + chain + " 1";
if(!proto.equals("all")) { cmd += " -p " + proto; if(proto.equals("tcp")||proto.equals("udp")) cmd += " -m " + proto; }
Optional<PortGroup> pg = pgroups.findAll().stream().filter(g -> g.name.equals(r.port)).findFirst();
if(pg.isPresent()) { cmd += " -m multiport --dports " + String.join(",", pg.get().ports) + " "; } else if(r.port != null && !r.port.isBlank()) { if(r.port.contains(",")) cmd += " -m multiport --dports " + r.port.replace(" ", ""); else cmd += " --dport " + r.port + " "; }
String src=normalizeCidr(r.srcCidr); Optional<HostGroup> hgSrc = src==null ? Optional.empty() : hgroups.findAll().stream().filter(g -> g.name.equals(src)).findFirst();
if(hgSrc.isPresent()) { cmd += " -m set --match-set hg_" + hgSrc.get().name.replaceAll("[^a-zA-Z0-9_]", "") + " src "; } else if(src!=null) { cmd += " -s " + src + " "; }
String dst=normalizeCidr(r.dstCidr); Optional<HostGroup> hgDst = dst==null ? Optional.empty() : hgroups.findAll().stream().filter(g -> g.name.equals(dst)).findFirst();
if(hgDst.isPresent()) { cmd += " -m set --match-set hg_" + hgDst.get().name.replaceAll("[^a-zA-Z0-9_]", "") + " dst "; } else if(dst!=null) { cmd += " -d " + dst + " "; }
cmd += " -j " + action;
String out = ipt.executeAndSync(cmd);
if(out != null && out.contains("ERR")) return Map.of("success", false, "error", out);
audit.log("admin","RULE","*","SAVE",cmd); return Map.of("success", true);
});}
private String normalizeCidr(String s){ if(s==null) return null; String t=s.trim(); if(t.isEmpty()||t.equalsIgnoreCase("any")||t.equalsIgnoreCase("all")||t.equals("*")||t.equals("0.0.0.0/0")) return null; return t; }
@DeleteMapping("/rules/{id}") public Map<String,Object> delRule(@PathVariable Long id){return call(()->{
rules.findById(id).ifPresent(r->{ if(r.rawRule != null) ipt.executeAndSync("-D " + r.chain + " " + r.rawRule.substring(r.chain.length()).trim()); });
audit.log("admin","RULE",id.toString(),"DELETE",""); return Map.of("success",true);
});}
@PostMapping("/rules/apply") public Map<String,Object> apply(){return call(() -> { ipt.syncFromRuntime(); return Map.of("success",true); });}
@PostMapping("/rules/reorder") public Map<String,Object> reorder(@RequestBody Map<String,Object> body){return call(()->{ ipt.syncFromRuntime(); return Map.of("success",true); });}
@GetMapping("/simulate") public Map<String,Object> sim(@RequestParam String proto,@RequestParam String port,@RequestParam(defaultValue="0.0.0.0") String src,@RequestParam(defaultValue="0.0.0.0") String dst){return call(()->{for(FirewallRule r:rules.findByChainOrderByPriority("INPUT"))if(r.enabled&&match(r,proto,port,src,dst))return Map.of("match",true,"rule",r);return Map.<String,Object>of("match",false,"policy","DROP");});}
private boolean match(FirewallRule r,String proto,String port,String src,String dst){if(!r.protocol.equals("ALL")&&!r.protocol.equalsIgnoreCase(proto))return false;if(!r.port.isBlank()&&!r.port.equals(port))return false;if(!r.srcCidr.isBlank()&&!inCidr(src,r.srcCidr))return false;if(!r.dstCidr.isBlank()&&!inCidr(dst,r.dstCidr))return false;return true;}
private boolean inCidr(String ip,String cidr){try{String[] c=cidr.split("/");byte[] a=InetAddress.getByName(c[0]).getAddress();byte[] b=InetAddress.getByName(ip).getAddress();int bits=c.length>1?Integer.parseInt(c[1]):32,full=bits/8,rem=bits%8;for(int i=0;i<full;i++)if(a[i]!=b[i])return false;if(rem>0){int m=(0xFF00>>rem)&0xFF;if((a[full]&m)!=(b[full]&m))return false;}return true;}catch(Exception e){return false;}}
@GetMapping("/forwards") public List<PortForward> fw(){return call(() -> { ipt.updateNatStats(); return forwards.findAll(); });}
@PostMapping("/forwards") public Map<String,Object> saveFw(@RequestBody PortForward f){return call(()->{
boolean clash=forwards.findAll().stream().anyMatch(o->o.enabled && o.externalPort.equals(f.externalPort) && o.protocol.equals(f.protocol) && !o.id.equals(f.id));
if(clash)return Map.of("success",false,"error","Conflito de porta externa.");
PortForward saved=forwards.save(f); ipt.syncNatFromDb();
return Map.of("saved",saved,"sync",Map.of("success",true));});}
@DeleteMapping("/forwards/{id}") public Map<String,Object> delFw(@PathVariable Long id){return call(()->{forwards.deleteById(id); ipt.syncNatFromDb(); return Map.of("success",true);});}
@GetMapping("/masquerade") public List<MasqueradeRule> mq(){return call(() -> { ipt.updateNatStats(); return masq.findAll(); });}
@PostMapping("/masquerade") public Map<String,Object> saveMq(@RequestBody MasqueradeRule m){return call(()->{MasqueradeRule saved=masq.save(m); ipt.syncNatFromDb(); return Map.of("saved",saved,"sync",Map.of("success",true));});}
@DeleteMapping({"/masquerade/{id}","/masquerade_rule/{id}"}) public Map<String,Object> delMq(@PathVariable Long id){return call(()->{masq.deleteById(id); ipt.syncNatFromDb(); return Map.of("success",true);});}
@GetMapping("/zones") public List<Zone> z(){return call(() -> zones.findAll());}
@PostMapping("/zones") public Zone saveZ(@RequestBody Zone z){return call(() -> { Zone saved = zones.save(z); ipt.syncZonesFromDb(); return saved; });}
@DeleteMapping("/zones/{id}") public Map<String,String> delZ(@PathVariable Long id){return call(()->{zones.deleteById(id);zifaces.deleteByZoneId(id);ipt.syncZonesFromDb();return Map.of("success","true");});}
@GetMapping("/hostgroups") public List<HostGroup> hg(){return call(() -> hgroups.findAll());}
@PostMapping("/hostgroups") public HostGroup saveHg(@RequestBody HostGroup g){return call(() -> { HostGroup saved=hgroups.save(g); ipt.syncGroupsFromDb(); return saved; });}
@DeleteMapping("/hostgroups/{id}") public Map<String,String> delHg(@PathVariable Long id){return call(()->{hgroups.deleteById(id); ipt.syncGroupsFromDb(); return Map.of("success","true");});}
@GetMapping("/portgroups") public List<PortGroup> pg(){return call(() -> pgroups.findAll());}
@PostMapping("/portgroups") public PortGroup savePg(@RequestBody PortGroup g){return call(() -> { PortGroup saved=pgroups.save(g); return saved; });}
@DeleteMapping("/portgroups/{id}") public Map<String,String> delPg(@PathVariable Long id){return call(()->{pgroups.deleteById(id);return Map.of("success","true");});}
@GetMapping("/schedules") public List<Schedule> sc(){return call(() -> schedules.findAll());}
@PostMapping("/schedules") public Schedule saveSc(@RequestBody Schedule s){return call(() -> schedules.save(s));}
@DeleteMapping("/schedules/{id}") public Map<String,String> delSc(@PathVariable Long id){return call(()->{schedules.deleteById(id);return Map.of("success","true");});}
@GetMapping("/ratelimits") public List<RateLimitPolicy> rl(){return call(() -> rates.findAll());}
@PostMapping("/ratelimits") public Map<String,Object> saveRl(@RequestBody RateLimitPolicy r){return call(()->{rates.save(r); ipt.syncProtectionsFromDb(); return Map.of("success",true);});}
@DeleteMapping("/ratelimits/{id}") public Map<String,Object> delRl(@PathVariable Long id){return call(()->{rates.deleteById(id); ipt.syncProtectionsFromDb(); return Map.of("success",true);});}
@GetMapping("/autoban") public List<AutoBanRule> ab(){return call(() -> bans.findAll());}
@PostMapping("/autoban") public AutoBanRule saveAb(@RequestBody AutoBanRule b){return call(() -> { AutoBanRule saved = bans.save(b); ipt.syncProtectionsFromDb(); return saved; });}
@DeleteMapping("/autoban/{id}") public Map<String,String> delAb(@PathVariable Long id){return call(()->{bans.deleteById(id); ipt.syncProtectionsFromDb(); return Map.of("success","true");});}
@GetMapping("/threatlists") public List<ThreatList> tl(){return call(() -> threats.findAll());}
@PostMapping("/threatlists") public ThreatList saveTl(@RequestBody ThreatList t){return call(() -> { ThreatList saved = threats.save(t); ipt.syncProtectionsFromDb(); return saved; });}
@DeleteMapping("/threatlists/{id}") public Map<String,String> delTl(@PathVariable Long id){return call(()->{threats.deleteById(id); ipt.syncProtectionsFromDb(); return Map.of("success","true");});}
@PostMapping("/threatlists/{id}/refresh") public Map<String,Object> refreshTl(@PathVariable Long id){return call(()->{ThreatList t=threats.findById(id).orElseThrow();ipt.sh("sudo ipset create astral-threats hash:net -! 2>/dev/null");String raw=ipt.sh("curl -fsSL "+t.sourceUrl);int n=0;if(raw!=null&&!raw.isBlank()){StringBuilder sb=new StringBuilder();for(String line:raw.split(NL)){if(line.trim().startsWith("#"))continue;String ip=line.trim().split("\\s+")[0];if(isIpish(ip)){sb.append("add astral-threats ").append(ip).append(" -exist\n");n++;}}if(n>0){ipt.run(List.of("bash","-c","echo \""+sb.toString()+"\" | sudo ipset restore"), null);}}t.ipCount=n;t.lastUpdated=java.time.Instant.now();threats.save(t); ipt.syncProtectionsFromDb(); return Map.of("success",true,"ipCount",n);});}
private boolean isIpish(String s){if(s==null||s.isBlank())return false;int dots=0;for(char c:s.toCharArray()){if(c=='.')dots++;else if(!Character.isDigit(c)&&c!='/')return false;}return dots==3;}
@GetMapping("/logs") public List<Map<String,String>> logs(@RequestParam(required=false) String ip,@RequestParam(required=false) String action){return call(()->{List<Map<String,String>> out=new ArrayList<>();for(String line:ipt.sh("sudo journalctl -k -o short-unix --since '24 hours ago' 2>/dev/null | grep 'ASTRAL-FW' | tail -200").split(NL)){if(line.isBlank())continue;if(ip!=null&&!line.contains("SRC="+ip))continue;if(action!=null&&!line.contains("ASTRAL-FW-"+action))continue;out.add(Map.of("raw",line));}return out;});}
@GetMapping("/logs/export") public String exportLogs(@RequestParam(defaultValue="json") String format){return call(()->{String raw=ipt.sh("sudo journalctl -k --since '24 hours ago' 2>/dev/null | grep 'ASTRAL-FW' || true");if(format.equals("csv")){StringBuilder b=new StringBuilder("line\n");for(String l:raw.split("\n"))b.append(l.replace(",",";")).append("\n");return b.toString();}return raw;});}
@GetMapping("/snapshots") public List<FirewallSnapshot> snapList(){return call(()->snaps.findAllByOrderByCreatedAtDesc());}
@PostMapping("/snapshots/revert/{id}") public Map<String,Object> snapRevert(@PathVariable Long id){return call(()->{Optional<FirewallSnapshot> s=snaps.findById(id);if(s.isEmpty())return Map.of("success",false);ipt.restoreDump(s.get().dumpText);ipt.persist();ipt.syncFromRuntime();audit.log("admin","SNAPSHOT",id.toString(),"REVERT","");return Map.of("success",true);});}
@GetMapping(value="/backup",produces=MediaType.TEXT_PLAIN_VALUE) public String backup(){return call(() -> backup.exportSql());}
@PostMapping(value="/backup/restore",consumes=MediaType.TEXT_PLAIN_VALUE) public Map<String,Object> restore(@RequestBody String sql){return call(()->{boolean ok=backup.restoreSql(sql);if(ok){ipt.syncFromRuntime();}audit.log("admin","BACKUP","*","RESTORE","ok="+ok);return Map.of("success",ok);});}
@GetMapping("/audit") public List<AuditLog> auditList(){return call(()->audits.findTop200ByOrderByTimestampDesc());}
}
