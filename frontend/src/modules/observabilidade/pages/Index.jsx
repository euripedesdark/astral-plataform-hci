import React from 'react';
import {Card} from 'primereact/card';
import {Conf} from '../../../shared/components/Conf';
export default function ObsPage(){return <div className="grid"><Conf rows={[['Logs','Graylog GELF 12201/udp'],['Metadados','Mongo dedicado'],['Health','/actuator/health na 8081']]}/></div>;}
