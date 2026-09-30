import React, {useEffect, useState} from 'react';
import {Card} from 'primereact/card';
import {api} from '../../../shared/services/api';
export const MODS=[
 {id:'Firewall',did:'firewall',img:'/images/firewall.png',desc:'Regras, NAT, zonas e pânico'},
 {id:'Traffic Server',did:'proxy',img:'/images/proxy_system.png',desc:'Proxy ATS 3128 + auth AD'},
 {id:'Active Directory',did:'domain',img:'/images/domain_controllers.png',desc:'Samba AD, LDAPS 636'},
 {id:'PostgreSQL',did:'postgres',img:'/images/postgresql_admin.png',desc:'Banco astral, mTLS'},
 {id:'Observabilidade',did:'web',img:'/images/web_server_admin.png',desc:'Graylog + métricas'},
 {id:'Computação',did:'vm',img:'/images/virtual_machines.png',desc:'KVM e contêineres OCI'},
 {id:'Rede',did:'network',img:'/images/network_config_vlan.png',desc:'VLANs e tecido'},
 {id:'Certificados',did:'postgres',img:'/images/login.png',desc:'Baixar CA do AD'},
 {id:'Terminal',did:'terminal',img:'/images/terminal.jpeg',desc:'Acesso SSH'},
];
export default function OverviewGo({setSection}){
 const[fw,setFw]=useState(null);
 useEffect(()=>{api('/api/firewall/status').then(setFw).catch(()=>{})},[]);
 return <div><div className="grid">{MODS.map(m=><Card key={m.id} title={m.id} className="modcard" data-id={m.did} onClick={()=>setSection(m.id)}><img src={m.img} alt={m.id} className="modimg" onError={e=>e.target.style.display='none'}/><p className="muted">{m.desc}</p>{m.id==='Firewall'&&<small className="muted">{fw?.active?'ATIVO':'—'} · {fw?.rulesActive??'?'} regras</small>}</Card>)}</div></div>;
}
