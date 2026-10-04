import React from 'react';
import {Card} from 'primereact/card';
function Conf({rows}){
 return <Card title="Configuração"><table className="kv">{rows.map(([k,v])=><tr key={k}><td>{k}</td><td><b>{v}</b></td></tr>)}</table></Card>;
}
export function TS(){return <div className="grid"><Conf rows={[['Escuta','0.0.0.0:3128'],['Auth','authproxy → 127.0.0.1:8091'],['Modo','forward (proxy explícito)'],['Remap','não requerido']]}/><Card title="Fluxo"><p className="muted">Cliente → ATS 3128 → 8091 valida no AD → origem. Sem credencial: 401.</p></Card></div>;}
export function AD({user}){return <div className="grid"><Card title="Sessão"><div className="metric">{user?.username||'—'}</div><p className="muted">{(user?.authorities||[]).map(a=>a.authority).join(', ')}</p></Card><Conf rows={[['Protocolo','LDAPS 636'],['Base','DC=srvcloud,DC=cloud'],['Grupo admin','Domain Admins'],['Fallback','PostgreSQL local']]}/></div>;}
export function PG(){return <div className="grid"><Conf rows={[['Host','127.0.0.1:5432'],['Banco','astral'],['Role','astral'],['Auth','mTLS verify-ca']]}/><Card title="Nota"><p className="muted">Certificados em /etc/astral/certs. Policy do pg_hba: trust local, cert no hostssl do ERP.</p></Card></div>;}
export function OBS(){return <div className="grid"><Conf rows={[['Logs','Graylog GELF 12201/udp'],['Metadados','Mongo dedicado'],['Health','/actuator/health na 8081']]}/></div>;}
export function COMP(){return <div className="grid"><Conf rows={[['Virtualização','KVM / libvirt'],['Contêineres','OCI'],['Regra','mesmas regras de firewall e identidade']]}/></div>;}
export function REDE({fw}){return <div className="grid"><Card title="Regras ativas"><div className="metric">{fw?.rulesActive??'?'}</div><small className="muted">sincronizadas do runtime</small></Card><Conf rows={[['Modelo','VLANs + firewall + NAT'],['Tecido','NetworkManager + iptables']]}/></div>;}
export function TERM(){return <div className="grid"><Card title="Terminal"><p className="muted">Sem backend web. Acesso via SSH na 22 com usuário do AD ou local.</p><code>ssh euripedes@192.168.2.10</code></Card></div>;}
