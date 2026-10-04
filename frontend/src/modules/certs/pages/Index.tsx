import React, {useState} from 'react';
import {Card} from 'primereact/card';
import {InputText} from 'primereact/inputtext';
import {Password} from 'primereact/password';
import {Button} from 'primereact/button';
export default function CertsPage(){
 const[u,setU]=useState(''),[p,setP]=useState(''),[busy,setBusy]=useState(false),[msg,setMsg]=useState('');
 async function dl(){
  setBusy(true);setMsg('');
  try{
   const r=await fetch('/api/certs/ca/download',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:u,password:p})});
   if(r.status===401)throw new Error('Credenciais inválidas.');
   if(r.status===503)throw new Error('CA ainda não emitida neste servidor.');
   if(!r.ok)throw new Error('Falha '+r.status);
   const blob=await r.blob();const a=document.createElement('a');
   a.href=URL.createObjectURL(blob);a.download='srvcloud-root-ca.crt';document.body.appendChild(a);a.click();a.remove();
   setMsg('Certificado baixado. Instale como CA confiável para acessar o AD via LDAPS.');
  }catch(e){setMsg(e.message)}finally{setBusy(false)}
 }
 return <div className="grid"><Card title="CA do Active Directory"><p className="muted">Somente superuser do banco. O certificado permite confiar no LDAPS do AD nesta máquina.</p>
  <div className="formrow"><InputText value={u} placeholder="Usuário (ex: postgres)" onChange={e=>setU(e.target.value)}/></div>
  <div className="formrow"><Password value={p} placeholder="Senha" feedback={false} toggleMask onChange={e=>setP(e.target.value)} onKeyDown={e=>e.key==='Enter'&&dl()}/></div>
  <div className="formrow"><Button label={busy?'Verificando...':'Baixar certificado'} icon="pi pi-download" disabled={busy||!u||!p} onClick={dl}/></div>
  {msg&&<p className="muted">{msg}</p>}</Card></div>;
}
