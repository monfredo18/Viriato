/**
 * A sessão fica em um cookie HttpOnly (o navegador o envia sozinho; o JavaScript não consegue lê-lo). O que a página
 * guarda é só o código anti-CSRF (cookie XSRF-TOKEN), que volta no cabeçalho X-XSRF-TOKEN nas chamadas que alteram dados.
 */
const csrf = () => document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.slice('XSRF-TOKEN='.length) ?? ''

export class ApiError extends Error {
  constructor(public status: number, message: string, public codigo?: string) {
    super(message)
  }
}

let onUnauthorized: () => void = () => {}
export const setOnUnauthorized = (fn: () => void) => (onUnauthorized = fn)

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if ((init.method ?? 'GET') !== 'GET') headers.set('X-XSRF-TOKEN', csrf())
  if (init.body && !(init.body instanceof FormData)) headers.set('Content-Type', 'application/json')

  const res = await fetch(`/api${path}`, { ...init, headers, credentials: 'same-origin' })
  if (res.status === 401 && !path.startsWith('/auth/')) onUnauthorized()
  if (!res.ok) {
    let msg = `Erro ${res.status}`
    let codigo: string | undefined
    try {
      const corpo = await res.json()
      msg = corpo.erro ?? msg
      codigo = corpo.codigo
    } catch {
      /* corpo vazio */
    }
    throw new ApiError(res.status, msg, codigo)
  }
  if (res.status === 204) return undefined as T
  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}

const body = (data: unknown) => JSON.stringify(data)

export const api = {
  get: <T,>(p: string) => request<T>(p),
  /** Lista paginada: o corpo é a página; o total vem nos cabeçalhos X-Total-Count e X-Total-Pages. */
  async pagina<T>(p: string, numero: number, tamanho: number): Promise<{ itens: T[]; total: number; paginas: number }> {
    const res = await fetch(`/api${p}${p.includes('?') ? '&' : '?'}pagina=${numero}&tamanho=${tamanho}`, { credentials: 'same-origin' })
    if (res.status === 401) onUnauthorized()
    if (!res.ok) throw new ApiError(res.status, `Erro ${res.status}`)
    return { itens: await res.json(), total: Number(res.headers.get('X-Total-Count') ?? 0), paginas: Number(res.headers.get('X-Total-Pages') ?? 1) }
  },
  post: <T,>(p: string, data?: unknown) => request<T>(p, { method: 'POST', body: data === undefined ? undefined : body(data) }),
  put: <T,>(p: string, data: unknown) => request<T>(p, { method: 'PUT', body: body(data) }),
  patch: <T,>(p: string, data: unknown) => request<T>(p, { method: 'PATCH', body: body(data) }),
  upload: <T,>(p: string, form: FormData) => request<T>(p, { method: 'POST', body: form }),
  del: (p: string) => request<void>(p, { method: 'DELETE' }),
  /** Imagem autenticada (foto de perfil): baixa e devolve um endereço local que o <img> consegue usar. */
  async imagem(p: string): Promise<string> {
    const res = await fetch(`/api${p}`, { credentials: 'same-origin' })
    if (!res.ok) throw new ApiError(res.status, 'Sem imagem')
    return URL.createObjectURL(await res.blob())
  },
  /** Download autenticado: baixa como blob e dispara o salvamento. */
  async download(p: string, nome: string) {
    const res = await fetch(`/api${p}`, { credentials: 'same-origin' })
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
export interface Usuario { id: number; nome: string; papel: Papel; ativo: boolean; setorId: number | null; setorNome: string | null; deveTrocarSenha: boolean; fotoVersao: number | null; bloqueado: boolean; doisFatores: boolean; email: string | null; deveAtivarDoisFatores: boolean }
export const ROTULO_PAPEL: Record<Papel, string> = { ADMIN: 'Administrador', GESTOR: 'Gestor', USUARIO: 'Usuário' }
export type Regime = 'MEI' | 'SIMPLES_NACIONAL' | 'LUCRO_PRESUMIDO' | 'LUCRO_REAL' | 'IMUNE_ISENTA'
export type StatusContratual = 'ONBOARDING' | 'ATIVO' | 'SUSPENSO' | 'OFFBOARDING' | 'ENCERRADO'
export const ROTULO_REGIME: Record<Regime, string> = { MEI: 'MEI', SIMPLES_NACIONAL: 'Simples Nacional', LUCRO_PRESUMIDO: 'Lucro Presumido', LUCRO_REAL: 'Lucro Real', IMUNE_ISENTA: 'Imune ou isenta' }
export const ROTULO_STATUS: Record<StatusContratual, string> = { ONBOARDING: 'Onboarding', ATIVO: 'Ativa', SUSPENSO: 'Suspensa', OFFBOARDING: 'Offboarding', ENCERRADO: 'Encerrada' }
export interface Cliente { id: number; numero: string; nome: string; ativo: boolean; cnpj: string | null; cnpjFormatado: string | null; regimeTributario: Regime | null; statusContratual: StatusContratual; uf: string | null }
export interface Cnae { codigo: string; descricao: string | null }
export interface FichaFiscal { cnpj: string | null; cnpjFormatado: string | null; razaoSocial: string | null; nomeFantasia: string | null; inscricaoEstadual: string | null; inscricaoMunicipal: string | null; cnaePrincipal: string | null; cnaePrincipalDescricao: string | null; cnaesSecundarios: Cnae[]; regimeTributario: Regime | null; uf: string | null; codigoMunicipioIbge: string | null; dataAberturaEmpresa: string | null; statusContratual: StatusContratual }
export type AreaContato = 'FISCAL' | 'CONTABIL' | 'DP' | 'FINANCEIRO'
export const ROTULO_AREA: Record<AreaContato, string> = { FISCAL: 'Fiscal', CONTABIL: 'Contábil', DP: 'DP / Folha', FINANCEIRO: 'Financeiro' }
export interface Socio { id: number; nome: string; cpf: string | null; percentual: number | null; responsavelLegal: boolean; email: string | null; telefone: string | null }
export interface ContatoCliente { id: number; nome: string; area: AreaContato; email: string | null; telefone: string | null; recebeGuias: boolean; recebeCobrancas: boolean }
export type SituacaoVencimento = 'VALIDO' | 'VENCENDO' | 'VENCIDO' | 'SEM_VALIDADE'
/** Certificado lido por um agente do computador de um usuário: só dados públicos (a chave privada nunca chega ao sistema). */
export interface Certificado { id: number; titular: string | null; cnpjCertificado: string | null; emissora: string | null; numeroSerie: string | null; validoDe: string | null; validoAte: string | null; situacao: SituacaoVencimento; diasParaVencer: number | null; presente: boolean; clienteId: number | null; clienteNome: string | null; vinculoManual: boolean; computadores: string[]; computadoresConectados: number }
export interface AgenteLocal { id: number; nome: string; versao: string | null; usuarioId: number; usuario: string | null; ultimoContato: string | null; conectado: boolean; certificados: number }
export interface CodigoAgente { codigo: string; expiraEm: string }
export type TipoProcuracao = 'ECAC' | 'SEFAZ' | 'PREFEITURA' | 'FGTS_CONECTIVIDADE' | 'OUTRO'
export const ROTULO_PROCURACAO: Record<TipoProcuracao, string> = { ECAC: 'e-CAC', SEFAZ: 'SEFAZ', PREFEITURA: 'Prefeitura', FGTS_CONECTIVIDADE: 'FGTS (Conectividade)', OUTRO: 'Outra' }
export interface Procuracao { id: number; tipo: TipoProcuracao; dataOutorga: string; dataVencimento: string | null; poderes: string | null; temComprovante: boolean; comprovanteNome: string | null; situacao: 'VIGENTE' | 'VENCENDO' | 'VENCIDA' | 'SEM_VENCIMENTO'; diasParaVencer: number | null; ativa: boolean }
export interface ItemChecklist { id: number; tipo: 'ONBOARDING' | 'OFFBOARDING'; ordem: number; descricao: string; concluido: boolean; concluidoPor: number | null; concluidoEm: string | null }
export interface Ficha { id: number; numero: string; nome: string; ativo: boolean; fiscal: FichaFiscal; socios: Socio[]; contatos: ContatoCliente[]; certificados: Certificado[]; procuracoes: Procuracao[]; checklist: ItemChecklist[] }
export interface ItemSaude { tipo: 'CERTIFICADO' | 'PROCURACAO'; itemId: number; empresaId: number; empresaNome: string; descricao: string; vencimento: string; dias: number; gravidade: 'CRITICA' | 'ALERTA' }
export interface Saude { resumo: { certificadosVencidos: number; certificadosAVencer: number; procuracoesVencidas: number; procuracoesAVencer: number; empresasSemCnpj: number; empresasSemRegime: number; empresasSemCertificado: number; onboardingEmAndamento: number; offboardingEmAndamento: number }; itens: ItemSaude[] }
interface EntregaResumo { id: number; nome: string; ativo: boolean }
export interface Setor { id: number; nome: string; ativo: boolean; entregas: EntregaResumo[] }
/** {@code setorDonoId} vazio = entrega geral (do administrador); com valor = exclusiva desse setor. */
export interface Entrega { id: number; nome: string; fluxoId: number; fluxoNome: string; ativo: boolean; setorDonoId: number | null; setorDonoNome: string | null; prazoPadrao: string | null }
export interface Sessao { token?: string | null; usuario: Usuario; empresa: string }
export type TipoRequisito = 'DOCUMENTO' | 'CONFIRMACAO'
export interface Requisito { id: number; tipo: TipoRequisito; descricao: string }
interface RequisitoStatus extends Requisito { cumprido: boolean; cumpridoPor: string | null; cumpridoEm: string | null; documentoId: number | null; documentoNome: string | null }
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
  competenciaAno: number | null; competenciaMes: number | null; obrigacaoModeloId: number | null
}
interface Historico { id: number; acao: string; usuarioNome: string | null; deEtapa: string | null; paraEtapa: string | null; comentario: string | null; criadoEm: string }
type StatusAprovacao = 'PENDENTE' | 'APROVADA' | 'RECUSADA' | 'CANCELADA'
interface Aprovacao {
  id: number; etapaDestinoId: number; etapaDestino: string | null; papelMinimo: Papel
  solicitadoPorId: number | null; solicitadoPor: string | null; solicitadoEm: string; comentario: string | null
  status: StatusAprovacao; decididoPor: string | null; decididoEm: string | null; motivo: string | null
}
export interface Detalhe { processo: Processo; proximasEtapas: Etapa[]; historico: Historico[]; requisitos: RequisitoStatus[]; etapaOrdem: number | null; aprovacoes: Aprovacao[]; dependencias: Dependencia[]; campos: CampoExtra[] }
/** Administrador atende gestor, que atende usuário (mesma regra do servidor). */
export const NIVEL_PAPEL: Record<Papel, number> = { ADMIN: 2, GESTOR: 1, USUARIO: 0 }
export const ROTULO_QUEM_MOVE: Record<Papel, string> = { USUARIO: 'Qualquer responsável', GESTOR: 'Somente gestor ou administrador', ADMIN: 'Somente administrador' }
export interface Pagina<T> { content: T[]; totalPages: number; number: number; totalElements: number }
export interface Documento { id: number; nome: string; contentType: string; tamanho: number; criadoEm: string; requisitoId: number | null; requisito: string | null; automatico: boolean; categoria?: string | null; guardaLegal?: boolean; arquivado?: boolean }
interface PainelResumo { abertos: number; atrasados: number; concluidos: number; cancelados: number; tempoMedioConclusaoDias: number | null }
interface PainelSetor { setorId: number | null; setor: string; abertos: number; atrasados: number; concluidos: number; cancelados: number }
export interface PainelEtapa { fluxo: string; etapa: string; prazoDias: number | null; passagens: number; mediaHoras: number; foraDoPrazo: number }
interface PainelFuncionario { usuarioId: number; nome: string; setor: string | null; abertos: number; atrasados: number; concluidos: number }
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
export interface ExecucaoAutomacao { id: number; tipo: TipoAutomacao; evento: EventoAutomacao; etapa: string | null; status: 'PENDENTE' | 'PROCESSANDO' | 'OK' | 'FALHA'; mensagem: string | null; executadoEm: string }
export const ROTULO_AUTOMACAO: Record<TipoAutomacao, string> = {
  GERAR_DOCUMENTO: 'Gerar a ficha do processo (PDF)', WEBHOOK: 'Chamar outro sistema (webhook)',
}
export interface Notificacao { id: number; titulo: string; mensagem: string | null; empresaNome: string | null; entregaNome: string | null; autorNome: string | null; processoId: number | null; clienteId: number | null; lida: boolean; criadoEm: string }
export interface MensagemAjuda { id: number; autorNome: string; deAdmin: boolean; texto: string; criadoEm: string }
export interface ConversaAjuda { id: number; usuarioId: number; fotoVersao: number | null; pessoa: string; setor: string | null; ultimaTexto: string | null; ultimaDeAdmin: boolean; atualizadoEm: string; naoLidas: number }
export interface ConversaAjudaAberta { id: number; usuarioId: number; fotoVersao: number | null; pessoa: string; setor: string | null; mensagens: MensagemAjuda[] }
export interface RegistroAuditoria { id: number; usuario: string | null; acao: string; detalhe: string | null; criadoEm: string; ip: string | null; userAgent: string | null }
export interface ConfigDoisFatores { segredo: string; uri: string }

// ---- Fase 3: calendário fiscal, matriz de obrigações, dependências e campos extras ----
export type Periodicidade = 'MENSAL' | 'TRIMESTRAL' | 'ANUAL'
export type ModoVencimento = 'DIA_DO_MES' | 'ENESIMO_DIA_UTIL' | 'ULTIMO_DIA_UTIL'
export type RegraDiaUtil = 'MANTER_DATA' | 'ANTECIPAR_DIA_UTIL' | 'POSTERGAR_DIA_UTIL'
export type Esfera = 'FEDERAL' | 'ESTADUAL' | 'MUNICIPAL'
export const ROTULO_REGRA: Record<RegraDiaUtil, string> = { MANTER_DATA: 'Manter a data (dias corridos)', ANTECIPAR_DIA_UTIL: 'Antecipar para o dia útil anterior', POSTERGAR_DIA_UTIL: 'Postergar para o próximo dia útil' }
export interface ModeloObrigacao {
  id: number; nome: string; entregaId: number; entregaNome: string | null; setorId: number | null; periodicidade: Periodicidade; modoVencimento: ModoVencimento
  diaVencimento: number | null; mesesDefasagem: number; mesesAplicaveis: number[]; regraDiaUtil: RegraDiaUtil; esfera: Esfera; regimesAplicaveis: Regime[]
  ufsAplicaveis: string[]; cnaesAplicaveis: string[]; responsavelPadraoId: number | null; ativo: boolean; dependeDeModeloIds: number[]; empresasVinculadas: number
}
export interface ItemCatalogo { nome: string; periodicidade: Periodicidade; modoVencimento: ModoVencimento; diaVencimento: number | null; mesesDefasagem: number; mesesAplicaveis: number[]; regraDiaUtil: RegraDiaUtil; esfera: Esfera; regimesAplicaveis: Regime[]; observacao: string }
export interface ObrigacaoDaEmpresa { modeloId: number; nome: string; entregaNome: string | null; periodicidade: Periodicidade; esfera: Esfera; vencimento: string; vinculada: boolean; ativa: boolean; compativel: boolean; responsavelId: number | null; personalizado: boolean }
export interface Feriado { id: number; escopo: 'NACIONAL' | 'ESTADUAL' | 'MUNICIPAL'; uf: string | null; codigoMunicipioIbge: string | null; data: string | null; dia: number | null; mes: number | null; descricao: string; ativo: boolean }
export interface ResultadoGeracao { ano: number; mes: number; gerados: number; jaExistiam: number; dependenciasCriadas: number; ignorados: string[] }
export interface Dependencia { id: number; predecessorId: number; numero: string; titulo: string; empresa: string | null; status: Status; etapaAtual: string | null; resolvida: boolean }
export type TipoCampo = 'TEXTO' | 'NUMERO' | 'DATA' | 'MOEDA' | 'SELECAO'
export const ROTULO_TIPO_CAMPO: Record<TipoCampo, string> = { TEXTO: 'Texto', NUMERO: 'Número', DATA: 'Data', MOEDA: 'Valor em reais', SELECAO: 'Seleção' }
export interface CampoExtra { id: number; chave: string; rotulo: string; tipo: TipoCampo; obrigatorio: boolean; opcoes: string[]; ordem: number }

// ---- Fase 4: GED, notas fiscais em XML e consulta à SEFAZ ----
export const CATEGORIAS_GED = ['GUIA', 'RECIBO', 'EXTRATO', 'XML', 'CONTRATO', 'FICHA', 'OUTRO'] as const
export interface PastaGed { ano: number | null; mes: number | null; setorId: number | null; setorNome: string; quantidade: number; bytes: number }
export interface DocumentoGed { id: number; nome: string; categoria: string | null; tamanho: number; ano: number | null; mes: number | null; setorId: number | null; setorNome: string | null; processoId: number | null; guardaLegal: boolean; arquivado: boolean; criadoEm: string; automatico: boolean }
export interface NotaXml {
  id: number; chaveAcesso: string; modelo: string; numeroNota: string | null; serie: string | null; dataEmissao: string | null; competenciaAno: number | null; competenciaMes: number | null
  cnpjEmitente: string | null; nomeEmitente: string | null; cnpjDestinatario: string | null; nomeDestinatario: string | null; tipoOperacao: 'ENTRADA' | 'SAIDA'
  valorTotal: number; valorIcms: number; valorIpi: number; valorPis: number; valorCofins: number; valorIss: number; cfops: string[]; ncms: string[]; origem: string; documentoId: number | null
}
export interface ResultadoImportacao { lidos: number; importados: number; duplicados: number; rejeitados: { arquivo: string; motivo: string }[]; notas: NotaXml[] }
export interface AlertaDocumento { nivel: 'INFO' | 'ALERTA'; mensagem: string }
export interface EstadoSefaz { habilitado: boolean; ultimoNsu: string; ultimaConsulta: string | null; proximaConsulta: string | null; status: string | null; mensagem: string | null }
export interface ResultadoSefaz { status: string; mensagem: string | null; importadas: number; duplicadas: number; resumosIgnorados: number; ultimoNsu: string }
