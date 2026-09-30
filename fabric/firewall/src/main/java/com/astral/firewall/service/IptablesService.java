package com.astral.firewall.service;
import com.astral.firewall.model.*; import com.astral.firewall.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.*; import java.time.Instant; import java.util.*;
@Service
public class IptablesService {
private static final String NL=String.valueOf((char)10);
private static final String Q=String.valueOf((char)34);
private final FirewallRuleRepo rules; private final PortForwardRepo forwards; private final MasqueradeRuleRepo masq; private final ZoneRepo zones;
private final RateLimitPolicyRepo rates; private final AutoBanRuleRepo bans; private final ThreatListRepo threats; private final HostGroupRepo hgroups; private final PortGroupRepo pgroups;
private final FirewallSnapshotRepo snaps; private final FirewallStateRepo state;
private final AuditService audit;
public IptablesService(FirewallRuleRepo r,PortForwardRepo f,MasqueradeRuleRepo m,ZoneRepo z,RateLimitPolicyRepo rl,AutoBanRuleRepo ab,ThreatListRepo tl,HostGroupRepo hg,PortGroupRepo pg,FirewallSnapshotRepo s,FirewallStateRepo st,AuditService a){rules=r;forwards=f;masq=m;zones=z;rates=rl;bans=ab;threats=tl;hgroups=hg;pgroups=pg;snaps=s;state=st;audit=a;}
public String run(List<String> cmd,String stdin){try{Process p=new ProcessBuilder(cmd).redirectErrorStream(true).start();
if(stdin!=null){p.getOutputStream().write(stdin.getBytes());p.getOutputStream().close();}
String out=new String(p.getInputStream().readAllBytes());p.waitFor();return out;}catch(Exception e){return "ERR:"+e.getMessage();}}
public String sh(String c){return run(List.of("bash","-c",c),null);}
/**
 * Persiste o ruleset no arquivo que o init desta maquina carrega.
 *
 * <p>A versao anterior escrevia assim:
 * {@code sudo iptables-save > /etc/iptables/rules.v4 || ... > /etc/sysconfig/iptables}.
 * Tres defeitos, e os tres so apareceram quando a maquina trocou de distro:
 *
 * <p>1. O {@code >} e redirecionamento do shell: trunca o destino ANTES do
 * iptables-save rodar. O modulo sobe antes das regras salvas irem para o
 * kernel, entao o arquivo bom era destruido por um dump minimo. Medido em
 * 28/09, depois do reboot.
 * <p>2. O {@code ||} nunca disparava: iptables-save sai com 0 mesmo com ruleset
 * vazio. O segundo caminho era codigo morto.
 * <p>3. E o codigo morto era justamente o do Fedora. O primeiro caminho,
 * /etc/iptables/rules.v4, e do Debian; no Fedora o iptables.service le
 * /etc/sysconfig/iptables. As regras boas iam para um arquivo que ninguem le.
 *
 * <p>Agora o dump e aprovado antes de tocar qualquer arquivo, no mesmo criterio
 * que saveSnapshot() ja usava, e o destino e perguntado ao init em vez de
 * adivinhado pela distro.
 */
public void persist(){
  String dump=sh("sudo iptables-save");
  if(dump==null || !dump.contains("COMMIT") || dump.length()<100){
    System.out.println("[firewall] persist() pulado: ruleset nao carregado ("+(dump==null?0:dump.length())+" bytes). Destino preservado.");
    return; }
  String destino=caminhoDePersistencia();
  if(destino==null){
    System.out.println("[firewall] persist() pulado: nenhum servico de persistencia de iptables habilitado aqui.");
    return; }
  // tee em vez de ">": o conteudo ja foi aprovado, e nao ha shell no caminho.
  run(List.of("sudo","tee",destino), dump); }

/**
 * Pergunta ao init qual arquivo ele carrega, em vez de decidir pela distro.
 *
 * <p>netfilter-persistent (Debian) le /etc/iptables/rules.v4. iptables.service
 * (RHEL/Fedora) le o que IPTABLES_DATA apontar, normalmente
 * /etc/sysconfig/iptables. Se nenhum dos dois estiver habilitado, nao ha para
 * onde persistir e a resposta e null -- nunca um caminho chute de outra distro,
 * que era o que o switch por distro devolvia ao cair em unknown.
 */
private String caminhoDePersistencia(){
  if(!sh("sudo systemctl is-enabled netfilter-persistent 2>/dev/null").trim().equals("enabled")){
    if(!sh("sudo systemctl is-enabled iptables 2>/dev/null").trim().equals("enabled")) return null;
    String data=sh("sudo grep -h ^IPTABLES_DATA= /etc/sysconfig/iptables-config 2>/dev/null").trim();
    String caminho=data.replaceFirst("^IPTABLES_DATA=","").replace("\"","").trim();
    return caminho.isEmpty() ? "/etc/sysconfig/iptables" : caminho; }
  return "/etc/iptables/rules.v4"; }
@Transactional public synchronized void syncFromRuntime(){
rules.deleteAll(); String dump=sh("sudo iptables-save -c"); int p=1;
if(dump==null) return;
for(String l:dump.split(NL)){
String ruleLine=l; String pkts="0", bytes="0";
if(l.startsWith("[")) { int cb=l.indexOf(']'); if(cb>0){ String[] counts=l.substring(1,cb).split(":"); if(counts.length==2){pkts=counts[0]; bytes=counts[1];} ruleLine=l.substring(cb+2).trim(); } }
if(ruleLine.startsWith("-A INPUT") || ruleLine.startsWith("-A FORWARD") || ruleLine.startsWith("-A OUTPUT")){
if(ruleLine.contains("ASTRAL_BANNED") || ruleLine.contains("hashlimit") || ruleLine.contains("auto-fwd-") || ruleLine.contains("zone-rule-") || ruleLine.contains("astral-protection-") || ruleLine.contains("RELATED,ESTABLISHED")) continue;
FirewallRule r=new FirewallRule(); r.rawRule=ruleLine.substring(3).trim();
r.chain=r.rawRule.split(" ")[0]; r.priority=p++;
r.action=extract(ruleLine," -j ([a-zA-Z0-9_]+)",1,"ACCEPT");
r.protocol=extract(ruleLine," -p ([a-z0-9]+)",1,"ALL");
if(ruleLine.contains("match-set")) { r.srcCidr=extract(ruleLine,"--match-set hg_([a-zA-Z0-9_]+) src",1,""); r.dstCidr=extract(ruleLine,"--match-set hg_([a-zA-Z0-9_]+) dst",1,""); }
else { r.srcCidr=extract(ruleLine," -s ([0-9\\./a-zA-Z]+)",1,""); r.dstCidr=extract(ruleLine," -d ([0-9\\./a-zA-Z]+)",1,""); }
r.port=extract(ruleLine," --dports ([0-9:,]+)",1,extract(ruleLine," --dport ([0-9:]+)",1,""));
r.comment=extract(ruleLine," --comment \"([^\"]+)\"",1,"");
r.bytes=bytes; r.packets=pkts;
r.enabled=true; r.appliedAt=Instant.now();
rules.save(r); } } }
public void updateNatStats() {
String dump = sh("sudo iptables-save -c -t nat"); if(dump==null) return;
Map<Long, String> pfBytes = new HashMap<>(); Map<Long, String> masqBytes = new HashMap<>();
for(String l : dump.split(NL)) { if(!l.startsWith("[")) continue;
int cb = l.indexOf(']'); if(cb<0) continue;
String[] counts = l.substring(1,cb).split(":"); if(counts.length!=2) continue;
String bytes = counts[1];
java.util.regex.Matcher m1 = java.util.regex.Pattern.compile("pfwd-(\\d+)").matcher(l);
if(m1.find()) pfBytes.put(Long.parseLong(m1.group(1)), bytes);
java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("masq-(\\d+)").matcher(l);
if(m2.find()) masqBytes.put(Long.parseLong(m2.group(1)), bytes); }
List<PortForward> pfs = forwards.findAll(); boolean savePf = false;
for(PortForward pf : pfs) { if(pfBytes.containsKey(pf.id)) { pf.bytes = pfBytes.get(pf.id); savePf = true; } }
if(savePf) forwards.saveAll(pfs);
List<MasqueradeRule> masqs = masq.findAll(); boolean saveMq = false;
for(MasqueradeRule mq : masqs) { if(masqBytes.containsKey(mq.id)) { mq.bytes = masqBytes.get(mq.id); saveMq = true; } }
if(saveMq) masq.saveAll(masqs); }
private void clearManaged(String marker, String table) {
sh("sudo iptables-save -t " + table + " | grep '^-A .*" + marker + "' | sed 's/^-A /sudo iptables -t " + table + " -D /' | bash");
}
public void syncNatFromDb() {
clearManaged("pfwd-", "nat"); clearManaged("masq-", "nat"); clearManaged("auto-fwd-", "filter");
for(PortForward pf : forwards.findAll()) { if(pf.enabled) {
String pt = pf.protocol.toLowerCase();
String iface = (pf.iface != null && !pf.iface.isBlank() && !pf.iface.equalsIgnoreCase("any")) ? "-i " + pf.iface.trim() + " " : "";
String dest = pf.internalIp.trim();
if(pf.internalPort != null && !pf.internalPort.isBlank()) dest += ":" + pf.internalPort.trim();
sh("sudo iptables -t nat -A PREROUTING "+iface+"-p "+pt+" -m "+pt+" --dport "+pf.externalPort.trim()+" -m comment --comment \"pfwd-"+pf.id+"\" -j DNAT --to-destination "+dest);
String dport = (pf.internalPort != null && !pf.internalPort.isBlank()) ? pf.internalPort.trim() : pf.externalPort.trim();
sh("sudo iptables -I FORWARD 1 "+iface+"-p "+pt+" -m "+pt+" --dport "+dport+" -d "+pf.internalIp.trim()+" -m comment --comment \"auto-fwd-"+pf.id+"\" -j ACCEPT");
} }
for(MasqueradeRule m : masq.findAll()) { if(m.enabled) {
String iface = (m.iface != null && !m.iface.isBlank() && !m.iface.equalsIgnoreCase("any")) ? "-o " + m.iface.trim() + " " : "";
sh("sudo iptables -t nat -A POSTROUTING "+iface+"-m comment --comment \"masq-"+m.id+"\" -j MASQUERADE");
} }
persist(); syncFromRuntime(); }
public void syncZonesFromDb() {
clearManaged("zone-rule-", "filter");
for(Zone z : zones.findAll()) {
String action = z.defaultPolicy.toUpperCase();
sh("sudo iptables -I FORWARD 1 -s "+z.name.trim()+" -m comment --comment \"zone-rule-"+z.id+"\" -j "+action);
sh("sudo iptables -I INPUT 1 -s "+z.name.trim()+" -m comment --comment \"zone-rule-"+z.id+"\" -j "+action);
}
persist(); syncFromRuntime(); }
public void syncGroupsFromDb() {
for(HostGroup hg : hgroups.findAll()) {
String setName = "hg_" + hg.name.replaceAll("[^a-zA-Z0-9_]", "");
sh("sudo ipset create " + setName + " hash:net -! 2>/dev/null");
sh("sudo ipset flush " + setName + " 2>/dev/null");
for(String cidr : hg.cidrs) { if(cidr!=null && !cidr.isBlank()) sh("sudo ipset add " + setName + " " + cidr.trim() + " -! 2>/dev/null"); }
} }
public void syncProtectionsFromDb() {
clearManaged("astral-protection-", "filter");
sh("sudo ipset create astral-threats hash:net -! 2>/dev/null");
sh("sudo iptables -I INPUT 1 -m set --match-set astral-threats src -m comment --comment \"astral-protection-threats\" -j DROP");
sh("sudo iptables -I FORWARD 1 -m set --match-set astral-threats src -m comment --comment \"astral-protection-threats\" -j DROP");
for(AutoBanRule ab : bans.findAll()) { if(ab.enabled) {
String port = ab.targetPort.trim(); String name = "BAN" + port;
sh("sudo iptables -I INPUT 1 -p tcp --dport "+port+" -m state --state NEW -m recent --name "+name+" --update --seconds "+(ab.banMinutes*60)+" --hitcount "+ab.maxAttempts+" -m comment --comment \"astral-protection-autoban\" -j DROP");
sh("sudo iptables -I INPUT 2 -p tcp --dport "+port+" -m state --state NEW -m recent --name "+name+" --set -m comment --comment \"astral-protection-autoban\" -j ACCEPT");
} }
for(RateLimitPolicy rl : rates.findAll()) { if(rl.enabled) {
String pt = rl.protocol.toLowerCase();
sh("sudo iptables -I INPUT 1 -p "+pt+" --dport "+rl.port+" -m state --state NEW -m hashlimit --hashlimit-above "+rl.ratePerSecond+"/sec --hashlimit-burst 5 --hashlimit-mode srcip --hashlimit-name rl"+rl.port+" -m comment --comment \"astral-protection-ratelimit\" -j DROP");
} }
persist(); syncFromRuntime(); }
private String extract(String s,String p,int g,String d){java.util.regex.Matcher m=java.util.regex.Pattern.compile(p).matcher(s); return m.find()?m.group(g):d;}
public String executeAndSync(String cmd){ String out=sh("sudo iptables "+cmd); persist(); syncFromRuntime(); return out; }
public FirewallSnapshot saveSnapshot(String label){String dump=sh("sudo iptables-save");if(dump==null||!dump.contains("COMMIT")||dump.length()<100) throw new IllegalStateException("snapshot invalido, panic recusado");return snaps.save(new FirewallSnapshot(label,dump));}
public void restoreDump(String dump){run(List.of("bash","-c","echo \""+dump.replace("\"","\\\"")+"\" | sudo iptables-restore"),null);}
public Map<String,Object> applyFromDb(String user){
syncFromRuntime(); return Map.of("success",true);
}
public Map<String,Object> panic(String user){
saveSnapshot("pre-panic");
sh("sudo iptables -P INPUT DROP; sudo iptables -P FORWARD DROP; sudo iptables -F INPUT; sudo iptables -A INPUT -i lo -j ACCEPT; sudo iptables -A INPUT -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT; sudo iptables -A INPUT -p tcp --dport 22 -j ACCEPT; sudo iptables -A INPUT -s 127.0.0.1/32 -p tcp --dport 8040 -j ACCEPT; sudo iptables -A INPUT -p tcp --dport 5001 -j ACCEPT");
sh("for p in 22 53 80 81 88 135 139 389 443 445 464 636 953 3128 3268 3269 4369 4568 4569 5001 5432 5672 6379 8040 8080 8081 8082 8091 9000 9001 9090 15672 25672 27017 3306; do sudo iptables -A INPUT -p tcp --dport $p -j ACCEPT; done; for p in 53 88 123 137 138 389 464; do sudo iptables -A INPUT -p udp --dport $p -j ACCEPT; done");
persist(); syncFromRuntime(); setState("panic","ON"); audit.log(user,"FIREWALL","*","PANIC","");
return Map.of("success",true);
}
public Map<String,Object> revert(String user){Map<String,Object> res=new HashMap<>();
Optional<FirewallSnapshot> s=snaps.findAllByOrderByCreatedAtDesc().stream().filter(x->x.label.startsWith("pre-")).findFirst();
if(s.isEmpty()){res.put("success",false);res.put("error","Sem snapshot no banco.");return res;}
restoreDump(s.get().dumpText);persist(); syncFromRuntime(); setState("panic","OFF"); audit.log(user,"FIREWALL","*","REVERT","snapshot="+s.get().id);res.put("success",true);return res;}
public boolean panicActive(){return state.findById("panic").map(x->"ON".equals(x.value)).orElse(false);}
private void setState(String k,String v){FirewallState st=state.findById(k).orElse(new FirewallState());st.key=k;st.value=v;state.save(st);}
}
