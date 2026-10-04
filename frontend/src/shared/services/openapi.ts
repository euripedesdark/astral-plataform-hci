/**
 * Consumidor do documento OpenAPI 3 gerado pelo springdoc (`/v3/api-docs`).
 *
 * <p>Por que a UI le a especificacao em vez de guardar os paths na mao: o
 * contrato passa a ser UMA fonte (a anotacao do controller). Se um endpoint
 * mudar de `/api/v1/acl/rules` para `/api/v1/acl/policies`, a tela mostra o
 * que a API realmente expoe -- e a divergencia aparece na hora, no navegador,
 * e nao em producao quando alguem chama o path antigo e le 404.
 *
 * <p>E tambem a prova documental de que a API existe: qualquer pessoa abre a
 * tela de ACL e ve a lista de operacoes que a pagina esta usando.
 */

/** Tipos minimos do OpenAPI 3 que este modulo le. */
export interface OperacaoOpenApi {
    metodo: string;
    path: string;
    summary: string;
    tags: string[];
}
interface DocumentoOpenApi {
    openapi?: string;
    info?: { title?: string; version?: string };
    paths?: Record<string, Record<string, {
        summary?: string;
        tags?: string[];
        security?: unknown[];
    }>>;
}

const METODOS = ['get', 'post', 'put', 'patch', 'delete'];

let cache: OperacaoOpenApi[] | null = null;

/** Busca a especificacao e devolve as operacoes, ja' ordenadas por path. */
export async function operacoes(filtro?: string): Promise<OperacaoOpenApi[]> {
    if (!cache) {
        const r = await fetch('/v3/api-docs', {credentials: 'same-origin'});
        if (!r.ok) throw new Error(`OpenAPI indisponível (${r.status})`);
        const doc = await r.json() as DocumentoOpenApi;
        const ops: OperacaoOpenApi[] = [];
        for (const [path, metodos] of Object.entries(doc.paths ?? {})) {
            for (const m of METODOS) {
                const op = metodos[m];
                if (!op) continue;
                ops.push({metodo: m.toUpperCase(), path, summary: op.summary ?? '', tags: op.tags ?? []});
            }
        }
        cache = ops.sort((a, b) => a.path.localeCompare(b.path) || a.metodo.localeCompare(b.metodo));
    }
    if (!filtro) return cache;
    const f = filtro.toLowerCase();
    return cache.filter(o => o.path.toLowerCase().includes(f) || o.summary.toLowerCase().includes(f));
}

/** Informacao do documento (titulo + versao) para o rodape do painel. */
export async function infoDocumento(): Promise<{ titulo: string; versao: string; openapi: string }> {
    const r = await fetch('/v3/api-docs', {credentials: 'same-origin'});
    if (!r.ok) throw new Error(`OpenAPI indisponível (${r.status})`);
    const d = await r.json() as DocumentoOpenApi;
    return {
        titulo: d.info?.title ?? 'API',
        versao: d.info?.version ?? '—',
        openapi: d.openapi ?? '3.x'
    };
}

/** Limpa o cache em memoria (usado quando o operador pede recarregar). */
export function limpaCacheOpenApi(): void {
    cache = null;
}
