import React from 'react';
import {Card} from 'primereact/card';
import {Conf} from '../../../shared/components/Conf';
export default function PostgresPage(){return <div className="grid"><Conf rows={[['Host','127.0.0.1:5432'],['Banco','astral'],['Role','astral'],['Auth','mTLS verify-ca']]}/><Card title="Nota"><p className="muted">Certificados em /etc/astral/certs.</p></Card></div>;}
