import React from 'react';
import {Card} from 'primereact/card';
export function Conf({rows}){
 return <Card title="Configuração"><table className="kv"><tbody>{rows.map(([k,v])=><tr key={k}><td>{k}</td><td><b>{v}</b></td></tr>)}</tbody></table></Card>;
}
