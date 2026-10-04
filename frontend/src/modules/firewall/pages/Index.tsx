import React, {useEffect, useState} from 'react';
import {Card} from 'primereact/card';
import {DataTable} from 'primereact/datatable';
import {Column} from 'primereact/column';
import {Button} from 'primereact/button';
import {InputText} from 'primereact/inputtext';
import {api} from '../../../shared/services/api';
const SECS=[['dashboard','Dashboard'],['rules','Regras'],['nat','Port Forwarding'],['zones','Zonas'],['groups','Grupos'],['protections','Proteções'],['logs','Logs'],['backup','Backup & Auditoria']];
function useFw(path,dep){
 const[d,setD]=useState(null),[err,setErr]=useState('');
 useEffect(()=>{let on=true;setErr('');api('/api/firewall/'+path).then(r=>{if(on)setD(r)}).catch(e=>{if(on)setErr(e.message)});return ()=>{on=false}},[path,dep]);
 return [d,err,setD];
}
type Sev='info'|'secondary'|'success'|'warning'|'danger'|'help'|'contrast';
function Btn({l,fn,sev='info' as Sev}:{l:string;fn:()=>void;sev?:Sev}){return <Button label={l} severity={sev} size="small" onClick={fn} style={{marginRight:6}}/>;}
function Dash(){
 const[st]=useFw('status',0);const[sm]=useFw('stats',0);
 if(!st&&!sm)return <p className="muted">Carregando…</p>;
 return <div><div className="grid">
  <Card title="BLOQUEADOS (24H)"><div className="metric">{sm?.blocked24??'—'}</div><small className="muted">+{sm?.rejected24??'?'} rejeitados</small></Card>
  <Card title="PERMITIDOS (24H)"><div className="metric">{sm?.accepted24??'—'}</div><small className="muted">tráfego normal</small></Card>
  <Card title="REGRAS ATIVAS"><div className="metric">{st?.rulesActive??'?'}</div><small className="muted">{st?.pending??0} pendentes</small></Card>
  <Card title="PÂNICO"><div className="metric">{st?.panicActive?'ON':'OFF'}</div></Card></div></div>;
}
function Panic(){
 const[st,setSt]=useState(0),[msg,setMsg]=useState('');
 async function fire(){setMsg('');try{await api('/api/firewall/panic?confirm=true',{method:'POST'});setMsg('Pânico ATIVADO')}catch(e){setMsg(e.message)}finally{setSt(0)}}
 if(st===0)return <button className="panicbtn" onClick={()=>setSt(1)}>MODO PÂNICO<small>bloquear tudo, exceto admin</small></button>;
 return <div><p><b>Bloquear tudo, exceto admin?</b></p><Button label="Confirmar" severity="danger" onClick={fire}/><Button text label="Cancelar" onClick={()=>setSt(0)}/>{msg&&<p className="muted">{msg}</p>}</div>;
}
function Rules(){
 const[rs,err,reload]=useFw('rules',0);const[f,setF]=useState({protocol:'TCP',port:'',src:'',dst:'',action:'ACCEPT'});
 async function add(){await api('/api/firewall/rules',{method:'POST',body:JSON.stringify({chain:'INPUT',protocol:f.protocol,port:f.port,srcCidr:f.src,dstCidr:f.dst,action:f.action,enabled:true})});reload({});}
 async function del(id){await api('/api/firewall/rules/'+id,{method:'DELETE'});reload({});}
 async function apply(){await api('/api/firewall/rules/apply',{method:'POST'});reload({});}
 if(err)return <div className="error">{err}</div>;if(!rs)return <p className="muted">Carregando…</p>;
 return <div><Btn l="Aplicar" fn={apply}/><DataTable value={rs} paginator rows={12} size="small">
  <Column field="priority" header="#"/><Column field="chain" header="CHAIN"/><Column field="protocol" header="PROTO"/><Column field="port" header="PORTA"/><Column field="srcCidr" header="ORIGEM"/><Column field="action" header="AÇÃO"/>
  <Column header="" body={r=><Button icon="pi pi-times" size="small" severity="danger" onClick={()=>del(r.id)}/>}/></DataTable>
  <div className="formrow"><InputText value={f.port} placeholder="Porta" onChange={e=>setF({...f,port:e.target.value})}/><InputText value={f.src} placeholder="Origem" onChange={e=>setF({...f,src:e.target.value})}/><Btn l="+ Nova Regra" fn={add}/></div></div>;
}
function Forwards(){
 const[fs,err,reload]=useFw('forwards',0);const[ms]=useFw('masquerade',0);
 const[f,setF]=useState({proto:'TCP',ext:'',ip:'',int:''});
 async function add(){await api('/api/firewall/forwards',{method:'POST',body:JSON.stringify({externalPort:f.ext,protocol:f.proto,internalIp:f.ip,internalPort:f.int,enabled:true})});reload({});}
 async function del(id){await api('/api/firewall/forwards/'+id,{method:'DELETE'});reload({});}
 if(err)return <div className="error">{err}</div>;if(!fs)return <p className="muted">Carregando…</p>;
 return <div><Card title="PORT FORWARDING"><DataTable value={fs} size="small"><Column field="externalPort" header="EXT"/><Column field="protocol" header="PROTO"/><Column field="internalIp" header="INTERNO"/><Column header="" body={r=><Button icon="pi pi-times" size="small" severity="danger" onClick={()=>del(r.id)}/>}/></DataTable>
 <div className="formrow"><InputText value={f.ext} placeholder="Porta ext" onChange={e=>setF({...f,ext:e.target.value})}/><InputText value={f.ip} placeholder="IP interno" onChange={e=>setF({...f,ip:e.target.value})}/><InputText value={f.int} placeholder="Porta int" onChange={e=>setF({...f,int:e.target.value})}/><Btn l="+ Forward" fn={add}/></div></Card>
 <Card title="MASQUERADE"><DataTable value={ms||[]} size="small"><Column field="iface" header="IFACE"/></DataTable></Card></div>;
}
function Zones(){
 const[z,err,reload]=useFw('zones',0);const[n,setN]=useState({name:'',policy:'DROP'});
 async function add(){await api('/api/firewall/zones',{method:'POST',body:JSON.stringify({name:n.name,trustLevel:'LAN',defaultPolicy:n.policy})});reload({});}
 async function del(id){await api('/api/firewall/zones/'+id,{method:'DELETE'});reload({});}
 if(err)return <div className="error">{err}</div>;if(!z)return <p className="muted">Carregando…</p>;
 return <div><DataTable value={z} size="small"><Column field="name" header="REDE"/><Column field="trustLevel" header="TRUST"/><Column field="defaultPolicy" header="POLÍTICA"/><Column header="" body={r=><Button icon="pi pi-times" size="small" severity="danger" onClick={()=>del(r.id)}/>}/></DataTable>
 <div className="formrow"><InputText value={n.name} placeholder="IP ou rede" onChange={e=>setN({...n,name:e.target.value})}/><Btn l="+ Zona" fn={add}/></div></div>;
}
function Groups(){
 const[h,errh,rh]=useFw('hostgroups',0);const[p,errp,rp]=useFw('portgroups',0);
 const[hn,setHn]=useState({n:'',c:''});const[pn,setPn]=useState({n:'',p:''});
 async function addH(){await api('/api/firewall/hostgroups',{method:'POST',body:JSON.stringify({name:hn.n,cidrs:hn.c.split(',').map(s=>s.trim()).filter(Boolean)})});rh({});}
 async function addP(){await api('/api/firewall/portgroups',{method:'POST',body:JSON.stringify({name:pn.n,ports:pn.p.split(',').map(s=>s.trim()).filter(Boolean)})});rp({});}
 if(errh||errp)return <div className="error">{errh||errp}</div>;if(!h||!p)return <p className="muted">Carregando…</p>;
 return <div><Card title="GRUPOS DE HOSTS"><DataTable value={h} size="small"><Column field="name" header="NOME"/><Column field="cidrs" header="CIDRs" body={r=>(r.cidrs||[]).join(', ')}/></DataTable>
 <div className="formrow"><InputText value={hn.n} placeholder="Nome" onChange={e=>setHn({...hn,n:e.target.value})}/><InputText value={hn.c} placeholder="CIDRs vírgula" onChange={e=>setHn({...hn,c:e.target.value})}/><Btn l="+ Grupo" fn={addH}/></div></Card>
 <Card title="GRUPOS DE PORTAS"><DataTable value={p} size="small"><Column field="name" header="NOME"/><Column field="ports" header="PORTAS" body={r=>(r.ports||[]).join(', ')}/></DataTable>
 <div className="formrow"><InputText value={pn.n} placeholder="Nome" onChange={e=>setPn({...pn,n:e.target.value})}/><InputText value={pn.p} placeholder="Portas vírgula" onChange={e=>setPn({...pn,p:e.target.value})}/><Btn l="+ Grupo" fn={addP}/></div></Card></div>;
}
function Protections(){
 const[r]=useFw('ratelimits',0);const[a]=useFw('autoban',0);const[t]=useFw('threatlists',0);
 const[f,setF]=useState({port:'',rate:'20'});
 async function addR(){await api('/api/firewall/ratelimits',{method:'POST',body:JSON.stringify({port:f.port,protocol:'TCP',ratePerSecond:+f.rate,enabled:true})});}
 if(!r||!a||!t)return <p className="muted">Carregando…</p>;
 return <div><Card title="RATE LIMIT"><DataTable value={r} size="small"><Column field="port" header="PORTA"/><Column field="protocol" header="PROTO"/><Column field="ratePerSecond" header="TAXA/SEG"/></DataTable>
 <div className="formrow"><InputText value={f.port} placeholder="Porta" onChange={e=>setF({...f,port:e.target.value})}/><InputText value={f.rate} placeholder="Taxa" onChange={e=>setF({...f,rate:e.target.value})}/><Btn l="+ Rate Limit" fn={addR}/></div></Card>
 <Card title="AUTO-BAN"><DataTable value={a} size="small"><Column field="targetPort" header="ALVO"/><Column field="maxAttempts" header="TENTATIVAS"/><Column field="banMinutes" header="BAN MIN"/></DataTable></Card>
 <Card title="BLOCKLISTS"><DataTable value={t} size="small"><Column field="name" header="NOME"/><Column field="ipCount" header="IPS"/></DataTable></Card></div>;
}
function Logs(){
 const[lines,setLines]=useState([]);
 useEffect(()=>{const id=setInterval(()=>{api<{raw:string}[]>('/api/firewall/logs').then(l=>setLines(l.map(x=>x.raw))).catch(()=>{})},2000);return ()=>clearInterval(id)},[]);
 return <div className="panel"><h3>LOGS (tempo real)</h3><pre id="live">{lines.join('\n')}</pre></div>;
}
function Backup(){
 const[sn,setSn]=useState([]);
 useEffect(()=>{api('/api/firewall/snapshots').then(setSn).catch(()=>{})},[]);
 async function revert(id){await api('/api/firewall/snapshots/revert/'+id,{method:'POST'});}
 return <div className="panel"><h3>SNAPSHOTS</h3>{(sn||[]).map(x=><div key={x.id}>{x.createdAt} — {x.label} <Btn l="reverter" fn={()=>revert(x.id)}/></div>)}</div>;
}
export default function Firewall(){
 const[t,setT]=useState('dashboard');
 const titles={dashboard:'DASHBOARD — VISÃO GERAL',rules:'REGRAS',nat:'PORT FORWARDING',zones:'ZONAS',groups:'GRUPOS',protections:'PROTEÇÕES',logs:'LOGS',backup:'BACKUP & AUDITORIA'};
 return <div className="fwwrap"><aside className="fwside"><div className="fwtitle">MÓDULOS · FIREWALL</div>
  <div className="fwnav">{SECS.map(([id,l],i)=><button key={id} className={t===id?'on':''} onClick={()=>setT(id)}><span className="n">{'0'+(i+1)}</span>{l}</button>)}</div>
  <Panic/></aside>
  <main className="fwmain"><span className="badge">● MOTOR ATIVO · iptables</span><h2>{titles[t]}</h2><div id="content"></div>
  {t==='dashboard'&&<Dash/>}{t==='rules'&&<Rules/>}{t==='nat'&&<Forwards/>}{t==='zones'&&<Zones/>}{t==='groups'&&<Groups/>}{t==='protections'&&<Protections/>}{t==='logs'&&<Logs/>}{t==='backup'&&<Backup/>}</main></div>;
}
