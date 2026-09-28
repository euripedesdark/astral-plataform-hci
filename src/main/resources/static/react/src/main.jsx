import React from 'react';
import {createRoot} from 'react-dom/client';
import 'primeicons/primeicons.css';
import 'primereact/resources/themes/lara-dark-blue/theme.css';
import 'primereact/resources/primereact.min.css';
import 'primeflex/primeflex.css';
import './styles.css';
import App from './App.jsx';
createRoot(document.getElementById('root')).render(<React.StrictMode><App/></React.StrictMode>);
