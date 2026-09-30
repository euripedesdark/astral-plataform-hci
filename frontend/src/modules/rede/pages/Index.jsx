import React, {useEffect, useState} from 'react';
import {Card} from 'primereact/card';
import {Conf} from '../../../shared/components/Conf';
import {api} from '../../../shared/services/api';
export default function RedePage(){
 const[fw,setFw]=useState(null);
 useEffect(()=>{api('/api/firewall/status').then(setFw).catch(()=>{})},[]);
 return <div className="grid"><Card title="Regras ativas"><div className="metric">{fw?.rulesActive??'?'}</div><small className="muted">sincronizadas do runtime</small></Card><Conf rows={[['Modelo','VLANs + firewall + NAT'],['Tecido','NetworkManager + iptables']]}/></div>;
}
