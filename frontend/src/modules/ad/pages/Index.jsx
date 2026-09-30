import React from 'react';
import {Card} from 'primereact/card';
import {Conf} from '../../../shared/components/Conf';
export default function AdPage({user}){return <div className="grid"><Card title="Sessão"><div className="metric">{user?.username||'—'}</div><p className="muted">{(user?.authorities||[]).map(a=>a.authority).join(', ')}</p></Card><Conf rows={[['Protocolo','LDAPS 636'],['Base','DC=srvcloud,DC=cloud'],['Grupo admin','Domain Admins'],['Fallback','PostgreSQL local']]}/></div>;}
