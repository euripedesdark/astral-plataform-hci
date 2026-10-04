/**
 * Cliente HTTP da UI.
 *
 * <p>Um ponto so' para todas as chamadas, de proposito: autenticacao de
 * sessao, tratamento de 401 e a leitura do corpo sao uma decisao unica, nao
 * quinze. Se cada tela decidir o que fazer com 401, uma tela eventualmente
 * vai decidir errado.
 *
 * @param options igual a {@link RequestInit} do fetch -- o corpo em JSON e' 
 *        serializado pelo chamador, o Content-Type e' daqui.
 */
export async function api<T = unknown>(path: string, options: RequestInit = {}): Promise<T> {
  // Ordem importa: os headers calculados vem DEPOIS do spread, senao um
  // options.headers do chamador apagaria o Content-Type e o servidor
  // rejeitaria o JSON como texto sem tipo.
  const r = await fetch(path, {
    ...options,
    credentials: 'same-origin',
    headers: {
      ...(options.body ? { 'Content-Type': 'application/json' } : {}),
      ...(options.headers || {})
    }
  });
  if (r.status === 401) throw new Error('Sessão expirada');
  const t = await r.text();
  let d: unknown = null;
  try { d = t ? JSON.parse(t) : null; } catch { d = t; }
  if (!r.ok) {
    const msg = (d as { message?: string } | null)?.message;
    throw new Error(msg || r.statusText || 'erro');
  }
  return d as T;
}

export interface Credencial { token?: string; authenticated?: boolean }
export interface Usuario { username: string; authorities?: { authority: string }[] }

export const login = (username: string, password: string) =>
  api<Credencial>('/api/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) });
export const me = () => api<Usuario>('/api/auth/me');
export const logout = () => api<void>('/api/auth/logout', { method: 'POST' });
