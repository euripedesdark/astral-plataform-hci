import React from 'react';
import {Card} from 'primereact/card';
import {Conf} from '../../../shared/components/Conf';
export default function ProxyPage(){return <div className="grid"><Conf rows={[['Escuta','0.0.0.0:3128'],['Auth','authproxy → 127.0.0.1:8091'],['Modo','forward (proxy explícito)'],['Remap','não requerido']]}/><Card title="Fluxo"><p className="muted">Cliente → ATS 3128 → 8091 valida no AD → origem. Sem credencial: 401.</p></Card></div>;}
