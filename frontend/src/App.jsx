import React,{useEffect,useState}from'react';
import{Button}from'primereact/button';import{Tag}from'primereact/tag';import{ProgressSpinner}from'primereact/progressspinner';
import{me,logout}from'./shared/services/api';
import Login from'./shared/components/Login';
import{ORDER,ICONS,PAGES}from'./routes/routes';
function App(){const[user,setUser]=useState(null),[loading,setLoading]=useState(true),[section,setSection]=useState('Visão geral');useEffect(()=>{me().then(setUser).catch(()=>{}).finally(()=>setLoading(false))},[]);if(loading)return <div className="center"><ProgressSpinner/></div>;if(!user)return <Login onAuth={setUser}/>;const isAdmin=(user.authorities||[]).some(a=>a.authority==='ROLE_ASTRAL_ADMIN');const Page=PAGES[section];return <div className="page">
<header className="topbar"><div className="brand">ASTRAL<small>Platform & HCI</small></div>
<div className="topright">{section!=='Visão geral'&&<Button label="Início" icon="pi pi-home" onClick={()=>setSection('Visão geral')}/>}
<Tag value={isAdmin?'ADMIN':'USER'} severity="info"/><Button text label={user.username} icon="pi pi-user"/><Button text label="Sair" icon="pi pi-sign-out" onClick={async()=>{await logout();setUser(null)}}/></div></header>
{section==='Visão geral'?<h1 className="hometitle">ASTRAL PLATFORM</h1>:<h1 className="modtitle">{section}</h1>}
<Page user={user} setSection={setSection}/></div>;}
export default App;
