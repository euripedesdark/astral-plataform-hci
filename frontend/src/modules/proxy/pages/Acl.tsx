import React, {useCallback, useEffect, useState} from 'react';
import {Card} from 'primereact/card';
import {Button} from 'primereact/button';
import {InputText} from 'primereact/inputtext';
import {Dropdown} from 'primereact/dropdown';
import {DataTable} from 'primereact/datatable';
import {Column} from 'primereact/column';
import {TabView, TabPanel} from 'primereact/tabview';
import {Tag} from 'primereact/tag';
import {Message} from 'primereact/message';
import {api} from '../../../shared/services/api';
import {operacoes, type OperacaoOpenApi} from '../../../shared/services/openapi';

/** Linha de categoria como a API de /api/v1/acl/categories devolve. */
interface Categoria { id: number; code: string; name: string; description: string; blockedByDefault: boolean; source: string }
interface Dominio { id: number; pattern: string; categoryId: number; categoryCode: string; categoryName: string; source: string }
interface Excecao { id: number; pattern: string; adGroup: string; action: string; reason: string; enabled: boolean; createdBy: string }
interface Regra { id: number; categoryId: number; categoryCode: string; categoryName: string; adGroup: string; action: string; priority: number; enabled: boolean; justification: string }
interface Stats { [k: string]: string | number }
interface Tentativa { status?: number; origem?: string; motivo?: string; categoria?: string; grupo?: string; host?: string }

const ACAO_DROP = [{label: 'ALLOW (libera)', value: 'ALLOW'}, {label: 'DENY (bloqueia)', value: 'DENY'}];
const EXCECAO_DROP = [
    {label: 'ALLOW (libera sempre)', value: 'ALLOW'},
    {label: 'DENY (bloqueia sempre)', value: 'DENY'},
    {label: 'BYPASS (pula o motor e o cache)', value: 'BYPASS'}
];

/**
 * Gestao da politica de navegacao (PASSO A/B/C do motor de ACL).
 *
 * <p>Esta tela e' cliente puro dos endpoints de /api/v1/acl: ela nao decide
 * nada, mostra o que o motor decide e devolve o que o operador escreve. Cada
 * escrita ja' invalida cache no servidor (AclAdminController), entao nao ha
 * "aplica e espera 15 minutos" -- politica que so' vale daqui a pouco nao e'
 * politica.
 *
 * <p>O painel "Testar decisao" e' o que faz a tela se pagar num incidente:
 * digitar o dominio e ver 200/401/403 com a origem (PASSO_A, CACHE, PASSO_C,
 * PADRAO) responde em segundos a pergunta "por que isso foi bloqueado?".
 */
export default function AclPage() {
    const [stats, setStats] = useState<Stats>({});
    const [categorias, setCategorias] = useState<Categoria[]>([]);
    const [dominios, setDominios] = useState<Dominio[]>([]);
    const [excecoes, setExcecoes] = useState<Excecao[]>([]);
    const [regras, setRegras] = useState<Regra[]>([]);
    const [erro, setErro] = useState('');
    const [aviso, setAviso] = useState('');
    const [busy, setBusy] = useState(false);

    // ---- formulários ----
    const [novoDominio, setNovoDominio] = useState('');
    const [catDominio, setCatDominio] = useState<number | null>(null);
    const [novaExcecao, setNovaExcecao] = useState({pattern: '', adGroup: '', action: 'ALLOW', reason: ''});
    const [novaRegra, setNovaRegra] = useState({categoryId: 0, adGroup: '', action: 'DENY', priority: 100, justification: ''});

    // ---- teste de decisão ----
    const [testeHost, setTesteHost] = useState('');
    const [tentativa, setTentativa] = useState<Tentativa | null>(null);

    // ---- contrato OpenAPI ----
    const [contrato, setContrato] = useState<OperacaoOpenApi[]>([]);
    const [contratoErro, setContratoErro] = useState('');

    const carrega = useCallback(async () => {
        try {
            const [s, c, d, e, r] = await Promise.all([
                api<Stats>('/api/v1/acl/stats'),
                api<Categoria[]>('/api/v1/acl/categories'),
                api<Dominio[]>('/api/v1/acl/domains'),
                api<Excecao[]>('/api/v1/acl/overrides'),
                api<Regra[]>('/api/v1/acl/rules')
            ]);
            setStats(s); setCategorias(c); setDominios(d); setExcecoes(e); setRegras(r);
            setErro('');
        } catch (ex) {
            setErro(ex instanceof Error ? ex.message : String(ex));
        }
    }, []);

    useEffect(() => { void carrega(); }, [carrega]);

    // O contrato vem do proprio springdoc: se a API mudar, esta lista muda junto.
    useEffect(() => {
        operacoes('/api/v1/acl')
            .then(setContrato)
            .catch(ex => setContratoErro(ex instanceof Error ? ex.message : String(ex)));
    }, []);

    function notifica(msg: string) {
        setAviso(msg);
        window.setTimeout(() => setAviso(''), 6000);
    }

    async function executa(acao: () => Promise<string>) {
        setBusy(true); setErro('');
        try { notifica(await acao()); await carrega(); }
        catch (ex) { setErro(ex instanceof Error ? ex.message : String(ex)); }
        finally { setBusy(false); }
    }

    const criaDominio = () => executa(async () => {
        if (!novoDominio || !catDominio) throw new Error('informe o padrao e a categoria');
        await api('/api/v1/acl/domains', {method: 'POST', body: JSON.stringify({pattern: novoDominio, categoryId: catDominio, source: 'manual'})});
        setNovoDominio('');
        return `dominio ${novoDominio} cadastrado`;
    });

    const criaExcecao = () => executa(async () => {
        if (!novaExcecao.pattern) throw new Error('informe o padrao da excecao');
        await api('/api/v1/acl/overrides', {method: 'POST', body: JSON.stringify(novaExcecao)});
        setNovaExcecao({pattern: '', adGroup: '', action: 'ALLOW', reason: ''});
        return 'excecao criada (PASSO A passa a valer na proxima requisicao)';
    });

    const criaRegra = () => executa(async () => {
        if (!novaRegra.categoryId || !novaRegra.adGroup) throw new Error('escolha categoria e grupo');
        await api('/api/v1/acl/rules', {method: 'POST', body: JSON.stringify(novaRegra)});
        setNovaRegra({categoryId: 0, adGroup: '', action: 'DENY', priority: 100, justification: ''});
        return 'regra criada';
    });

    const remove = (rota: string, id: number, oque: string) => executa(async () => {
        await api(`/api/v1/acl/${rota}/${id}`, {method: 'DELETE'});
        return `${oque} removido`;
    });

    const invalidaCache = () => executa(async () => {
        await api('/api/v1/acl/cache/invalidate', {method: 'POST'});
        return 'cache e indice de excecoes invalidados';
    });

    const testa = async () => {
        if (!testeHost) return;
        setBusy(true);
        try {
            const r = await fetch('/api/v1/acl/check', {headers: {'X-Original-Host': testeHost, 'X-Original-URI': '/'}});
            setTentativa(await r.json() as Tentativa);
        } catch { setTentativa({status: 0, motivo: 'motor indisponivel'}); }
        finally { setBusy(false); }
    };

    const acaoTag = (v: string) =>
        <Tag value={v} severity={v === 'ALLOW' ? 'success' : 'danger'} />;

    return (
        <div className="grid">
            <Card title="Estado do motor" className="col-12">
                <div className="flex flex-wrap gap-2 align-items-center">
                    <Tag value={`permitidas ${Number(stats.permitidas ?? 0)}`} severity="success" />
                    <Tag value={`negadas ${Number(stats.negadas ?? 0)}`} severity="danger" />
                    <Tag value={`sem identidade ${Number(stats.semIdentidade ?? 0)}`} severity="warning" />
                    <Tag value={`falhas de infra ${Number(stats.falhasInfra ?? 0)}`} severity={Number(stats.falhasInfra ?? 0) > 0 ? 'danger' : 'info'} />
                    <span className="muted" style={{marginLeft: 8}}>{String(stats.cache ?? '')}</span>
                    <Button label="Invalidar cache" icon="pi pi-refresh" size="small" outlined onClick={invalidaCache} disabled={busy} />
                    <Button label="Recarregar" icon="pi pi-sync" size="small" text onClick={carrega} disabled={busy} />
                </div>
                {erro && <Message severity="error" text={erro} style={{display: 'block', marginTop: 10}} />}
                {aviso && <Message severity="success" text={aviso} style={{display: 'block', marginTop: 10}} />}
            </Card>

            <Card title="Testar decisão" className="col-12">
                <div className="flex flex-wrap gap-2 align-items-center">
                    <InputText value={testeHost} placeholder="ex.: facebook.com" onChange={e => setTesteHost(e.target.value)}
                               onKeyDown={e => e.key === 'Enter' && void testa()} />
                    <Button label="Consultar motor" icon="pi pi-search" onClick={testa} disabled={busy} />
                    {tentativa && (
                        <span className="flex align-items-center gap-2">
                            <Tag value={`${tentativa.status ?? '?'}`} severity={(tentativa.status === 200 ? 'success' : 'danger')} />
                            <b>{tentativa.origem}</b>
                            <span className="muted">{tentativa.categoria ?? 'sem categoria'} · {tentativa.grupo ?? '-'}</span>
                            <span className="muted">{tentativa.motivo}</span>
                        </span>
                    )}
                </div>
                <p className="muted" style={{marginBottom: 0}}>
                    A mesma consulta do Nginx: 200 libera, 403 nega, 401 nao ha quem avalie.
                    A coluna <b>origem</b> diz em qual passo a decisao foi tomada.
                </p>
            </Card>

            <Card title="Contrato OpenAPI 3" className="col-12"
                  subTitle={<a href="/swagger-ui.html" target="_blank" rel="noreferrer">swagger-ui</a>}>
                {contratoErro
                    ? <Message severity="warn" text={contratoErro} />
                    : <div className="flex flex-wrap gap-2">
                        {contrato.map(o => (
                            <Tag key={`${o.metodo} ${o.path}`}
                                 value={`${o.metodo} ${o.path.replace('/api/v1/acl', '') || '/'}`}
                                 severity={o.metodo === 'GET' ? 'info' : o.metodo === 'DELETE' ? 'danger' : 'success'}
                                 title={o.summary} />
                        ))}
                      </div>}
                <p className="muted" style={{marginBottom: 0}}>
                    Lido de <code>/v3/api-docs</code> nesta montagem: a mesma especificacao que o
                    swagger-ui serve, e a prova de que a pagina so' usa endpoints que existem.
                </p>
            </Card>

            <div className="col-12">
                <TabView>
                    <TabPanel header={`Domínios (${dominios.length})`}>
                        <div className="flex flex-wrap gap-2 align-items-center" style={{marginBottom: 10}}>
                            <InputText value={novoDominio} placeholder="facebook.com ou *.gov.br"
                                       onChange={e => setNovoDominio(e.target.value)} />
                            <Dropdown value={catDominio} options={categorias.map(c => ({label: `${c.code} — ${c.name}`, value: c.id}))}
                                      placeholder="categoria" onChange={e => setCatDominio(e.value as number)} style={{minWidth: 260}} />
                            <Button label="Cadastrar" icon="pi pi-plus" onClick={criaDominio} disabled={busy} />
                        </div>
                        <DataTable value={dominios} size="small" stripedRows rows={10} emptyMessage="nenhum dominio cadastrado">
                            <Column field="pattern" header="PADRÃO" />
                            <Column field="categoryCode" header="CATEGORIA" />
                            <Column field="source" header="ORIGEM" />
                            <Column header="" body={(r: Dominio) =>
                                <Button icon="pi pi-trash" size="small" severity="danger" text
                                        onClick={() => remove('domains', r.id, 'dominio')} />} />
                        </DataTable>
                    </TabPanel>

                    <TabPanel header={`Exceções (${excecoes.length})`}>
                        <div className="flex flex-wrap gap-2 align-items-center" style={{marginBottom: 10}}>
                            <InputText value={novaExcecao.pattern} placeholder="*.srvcloud.cloud"
                                       onChange={e => setNovaExcecao({...novaExcecao, pattern: e.target.value})} />
                            <InputText value={novaExcecao.adGroup} placeholder="grupo do AD (vazio = todo mundo)"
                                       onChange={e => setNovaExcecao({...novaExcecao, adGroup: e.target.value})} />
                            <Dropdown value={novaExcecao.action} options={EXCECAO_DROP}
                                      onChange={e => setNovaExcecao({...novaExcecao, action: e.value})} />
                            <InputText value={novaExcecao.reason} placeholder="justificativa"
                                       onChange={e => setNovaExcecao({...novaExcecao, reason: e.target.value})} />
                            <Button label="Criar exceção" icon="pi pi-plus" onClick={criaExcecao} disabled={busy} />
                        </div>
                        <DataTable value={excecoes} size="small" stripedRows rows={10} emptyMessage="nenhuma excecao">
                            <Column field="pattern" header="PADRÃO" />
                            <Column field="adGroup" header="GRUPO" />
                            <Column header="AÇÃO" body={(r: Excecao) => acaoTag(r.action)} />
                            <Column field="reason" header="MOTIVO" />
                            <Column field="createdBy" header="QUEM" />
                            <Column header="" body={(r: Excecao) =>
                                <Button icon="pi pi-trash" size="small" severity="danger" text
                                        onClick={() => remove('overrides', r.id, 'excecao')} />} />
                        </DataTable>
                        <p className="muted">
                            Excecao roda ANTES de tudo (PASSO A). <b>BYPASS</b> pula motor e cache -- e' a
                            chave para destravar um dominio sem esperar TTL.
                        </p>
                    </TabPanel>

                    <TabPanel header={`Regras (${regras.length})`}>
                        <div className="flex flex-wrap gap-2 align-items-center" style={{marginBottom: 10}}>
                            <Dropdown value={novaRegra.categoryId || null}
                                      options={categorias.map(c => ({label: `${c.code} — ${c.name}`, value: c.id}))}
                                      placeholder="categoria" onChange={e => setNovaRegra({...novaRegra, categoryId: e.value as number})}
                                      style={{minWidth: 260}} />
                            <InputText value={novaRegra.adGroup} placeholder="grupo do AD (ou *)"
                                       onChange={e => setNovaRegra({...novaRegra, adGroup: e.target.value})} />
                            <Dropdown value={novaRegra.action} options={ACAO_DROP}
                                      onChange={e => setNovaRegra({...novaRegra, action: e.value})} />
                            <InputText value={String(novaRegra.priority)} style={{width: 90}}
                                       onChange={e => setNovaRegra({...novaRegra, priority: Number(e.target.value) || 0})} />
                            <InputText value={novaRegra.justification} placeholder="justificativa"
                                       onChange={e => setNovaRegra({...novaRegra, justification: e.target.value})} />
                            <Button label="Criar regra" icon="pi pi-plus" onClick={criaRegra} disabled={busy} />
                        </div>
                        <DataTable value={regras} size="small" stripedRows rows={10} emptyMessage="nenhuma regra: tudo negado por padrao">
                            <Column field="categoryCode" header="CATEGORIA" />
                            <Column field="adGroup" header="GRUPO" />
                            <Column header="AÇÃO" body={(r: Regra) => acaoTag(r.action)} />
                            <Column field="priority" header="PRIOR." />
                            <Column field="justification" header="JUSTIFICATIVA" />
                            <Column header="" body={(r: Regra) =>
                                <Button icon="pi pi-trash" size="small" severity="danger" text
                                        onClick={() => remove('rules', r.id, 'regra')} />} />
                        </DataTable>
                        <p className="muted">
                            Menor prioridade primeiro. Par (categoria, grupo) e' unico: duas regras para o
                            mesmo par fariam a ordem de leitura decidir acesso.
                        </p>
                    </TabPanel>

                    <TabPanel header={`Categorias (${categorias.length})`}>
                        <DataTable value={categorias} size="small" stripedRows rows={10}>
                            <Column field="code" header="CODIGO" />
                            <Column field="name" header="NOME" />
                            <Column header="PADRAO" body={(c: Categoria) =>
                                <Tag value={c.blockedByDefault ? 'NEGADO' : 'LIBERADO'}
                                     severity={c.blockedByDefault ? 'danger' : 'success'} />} />
                            <Column field="source" header="ORIGEM" />
                        </DataTable>
                        <p className="muted">
                            Vocabulario semeado na subida (AclSeeder). A coluna PADRAO so' vale quando
                            <b> default-without-rule = BY_CATEGORY</b>; com o default DENY da casa, vale e' a regra.
                        </p>
                    </TabPanel>
                </TabView>
            </div>
        </div>
    );
}
