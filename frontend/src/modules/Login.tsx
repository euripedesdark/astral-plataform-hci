import React, {useState} from 'react';
import {login} from '../shared/services/api';
const HEXA=<svg viewBox="0 0 100 100" xmlns="http://www.w3.org/2000/svg"><polygon points="50,5 95,25 95,75 50,95 5,75 5,25" fill="none" stroke="#00d2ff" strokeWidth="4"/><path d="M50,25 L75,70 L65,70 L50,40 L35,70 L25,70 Z" fill="#00d2ff"/></svg>;
const USERI=<svg className="input-icon" viewBox="0 0 24 24"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path><circle cx="12" cy="7" r="4"></circle></svg>;
const LOCKI=<svg className="input-icon" viewBox="0 0 24 24"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"></rect><path d="M7 11V7a5 5 0 0 1 10 0v4"></path></svg>;
export default function Login({onAuth}){
 const[u,setU]=useState(''),[p,setP]=useState(''),[busy,setBusy]=useState(false),[err,setErr]=useState('');
 async function go(e){
  e&&e.preventDefault();
  if(busy)return;setBusy(true);setErr('');
  try{
   const data=await login(u,p);
   if(data&&(data.token||data.authenticated===true)){
    document.cookie='astral_token='+encodeURIComponent(data.token)+'; path=/';
    try{localStorage.setItem('astral_token',data.token)}catch(_){}
    onAuth(data);
   }else{setErr('Credenciais inválidas ou resposta inesperada.')}
  }catch(ex){setErr('Credenciais inválidas ou erro de rede.');}
  finally{setBusy(false)}
 }
 return <div className="lg-page"><div className="brand-title">ASTRAL PLATFORM</div>
  <div className="login-box"><div className="center-logo">{HEXA}</div>
   <form id="loginForm" onSubmit={go}>
    <div className="input-group"><label htmlFor="username">Login</label>
     <div className="input-wrapper"><input type="text" id="username" autoComplete="off" required value={u} onChange={e=>setU(e.target.value)}/>{USERI}</div></div>
    <div className="input-group"><label htmlFor="password">Password</label>
     <div className="input-wrapper"><input type="password" id="password" required value={p} onChange={e=>setP(e.target.value)}/>{LOCKI}</div></div>
    <div className="divider"></div>
    <button type="submit" className="btn-submit" id="submitBtn" disabled={busy}>{busy?'AUTHENTICATING...':'SIGN IN'}</button>
    <div className="error-msg" id="errorMsg" style={err?{display:'block'}:{}}>{err||'Credenciais inválidas'}</div>
   </form></div></div>;
}
