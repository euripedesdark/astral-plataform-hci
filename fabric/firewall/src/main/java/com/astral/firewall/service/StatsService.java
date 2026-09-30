package com.astral.firewall.service;
import org.springframework.stereotype.Service;
import java.util.*;
@Service
public class StatsService {
private static final String NL=String.valueOf((char)10);
private final IptablesService ipt;
public StatsService(IptablesService i){ipt=i;}
public Map<String,Object> stats(){
Map<String,Object> m=new HashMap<>();
String log=ipt.sh("sudo journalctl -k -o short-unix --since '24 hours ago' 2>/dev/null | grep 'ASTRAL-FW' || true");
int blocked=0,rejected=0,ssh=0; Map<String,int[]> top=new HashMap<>(); int[] hourB=new int[24]; int[] hourA=new int[24];
long now=System.currentTimeMillis()/1000;
for(String line:log.split(NL)){ if(line.isBlank())continue;
try{ long ts=Long.parseLong(line.trim().split(" ")[0]); int h=(int)((now-ts)/3600); if(h<0||h>23)continue;
int si=line.indexOf("SRC="); String src=si>=0?line.substring(si+4).split(" ")[0]:"";
int di=line.indexOf("DPT="); String dpt=di>=0?line.substring(di+4).split(" ")[0]:"";
if(line.contains("ASTRAL-FW-DROP")||line.contains("ASTRAL-FW-PANIC")){blocked++;hourB[h]++;
if(!src.isEmpty()){String key=src+"|"+dpt; top.computeIfAbsent(key,k->new int[]{0})[0]++; if("22".equals(dpt))ssh++;}}
else if(line.contains("ASTRAL-FW-REJECT")){rejected++;}
}catch(Exception ignored){} }
long accepted=0; String l=ipt.sh("sudo iptables -L INPUT -v -n 2>/dev/null");
for(String line:l.split(NL)) if(line.contains("ACCEPT")){ String[] c=line.trim().split(" "); try{accepted+=Long.parseLong(c[0].replaceAll("[^0-9]",""));}catch(Exception ignored){} }
for(int i=0;i<24;i++) hourA[i]=(int)(accepted/24);
m.put("blocked24",blocked); m.put("rejected24",rejected); m.put("accepted24",accepted); m.put("sshAttempts",ssh);
List<Map<String,Object>> topList=new ArrayList<>();
top.entrySet().stream().sorted((a,b)->b.getValue()[0]-a.getValue()[0]).limit(6).forEach(e->{
String[] p=e.getKey().split("\\|"); Map<String,Object> row=new HashMap<>(); row.put("ip",p[0]); row.put("port",p.length>1?p[1]:""); row.put("action","DROP"); row.put("count",e.getValue()[0]); topList.add(row);});
m.put("topBlocked",topList);
List<Map<String,Object>> series=new ArrayList<>(); for(int i=23;i>=0;i--){Map<String,Object> b=new HashMap<>();b.put("h",i);b.put("blocked",hourB[i]);b.put("allowed",hourA[i]);series.add(b);}
m.put("series",series); return m;
}
}
