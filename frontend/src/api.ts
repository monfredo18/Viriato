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
    if (!res.ok) {
      let msg = 'Falha ao baixar arquivo'
      try { msg = (await res.json()).erro ?? msg } catch { /* sem corpo */ }
      throw new ApiError(res.status, msg)
    }
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

export interface PendenciasUsuario { nome: string; abertos: number; atrasados: number; concluidos: number; cancelados: number }
export interface Usuario { id: number; nome: string; papel: Papel; ativo: boolean; setorId: number | null; setorNome: string | null; deveTrocarSenha: boolean }
export const ROTULO_PAPEL: Record<Papel, string> = { ADMIN: 'Administrador', GESTOR: 'Gestor', USUARIO: 'Usuário' }
export interface Cliente { id: number; numero: string; nome: string; ativo: boolean }
export interface EntregaResumo { id: number; nome: string; ativo: boolean }
export interface Setor { id: number; nome: string; ativo: boolean; entregas: EntregaResumo[] }
export interface Entrega { id: number; nome: string; fluxoId: number; fluxoNome: string; ativo: boolean }
export interface Sessao { token: string; usuario: Usuario; empresa: string }
export type TipoRequisito = 'DOCUMENTO' | 'CONFIRMACAO'
export interface Requisito { id: number; tipo: TipoRequisito; descricao: string }
export interface RequisitoStatus extends Requisito { cumprido: boolean; cumpridoPor: string | null; cumpridoEm: string | null; documentoId: number | null; documentoNome: string | null }
export interface Etapa { id: number; nome: string; ordem: number; prazoDias: number | null; inicial: boolean; finalEtapa: boolean; requisitos: Requisito[]; papelMinimo: Papel }
export interface Fluxo { id: number; nome: string; ativo: boolean; etapas: Etapa[]; transicoes: { de: number; para: number }[] }
export interface Processo {
  id: number; numero: string; titulo: string; descricao: string | null
  clienteId: number | null; clienteNome: string | null; entregaId: number | null; entregaNome: string | null
  setorId: number | null; setorNome: string | null
  fluxoId: number | null; fluxoNome: string | null; etapaAtualId: number | null; etapaAtual: string | null; etapaFinal: boolean
  responsavelId: number | null; responsavelNome: string | null
  status: Status; prazoEtapa: string | null; atrasado: boolean
  camposExtras: Record<string, unknown>; criadoEm: string; atualizadoEm: string
}
export interface Historico { id: number; acao: string; usuarioNome: string | null; deEtapa: string | null; paraEtapa: string | null; comentario: string | null; criadoEm: string }
export type StatusAprovacao = 'PENDENTE' | 'APROVADA' | 'RECUSADA' | 'CANCELADA'
export interface Aprovacao {
  id: number; etapaDestinoId: number; etapaDestino: string | null; papelMinimo: Papel
  solicitadoPorId: number | null; solicitadoPor: string | null; solicitadoEm: string; comentario: string | null
  status: StatusAprovacao; decididoPor: string | null; decididoEm: string | null; motivo: string | null
}
export interface Detalhe { processo: Processo; proximasEtapas: Etapa[]; historico: Historico[]; requisitos: RequisitoStatus[]; etapaOrdem: number | null; aprovacoes: Aprovacao[] }
/** Administrador atende gestor, que atende usuário (mesma regra do servidor). */
export const NIVEL_PAPEL: Record<Papel, number> = { ADMIN: 2, GESTOR: 1, USUARIO: 0 }
export const ROTULO_QUEM_MOVE: Record<Papel, string> = { USUARIO: 'Qualquer responsável', GESTOR: 'Somente gestor ou administrador', ADMIN: 'Somente administrador' }
export interface Pagina<T> { content: T[]; totalPages: number; number: number; totalElements: number }
export interface Documento { id: number; nome: string; contentType: string; tamanho: number; criadoEm: string; requisitoId: number | null; requisito: string | null; automatico: boolean }
export interface PainelResumo { abertos: number; atrasados: number; concluidos: number; cancelados: number; tempoMedioConclusaoDias: number | null }
export interface PainelSetor { setorId: number | null; setor: string; abertos: number; atrasados: number; concluidos: number; cancelados: number }
export interface PainelEtapa { fluxo: string; etapa: string; prazoDias: number | null; passagens: number; mediaHoras: number; foraDoPrazo: number }
export interface PainelFuncionario { usuarioId: number; nome: string; setor: string | null; abertos: number; atrasados: number; concluidos: number }
export interface Painel {
  escopo: string; todosOsSetores: boolean; periodoDias: number; geradoEm: string
  resumo: PainelResumo; setores: PainelSetor[]; etapas: PainelEtapa[]; funcionarios: PainelFuncionario[]
}
export type TipoAutomacao = 'GERAR_DOCUMENTO' | 'WEBHOOK'
export type EventoAutomacao = 'ENTRAR_ETAPA' | 'CANCELADO'
export interface RegraAutomacao {
  id: number; fluxoId: number; etapaId: number | null; etapaNome: string | null; evento: EventoAutomacao; tipo: TipoAutomacao; ativo: boolean
  url: string | null; temSegredo: boolean
}
export interface ExecucaoAutomacao { id: number; tipo: TipoAutomacao; evento: EventoAutomacao; etapa: string | null; status: 'OK' | 'FALHA'; mensagem: string | null; executadoEm: string }
export const ROTULO_AUTOMACAO: Record<TipoAutomacao, string> = {
  GERAR_DOCUMENTO: 'Gerar a ficha do processo (PDF)', WEBHOOK: 'Chamar outro sistema (webhook)',
}
export interface Notificacao { id: number; titulo: string; mensagem: string | null; empresaNome: string | null; entregaNome: string | null; autorNome: string | null; processoId: number | null; lida: boolean; criadoEm: string }
export interface MensagemAjuda { id: number; autorNome: string; deAdmin: boolean; texto: string; criadoEm: string }
export interface ConversaAjuda { id: number; pessoa: string; setor: string | null; ultimaTexto: string | null; ultimaDeAdmin: boolean; atualizadoEm: string; naoLidas: number }
export interface ConversaAjudaAberta { id: number; pessoa: string; setor: string | null; mensagens: MensagemAjuda[] }
