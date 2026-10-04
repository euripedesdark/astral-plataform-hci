import React, {useCallback, useEffect, useState} from 'react';
import {Card} from 'primereact/card';
import {Button} from 'primereact/button';
import {InputText} from 'primereact/inputtext';
import {Dropdown} from 'primereact/dropdown';
import {Checkbox} from 'primereact/checkbox';
import {Message} from 'primereact/message';
import {Tag} from 'primereact/tag';
import {api} from '../../../shared/services/api';

/**
 * Espelho exato do record {@code com.astral.fabric.network.EnderecamentoRede}.
 * O nome do campo e' o contrato com a API: se divergir, o Jackson ignora o que
 * nao conhece e o servidor aplica um endereco que o operador nao digitou.
 */
interface InterfaceRede {
    nome: string;
    papel: 'WAN' | 'LAN' | '';
    enderecoCidr: string;
    gateway: string;
    servidorDhcp: boolean;
    dhcpInicio: string;
    dhcpFim: string;
}
interface EnderecamentoRede {
    metodo: 'manual' | 'auto';
    interfaces: InterfaceRede[];
    dns: string[];
    dominioDhcp: string;
    protegerResolvConf: boolean;
}
interface EstadoApi {
    addressing: EnderecamentoRede;
    interfaces: string[];
    observado: Record<string, unknown>;
}
interface Validacao {
    erros: string[];
    aprovado: boolean;
    plano?: string[];
    semMudanca?: boolean;
}
interface Intencao {
    intentId?: string;
    status?: string;
    plano?: string[];
    message?: string;
}

const vazia = (): InterfaceRede => ({
    nome: '', papel: 'LAN', enderecoCidr: '', gateway: '', servidorDhcp: false, dhcpInicio: '', dhcpFim: ''
});

/**
 * Formulario de Configuracao de Enderecamento de Rede.
 *
 * <p>Tres botoes com semanticas diferentes, e essa e' a parte importante:
 *
 * <ul>
 *   <li><b>Validar</b> -- POST /validar. Mostra o plano e NADA e' aplicado.</li>
 *   <li><b>Aplicar intenção</b> -- POST /api/v1/network/addressing. Publica a
 *       intencao e devolve um intentId; quem reconcilia e o ReconciliationEngine,
 *       nao esta tela.</li>
 *   <li><b>Limpar</b> -- volta ao estado vazio, sem tocar no servidor.</li>
 * </ul>
 *
 * <p>Nao existe "salvar sem validar": o servidor revalida por cima (422), mas a
 * tela mostra os erros ANTES do operador pagar o custo de um 422. E "Aplicar"
 * pede confirmacao porque e' o botao que pode tirar o host da rede.
 */
export default function EnderecamentoPage() {
    const [estado, setEstado] = useState<EstadoApi | null>(null);
    const [desejo, setDesejo] = useState<EnderecamentoRede | null>(null);
    const [dnsTexto, setDnsTexto] = useState('');
    const [validacao, setValidacao] = useState<Validacao | null>(null);
    const [intencao, setIntencao] = useState<Intencao | null>(null);
    const [erro, setErro] = useState('');
    const [busy, setBusy] = useState(false);

    const carrega = useCallback(async () => {
        try {
            const e = await api<EstadoApi>('/api/v1/network/addressing');
            setEstado(e);
            const a = e.addressing;
            setDesejo({
                metodo: a.metodo || 'manual',
                interfaces: (a.interfaces || []).map(i => ({...i})),
                dns: a.dns || [],
                dominioDhcp: a.dominioDhcp || '',
                protegerResolvConf: !!a.protegerResolvConf
            });
            setDnsTexto((a.dns || []).join(', '));
            setErro('');
        } catch (ex) {
            setErro(ex instanceof Error ? ex.message : String(ex));
        }
    }, []);

    useEffect(() => { void carrega(); }, [carrega]);

    function muta(fn: (d: EnderecamentoRede) => EnderecamentoRede) {
        setDesejo(d => (d ? fn(d) : d));
        setValidacao(null);
    }

    function mutaInterface(idx: number, campo: keyof InterfaceRede, valor: string | boolean) {
        muta(d => {
            const interfaces = d.interfaces.map((i, k) => (k === idx ? {...i, [campo]: valor} : i));
            return {...d, interfaces};
        });
    }

    function montaPayload(): EnderecamentoRede {
        const d = desejo as EnderecamentoRede;
        const dns = dnsTexto.split(',').map(s => s.trim()).filter(Boolean);
        return {...d, dns};
    }

    async function validar() {
        setBusy(true); setErro(''); setIntencao(null);
        try {
            const r = await api<Validacao>('/api/v1/network/addressing/validar', {
                method: 'POST', body: JSON.stringify(montaPayload())
            });
            setValidacao(r);
        } catch (ex) {
            // 422 chega como erro com o corpo de erros dentro da mensagem quando
            // a API manda {erros:[...]}; guardamos o que veio para mostrar lista.
            setValidacao({erros: [ex instanceof Error ? ex.message : String(ex)], aprovado: false});
        } finally { setBusy(false); }
    }

    async function aplicar() {
        const plano = validacao?.plano ?? [];
        const confirmar = window.confirm(
            'Publicar a intenção de endereçamento?\n\n' +
            (plano.length ? plano.join('\n') : '(sem mudanças)') +
            '\n\nUm administrador vai reconciliar este host. Se ele estiver remoto,' +
            '\numa rede mal configurada pode derrubar o acesso.'
        );
        if (!confirmar) return;
        setBusy(true); setErro('');
        try {
            const r = await api<Intencao>('/api/v1/network/addressing', {
                method: 'POST', body: JSON.stringify(montaPayload())
            });
            setIntencao(r);
        } catch (ex) { setErro(ex instanceof Error ? ex.message : String(ex)); }
        finally { setBusy(false); }
    }

    if (!desejo) return <Card title="Endereçamento de Rede"><p className="muted">{erro || 'Carregando…'}</p></Card>;

    return (
        <div className="grid">
            <Card title="Desejado (declarativo)" className="col-12">
                {erro && <Message severity="error" text={erro} style={{display: 'block', marginBottom: 10}} />}

                <div className="flex flex-wrap gap-3 align-items-center" style={{marginBottom: 12}}>
                    <label className="muted">Método</label>
                    <Dropdown value={desejo.metodo}
                              options={[{label: 'manual (estático)', value: 'manual'}, {label: 'auto (DHCP)', value: 'auto'}]}
                              onChange={e => muta(d => ({...d, metodo: e.value}))} style={{width: 200}} />
                    <label className="muted">Domínio DHCP</label>
                    <InputText value={desejo.dominioDhcp} placeholder="srvcloud.cloud"
                               onChange={e => muta(d => ({...d, dominioDhcp: e.target.value}))} style={{width: 200}} />
                    <label>
                        <Checkbox checked={desejo.protegerResolvConf} inputId="prot"
                                  onChange={e => muta(d => ({...d, protegerResolvConf: !!e.checked}))} />
                        <span style={{marginLeft: 6}}>proteger /etc/resolv.conf</span>
                    </label>
                </div>

                <div className="flex flex-wrap gap-2 align-items-center" style={{marginBottom: 12}}>
                    <label className="muted" style={{width: 40}}>DNS</label>
                    <InputText value={dnsTexto} placeholder="1.1.1.1, 8.8.8.8" style={{minWidth: 320}}
                               onChange={e => { setDnsTexto(e.target.value); setValidacao(null); }} />
                    <span className="muted">separados por vírgula</span>
                </div>

                <h4 style={{margin: '14px 0 8px'}}>Interfaces</h4>
                <div className="flex flex-wrap gap-2 align-items-center" style={{marginBottom: 6}}>
                    <span className="muted" style={{width: 90}}>nome</span>
                    <span className="muted" style={{width: 90}}>papel</span>
                    <span className="muted" style={{width: 180}}>endereco/máscara</span>
                    <span className="muted" style={{width: 170}}>gateway</span>
                    <span className="muted" style={{width: 210}}>DHCP (início – fim)</span>
                </div>

                {desejo.interfaces.map((i, idx) => (
                    <div className="flex flex-wrap gap-2 align-items-center" key={idx} style={{marginBottom: 6}}>
                        <InputText value={i.nome} placeholder="ens192" style={{width: 90}}
                                   onChange={e => mutaInterface(idx, 'nome', e.target.value)} />
                        <Dropdown value={i.papel} options={[{label: 'WAN', value: 'WAN'}, {label: 'LAN', value: 'LAN'}]}
                                  onChange={e => mutaInterface(idx, 'papel', e.value)} style={{width: 90}} />
                        <InputText value={i.enderecoCidr} placeholder="192.168.2.10/24" style={{width: 180}}
                                   onChange={e => mutaInterface(idx, 'enderecoCidr', e.target.value)} />
                        <InputText value={i.gateway} placeholder="192.168.2.1" style={{width: 170}}
                                   onChange={e => mutaInterface(idx, 'gateway', e.target.value)} />
                        <label className="flex align-items-center" style={{width: 110}}>
                            <Checkbox checked={i.servidorDhcp}
                                      onChange={e => mutaInterface(idx, 'servidorDhcp', !!e.checked)} />
                            <span style={{marginLeft: 6}}>DHCP</span>
                        </label>
                        <InputText value={i.dhcpInicio} placeholder="192.168.2.100" style={{width: 130}}
                                   onChange={e => mutaInterface(idx, 'dhcpInicio', e.target.value)} />
                        <InputText value={i.dhcpFim} placeholder="192.168.2.200" style={{width: 130}}
                                   onChange={e => mutaInterface(idx, 'dhcpFim', e.target.value)} />
                        <Button icon="pi pi-trash" severity="danger" text size="small"
                                onClick={() => muta(d => ({...d, interfaces: d.interfaces.filter((_, k) => k !== idx)}))} />
                    </div>
                ))}

                <div className="flex gap-2" style={{marginTop: 10}}>
                    <Button label="Adicionar interface" icon="pi pi-plus" text size="small"
                            onClick={() => muta(d => ({...d, interfaces: [...d.interfaces, vazia()]}))} />
                    <Button label="Limpar" icon="pi pi-eraser" text size="small"
                            onClick={() => { setDesejo({metodo: 'manual', interfaces: [vazia()], dns: [], dominioDhcp: '', protegerResolvConf: true}); setDnsTexto(''); setValidacao(null); }} />
                </div>
            </Card>

            <Card title="Plano" className="col-12">
                <div className="flex gap-2" style={{marginBottom: 10}}>
                    <Button label="Validar (nada aplicado)" icon="pi pi-check-circle" onClick={validar} disabled={busy} />
                    <Button label="Aplicar intenção" icon="pi pi-send" severity="warning" onClick={aplicar}
                            disabled={busy || !validacao?.aprovado} />
                </div>

                {validacao?.aprovado === false && (validacao.erros || []).map((e, k) =>
                    <Message key={k} severity="error" text={e} style={{display: 'block', marginBottom: 6}} />)}

                {validacao?.aprovado && (
                    <div>
                        {validacao.semMudanca
                            ? <Tag value="sem mudança — o host já está assim" severity="info" />
                            : <ul style={{margin: 0, paddingLeft: 20}}>
                                {(validacao.plano || []).map((p, k) => <li key={k}>{p}</li>)}
                              </ul>}
                        <p className="muted" style={{marginTop: 8}}>
                            Validar custa zero: é a etapa de Validação do ciclo declarativo, com diff
                            calculado e nada executado. Só <b>Aplicar</b> publica a intenção.
                        </p>
                    </div>
                )}

                {intencao && (
                    <Message severity="success"
                             text={`intenção ${intencao.intentId} · ${intencao.status} — ${intencao.message ?? ''}`}
                             style={{display: 'block', marginTop: 8}} />
                )}
            </Card>

            <Card title="Observado (o que o sistema tem hoje)" className="col-12">
                <pre className="muted" style={{whiteSpace: 'pre-wrap', margin: 0, fontSize: 12}}>
                    {JSON.stringify(estado?.observado ?? {}, null, 2)}
                </pre>
            </Card>
        </div>
    );
}
