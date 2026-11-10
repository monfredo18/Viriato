const TOKEN_KEY = 'viriato.token'

export const tokenStore = {
  get: () => localStorage.getItem(TOKEN_KEY),
  set: (t: string) => localStorage.setItem(TOKEN_KEY, t),
  clear: () => localStorage.removeItem(TOKEN_KEY),
}

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message)
  }
}

let onUnauthorized: () => void = () => {}
export const setOnUnauthorized = (fn: () => void) => (onUnauthorized = fn)

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  const token = tokenStore.get()
  if (token) headers.set('Authorization', `Bearer ${token}`)
  if (init.body && !(init.body instanceof FormData)) headers.set('Content-Type', 'application/json')

  const res = await fetch(`/api${path}`, { ...init, headers })
  if (res.status === 401 && token) onUnauthorized()
  if (!res.ok) {
    let msg = `Erro ${res.status}`
    try {
      msg = (await res.json()).erro ?? msg
    } catch {
      /* corpo vazio */
    }
    throw new ApiError(res.status, msg)
  }
  if (res.status === 204) return undefined as T
  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}

const body = (data: unknown) => JSON.stringify(data)

export const api = {
  get: <T,>(p: string) => request<T>(p),
  post: <T,>(p: string, data?: unknown) => request<T>(p, { method: 'POST', body: data === undefined ? undefined : body(data) }),
  put: <T,>(p: string, data: unknown) => request<T>(p, { method: 'PUT', body: body(data) }),
  patch: <T,>(p: string, data: unknown) => request<T>(p, { method: 'PATCH', body: body(data) }),
  upload: <T,>(p: string, form: FormData) => request<T>(p, { method: 'POST', body: form }),
  del: (p: string) => request<void>(p, { method: 'DELETE' }),
  /** Download autenticado: baixa como blob e dispara o salvamento. */
  async download(p: string, nome: string) {
    const res = await fetch(`/api${p}`, { headers: { Authorization: `Bearer ${tokenStore.get()}` } })
    if (!res.ok) throw new ApiError(res.status, 'Falha ao baixar arquivo')
    const url = URL.createObjectURL(await res.blob())
    const a = document.createElement('a')
    a.href = url
    a.download = nome
    a.click()
    URL.revokeObjectURL(url)
  },
}

// ---- tipos ----
export type Papel = 'ADMIN' | 'GESTOR' | 'USUARIO'
export type Status = 'ABERTO' | 'CONCLUIDO' | 'CANCELADO'

export interface Usuario { id: number; nome: string; email: string; papel: Papel; ativo: boolean }
export interface Sessao { token: string; usuario: Usuario; empresa: string }
export interface Etapa { id: number; nome: string; ordem: number; prazoDias: number | null; inicial: boolean; finalEtapa: boolean }
export interface Fluxo { id: number; nome: string; ativo: boolean; etapas: Etapa[]; transicoes: { de: number; para: number }[] }
export interface Processo {
  id: number; numero: string; titulo: string; descricao: string | null
  fluxoId: number; fluxoNome: string; etapaAtualId: number; etapaAtual: string; etapaFinal: boolean
  responsavelId: number | null; responsavelNome: string | null
  status: Status; prazoEtapa: string | null; atrasado: boolean
  camposExtras: Record<string, unknown>; criadoEm: string; atualizadoEm: string
}
export interface Historico { id: number; acao: string; usuarioNome: string | null; deEtapa: string | null; paraEtapa: string | null; comentario: string | null; criadoEm: string }
export interface Detalhe { processo: Processo; proximasEtapas: Etapa[]; historico: Historico[] }
export interface Pagina<T> { content: T[]; totalPages: number; number: number; totalElements: number }
export interface Documento { id: number; nome: string; contentType: string; tamanho: number; criadoEm: string }
export interface Notificacao { id: number; titulo: string; mensagem: string | null; processoId: number | null; lida: boolean; criadoEm: string }
