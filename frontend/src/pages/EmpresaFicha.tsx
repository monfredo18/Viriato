import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { Link, Navigate, useParams } from 'react-router-dom'
import {
  api, ROTULO_AREA, ROTULO_PROCURACAO, ROTULO_REGIME, ROTULO_STATUS,
  type AreaContato, type Ficha, type ObrigacaoDaEmpresa, type Usuario, type Regime, type StatusContratual, type TipoProcuracao,
} from '../api'
import BotaoApagar from '../BotaoApagar'
import { useAuth } from '../auth'
import { fmtData } from '../formato'

type Aba = 'dados' | 'socios' | 'contatos' | 'certificados' | 'procuracoes' | 'obrigacoes' | 'checklist'
const ABAS: [Aba, string][] = [['dados', 'Dados fiscais'], ['socios', 'Sócios'], ['contatos', 'Contatos'], ['certificados', 'Certificados'], ['procuracoes', 'Procurações'], ['obrigacoes', 'Obrigações'], ['checklist', 'Checklist']]
const UFS = ['AC', 'AL', 'AP', 'AM', 'BA', 'CE', 'DF', 'ES', 'GO', 'MA', 'MT', 'MS', 'MG', 'PA', 'PB', 'PR', 'PE', 'PI', 'RJ', 'RN', 'RS', 'RO', 'RR', 'SC', 'SP', 'SE', 'TO']

/** Do nome completo do certificado (CN=..., O=...) fica só o CN. */
const cn = (dn: string | null) => (dn ? dn.split(',')[0].replace(/^CN=/i, '') : '—')
const seloDe = (s: string) => (s === 'VENCIDO' || s === 'VENCIDA' ? 'atrasado' : s === 'VENCENDO' ? 'aberto' : s === 'SEM_VALIDADE' || s === 'SEM_VENCIMENTO' ? 'cancelado' : 'concluido')
const rotuloSituacao: Record<string, string> = { VALIDO: 'Válido', VENCENDO: 'Vencendo', VENCIDO: 'Vencido', SEM_VALIDADE: 'Sem validade', VIGENTE: 'Vigente', VENCIDA: 'Vencida', SEM_VENCIMENTO: 'Sem vencimento' }

/** Ficha completa da empresa atendida (só administrador e gestor). */
export default function EmpresaFicha() {
  const { id } = useParams()
  const { podeGerenciar } = useAuth()
  const [ficha, setFicha] = useState<Ficha | null>(null)
  const [aba, setAba] = useState<Aba>('dados')
  const [erro, setErro] = useState('')

  const carregar = useCallback(() => api.get<Ficha>(`/clientes/${id}/ficha`).then((f) => { setFicha(f); setErro('') }).catch((e) => setErro(e.message)), [id])
  useEffect(() => { if (podeGerenciar) carregar() }, [carregar, podeGerenciar])

  if (!podeGerenciar) return <Navigate to="/cadastros" replace />
  const agir = (fn: () => Promise<unknown>) => fn().then(carregar).catch((e) => setErro(e.message))
  const base = `/clientes/${id}`

  return (
    <>
      <p><Link to="/cadastros">‹ Voltar para Cadastros</Link></p>
      {erro && <p className="erro">{erro}</p>}
      {ficha && (
        <>
          <div className="ficha-topo">
            <h2>{ficha.numero} — {ficha.nome}</h2>
            <span className={`selo ${ficha.fiscal.statusContratual === 'ATIVO' ? 'concluido' : ficha.fiscal.statusContratual === 'ENCERRADO' || ficha.fiscal.statusContratual === 'SUSPENSO' ? 'cancelado' : 'aberto'}`}>{ROTULO_STATUS[ficha.fiscal.statusContratual]}</span>
            {ficha.fiscal.cnpjFormatado && <span className="sub">CNPJ {ficha.fiscal.cnpjFormatado}</span>}
          </div>
          <div className="abas" role="tablist">
            {ABAS.map(([k, rot]) => <button key={k} role="tab" aria-selected={aba === k} className={aba === k ? 'ativa' : ''} onClick={() => setAba(k)}>{rot}</button>)}
          </div>
          {aba === 'dados' && <Dados ficha={ficha} salvar={(fiscal) => agir(() => api.patch(base, { fiscal }))} />}
          {aba === 'socios' && <Socios ficha={ficha} base={base} agir={agir} />}
          {aba === 'contatos' && <Contatos ficha={ficha} base={base} agir={agir} />}
          {aba === 'certificados' && <Certificados ficha={ficha} base={base} agir={agir} />}
          {aba === 'procuracoes' && <Procuracoes ficha={ficha} base={base} agir={agir} />}
          {aba === 'obrigacoes' && <ObrigacoesDaEmpresa base={base} />}
          {aba === 'checklist' && <Checklist ficha={ficha} base={base} agir={agir} />}
        </>
      )}
    </>
  )
}

type Agir = (fn: () => Promise<unknown>) => Promise<unknown>

// ---------------------------------------------------------------- dados fiscais
function Dados({ ficha, salvar }: { ficha: Ficha; salvar: (fiscal: object) => Promise<unknown> }) {
  const f = ficha.fiscal
  const [v, setV] = useState({
    cnpj: f.cnpjFormatado ?? '', razaoSocial: f.razaoSocial ?? '', nomeFantasia: f.nomeFantasia ?? '', inscricaoEstadual: f.inscricaoEstadual ?? '', inscricaoMunicipal: f.inscricaoMunicipal ?? '',
    cnaePrincipal: f.cnaePrincipal ?? '', cnaePrincipalDescricao: f.cnaePrincipalDescricao ?? '', regimeTributario: (f.regimeTributario ?? '') as Regime | '', uf: f.uf ?? '',
    codigoMunicipioIbge: f.codigoMunicipioIbge ?? '', dataAberturaEmpresa: f.dataAberturaEmpresa ?? '', statusContratual: f.statusContratual as StatusContratual,
    secundarios: f.cnaesSecundarios.map((c) => `${c.codigo}${c.descricao ? ' ' + c.descricao : ''}`).join('\n'),
  })
  const set = (k: keyof typeof v) => (e: { target: { value: string } }) => setV({ ...v, [k]: e.target.value })

  const enviar = (e: FormEvent) => {
    e.preventDefault()
    const secundarios = v.secundarios.split('\n').map((l) => l.trim()).filter(Boolean).map((l) => {
      const [codigo, ...resto] = l.split(/\s+/)
      return { codigo, descricao: resto.join(' ') || null }
    })
    return salvar({
      cnpj: v.cnpj, razaoSocial: v.razaoSocial, nomeFantasia: v.nomeFantasia, inscricaoEstadual: v.inscricaoEstadual, inscricaoMunicipal: v.inscricaoMunicipal,
      cnaePrincipal: v.cnaePrincipal, cnaePrincipalDescricao: v.cnaePrincipalDescricao, cnaesSecundarios: secundarios, regimeTributario: v.regimeTributario || null,
      uf: v.uf, codigoMunicipioIbge: v.codigoMunicipioIbge, dataAberturaEmpresa: v.dataAberturaEmpresa || null, statusContratual: v.statusContratual,
    })
  }

  return (
    <form className="cartao" onSubmit={enviar}>
      <h3>Dados fiscais e societários</h3>
      <div className="ficha-form">
        <label>CNPJ<input value={v.cnpj} onChange={set('cnpj')} placeholder="00.000.000/0000-00" inputMode="numeric" maxLength={18} /></label>
        <label>Razão social<input value={v.razaoSocial} onChange={set('razaoSocial')} maxLength={200} /></label>
        <label>Nome fantasia<input value={v.nomeFantasia} onChange={set('nomeFantasia')} maxLength={200} /></label>
        <label>Inscrição estadual<input value={v.inscricaoEstadual} onChange={set('inscricaoEstadual')} maxLength={30} /></label>
        <label>Inscrição municipal<input value={v.inscricaoMunicipal} onChange={set('inscricaoMunicipal')} maxLength={30} /></label>
        <label>Regime tributário
          <select value={v.regimeTributario} onChange={set('regimeTributario')}>
            <option value="">— não definido —</option>
            {(Object.keys(ROTULO_REGIME) as Regime[]).map((r) => <option key={r} value={r}>{ROTULO_REGIME[r]}</option>)}
          </select>
        </label>
        <label>CNAE principal<input value={v.cnaePrincipal} onChange={set('cnaePrincipal')} placeholder="0000-0/00" maxLength={9} /></label>
        <label className="largo">Descrição do CNAE principal<input value={v.cnaePrincipalDescricao} onChange={set('cnaePrincipalDescricao')} maxLength={255} /></label>
        <label className="largo">CNAEs secundários (um por linha: código e descrição)
          <textarea rows={3} value={v.secundarios} onChange={set('secundarios')} placeholder="4399-1/03 Obras de alvenaria" />
        </label>
        <label>UF
          <select value={v.uf} onChange={set('uf')}><option value="">—</option>{UFS.map((u) => <option key={u}>{u}</option>)}</select>
        </label>
        <label>Código do município (IBGE)<input value={v.codigoMunicipioIbge} onChange={set('codigoMunicipioIbge')} inputMode="numeric" maxLength={7} placeholder="7 dígitos" /></label>
        <label>Data de abertura<input type="date" value={v.dataAberturaEmpresa} onChange={set('dataAberturaEmpresa')} /></label>
        <label>Situação contratual
          <select value={v.statusContratual} onChange={set('statusContratual')}>
            {(Object.keys(ROTULO_STATUS) as StatusContratual[]).map((s) => <option key={s} value={s}>{ROTULO_STATUS[s]}</option>)}
          </select>
        </label>
      </div>
      <small>Onboarding gera o checklist de entrada; Offboarding gera o de encerramento. Concluir todos os itens ativa ou encerra a empresa.</small>
      <div className="linha-acoes"><button className="primario">Salvar dados</button></div>
    </form>
  )
}

// ---------------------------------------------------------------- sócios
function Socios({ ficha, base, agir }: { ficha: Ficha; base: string; agir: Agir }) {
  const vazio = { nome: '', cpf: '', percentual: '', responsavelLegal: false, email: '', telefone: '' }
  const [f, setF] = useState(vazio)
  const criar = (e: FormEvent) => {
    e.preventDefault()
    return agir(async () => { await api.post(`${base}/socios`, { ...f, percentual: f.percentual ? Number(f.percentual.replace(',', '.')) : null }); setF(vazio) })
  }
  return (
    <div className="grade">
      <section className="cartao">
        <h3>Sócios</h3>
        <table>
          <thead><tr><th>Nome</th><th>CPF</th><th>Participação</th><th>Contato</th><th></th></tr></thead>
          <tbody>
            {ficha.socios.map((s) => (
              <tr key={s.id}>
                <td>{s.nome} {s.responsavelLegal && <span className="selo aberto">Responsável legal</span>}</td>
                <td>{s.cpf ?? '—'}</td>
                <td>{s.percentual !== null ? `${s.percentual}%` : '—'}</td>
                <td>{[s.email, s.telefone].filter(Boolean).join(' · ') || '—'}</td>
                <td><BotaoApagar nome={s.nome} apagar={() => agir(() => api.del(`${base}/socios/${s.id}`))} /></td>
              </tr>
            ))}
            {ficha.socios.length === 0 && <tr><td colSpan={5} className="vazio">Nenhum sócio cadastrado.</td></tr>}
          </tbody>
        </table>
      </section>
      <form className="cartao" onSubmit={criar}>
        <h3>Novo sócio</h3>
        <label>Nome<input required maxLength={150} value={f.nome} onChange={(e) => setF({ ...f, nome: e.target.value })} /></label>
        <label>CPF<input inputMode="numeric" maxLength={14} placeholder="000.000.000-00" value={f.cpf} onChange={(e) => setF({ ...f, cpf: e.target.value })} /></label>
        <small>O CPF é guardado cifrado e só aparece mascarado.</small>
        <label>Participação (%)<input inputMode="decimal" value={f.percentual} onChange={(e) => setF({ ...f, percentual: e.target.value })} placeholder="50" /></label>
        <label>E-mail<input type="email" value={f.email} onChange={(e) => setF({ ...f, email: e.target.value })} /></label>
        <label>Telefone / WhatsApp<input value={f.telefone} onChange={(e) => setF({ ...f, telefone: e.target.value })} /></label>
        <label className="chk"><input type="checkbox" checked={f.responsavelLegal} onChange={(e) => setF({ ...f, responsavelLegal: e.target.checked })} /> Responsável legal</label>
        <button className="primario">Adicionar sócio</button>
      </form>
    </div>
  )
}

// ---------------------------------------------------------------- contatos
function Contatos({ ficha, base, agir }: { ficha: Ficha; base: string; agir: Agir }) {
  const vazio = { nome: '', area: 'FISCAL' as AreaContato, email: '', telefone: '', recebeGuias: false, recebeCobrancas: false }
  const [f, setF] = useState(vazio)
  const criar = (e: FormEvent) => { e.preventDefault(); return agir(async () => { await api.post(`${base}/contatos`, f); setF(vazio) }) }
  return (
    <div className="grade">
      <section className="cartao">
        <h3>Contatos por área</h3>
        <table>
          <thead><tr><th>Nome</th><th>Área</th><th>Contato</th><th>Recebe</th><th></th></tr></thead>
          <tbody>
            {ficha.contatos.map((c) => (
              <tr key={c.id}>
                <td>{c.nome}</td>
                <td>{ROTULO_AREA[c.area]}</td>
                <td>{[c.email, c.telefone].filter(Boolean).join(' · ') || '—'}</td>
                <td>{[c.recebeGuias && 'guias', c.recebeCobrancas && 'cobranças'].filter(Boolean).join(', ') || '—'}</td>
                <td><BotaoApagar nome={c.nome} apagar={() => agir(() => api.del(`${base}/contatos/${c.id}`))} /></td>
              </tr>
            ))}
            {ficha.contatos.length === 0 && <tr><td colSpan={5} className="vazio">Nenhum contato cadastrado.</td></tr>}
          </tbody>
        </table>
      </section>
      <form className="cartao" onSubmit={criar}>
        <h3>Novo contato</h3>
        <label>Nome<input required maxLength={150} value={f.nome} onChange={(e) => setF({ ...f, nome: e.target.value })} /></label>
        <label>Área
          <select value={f.area} onChange={(e) => setF({ ...f, area: e.target.value as AreaContato })}>
            {(Object.keys(ROTULO_AREA) as AreaContato[]).map((a) => <option key={a} value={a}>{ROTULO_AREA[a]}</option>)}
          </select>
        </label>
        <label>E-mail<input type="email" value={f.email} onChange={(e) => setF({ ...f, email: e.target.value })} /></label>
        <label>Telefone / WhatsApp<input value={f.telefone} onChange={(e) => setF({ ...f, telefone: e.target.value })} /></label>
        <label className="chk"><input type="checkbox" checked={f.recebeGuias} onChange={(e) => setF({ ...f, recebeGuias: e.target.checked })} /> Recebe guias</label>
        <label className="chk"><input type="checkbox" checked={f.recebeCobrancas} onChange={(e) => setF({ ...f, recebeCobrancas: e.target.checked })} /> Recebe cobranças</label>
        <button className="primario">Adicionar contato</button>
      </form>
    </div>
  )
}

// ---------------------------------------------------------------- certificados
function Certificados({ ficha, base, agir }: { ficha: Ficha; base: string; agir: Agir }) {
  const [arquivo, setArquivo] = useState<File | null>(null)
  const [senha, setSenha] = useState('')
  const [a3, setA3] = useState({ titular: '', emissora: '', numeroSerie: '', validoAte: '' })
  const enviarA1 = (e: FormEvent) => {
    e.preventDefault()
    if (!arquivo) return
    const form = new FormData()
    form.append('arquivo', arquivo)
    form.append('senha', senha)
    return agir(async () => { await api.upload(`${base}/certificados/a1`, form); setArquivo(null); setSenha('') })
  }
  const enviarA3 = (e: FormEvent) => { e.preventDefault(); return agir(async () => { await api.post(`${base}/certificados/a3`, a3); setA3({ titular: '', emissora: '', numeroSerie: '', validoAte: '' }) }) }
  return (
    <>
      <section className="cartao">
        <h3>Cofre de certificados digitais</h3>
        <p className="sub">O arquivo do A1 e a senha ficam cifrados e nunca voltam pela tela. Cada uso do certificado pelo sistema fica na Auditoria.</p>
        <div className="tabela-wrap"><table>
          <thead><tr><th>Tipo</th><th>Titular</th><th>Emissora</th><th>Validade</th><th>Situação</th><th></th></tr></thead>
          <tbody>
            {ficha.certificados.map((c) => (
              <tr key={c.id}>
                <td>{c.tipo}</td>
                <td className="corta" title={c.numeroSerie ? `${cn(c.titular)} · série ${c.numeroSerie}` : cn(c.titular)}>{cn(c.titular)}</td>
                <td className="corta" title={cn(c.emissora)}>{cn(c.emissora)}</td>
                <td>{fmtData(c.validoAte)}</td>
                <td><span className={`selo ${seloDe(c.situacao)}`}>{rotuloSituacao[c.situacao]}{c.diasParaVencer !== null && c.diasParaVencer >= 0 && c.situacao === 'VENCENDO' ? ` · ${c.diasParaVencer} d` : ''}</span></td>
                <td className="acoes-linha">
                  <label className="chk"><input type="checkbox" checked={c.ativo} onChange={() => agir(() => api.patch(`${base}/certificados/${c.id}`, { ativo: !c.ativo }))} /> Ativo</label>
                  <BotaoApagar nome={`certificado ${c.tipo}`} apagar={() => agir(() => api.del(`${base}/certificados/${c.id}`))} />
                </td>
              </tr>
            ))}
            {ficha.certificados.length === 0 && <tr><td colSpan={6} className="vazio">Nenhum certificado no cofre.</td></tr>}
          </tbody>
        </table></div>
      </section>
      <div className="ficha-2col">
        <form className="cartao" onSubmit={enviarA1}>
          <h3>Enviar certificado A1</h3>
          <label>Arquivo (.pfx ou .p12)<input type="file" accept=".pfx,.p12" required onChange={(e) => setArquivo(e.target.files?.[0] ?? null)} /></label>
          <label>Senha do certificado<input type="password" required autoComplete="off" value={senha} onChange={(e) => setSenha(e.target.value)} /></label>
          <small>A senha é conferida na hora, e o sistema lê o titular, a emissora, a série e a validade do próprio arquivo.</small>
          <button className="primario" disabled={!arquivo}>Guardar no cofre</button>
        </form>
        <form className="cartao" onSubmit={enviarA3}>
          <h3>Registrar certificado A3</h3>
          <label>Titular<input required value={a3.titular} onChange={(e) => setA3({ ...a3, titular: e.target.value })} /></label>
          <label>Emissora<input value={a3.emissora} onChange={(e) => setA3({ ...a3, emissora: e.target.value })} /></label>
          <label>Número de série<input value={a3.numeroSerie} onChange={(e) => setA3({ ...a3, numeroSerie: e.target.value })} /></label>
          <label>Validade<input type="date" required value={a3.validoAte} onChange={(e) => setA3({ ...a3, validoAte: e.target.value })} /></label>
          <small>O A3 fica no token do titular: o sistema só acompanha a validade.</small>
          <button className="primario">Registrar A3</button>
        </form>
      </div>
    </>
  )
}

// ---------------------------------------------------------------- procurações
function Procuracoes({ ficha, base, agir }: { ficha: Ficha; base: string; agir: Agir }) {
  const vazio = { tipo: 'ECAC' as TipoProcuracao, dataOutorga: '', dataVencimento: '', poderes: '' }
  const [f, setF] = useState(vazio)
  const [arquivo, setArquivo] = useState<File | null>(null)
  const criar = (e: FormEvent) => {
    e.preventDefault()
    const form = new FormData()
    form.append('tipo', f.tipo)
    form.append('dataOutorga', f.dataOutorga)
    if (f.dataVencimento) form.append('dataVencimento', f.dataVencimento)
    if (f.poderes) form.append('poderes', f.poderes)
    if (arquivo) form.append('arquivo', arquivo)
    return agir(async () => { await api.upload(`${base}/procuracoes`, form); setF(vazio); setArquivo(null) })
  }
  return (
    <div className="grade">
      <section className="cartao">
        <h3>Procurações eletrônicas</h3>
        <table>
          <thead><tr><th>Tipo</th><th>Outorga</th><th>Vencimento</th><th>Situação</th><th>Comprovante</th><th></th></tr></thead>
          <tbody>
            {ficha.procuracoes.map((p) => (
              <tr key={p.id}>
                <td title={p.poderes ?? undefined}>{ROTULO_PROCURACAO[p.tipo]}</td>
                <td>{fmtData(p.dataOutorga)}</td>
                <td>{fmtData(p.dataVencimento)}</td>
                <td><span className={`selo ${seloDe(p.situacao)}`}>{rotuloSituacao[p.situacao]}{p.situacao === 'VENCENDO' && p.diasParaVencer !== null ? ` · ${p.diasParaVencer} d` : ''}</span></td>
                <td>{p.temComprovante ? <button type="button" className="link" onClick={() => api.download(`${base}/procuracoes/${p.id}/comprovante`, p.comprovanteNome ?? 'comprovante.pdf')}>Baixar PDF</button> : '—'}</td>
                <td><BotaoApagar nome={`procuração ${ROTULO_PROCURACAO[p.tipo]}`} apagar={() => agir(() => api.del(`${base}/procuracoes/${p.id}`))} /></td>
              </tr>
            ))}
            {ficha.procuracoes.length === 0 && <tr><td colSpan={6} className="vazio">Nenhuma procuração cadastrada.</td></tr>}
          </tbody>
        </table>
      </section>
      <form className="cartao" onSubmit={criar}>
        <h3>Nova procuração</h3>
        <label>Tipo
          <select value={f.tipo} onChange={(e) => setF({ ...f, tipo: e.target.value as TipoProcuracao })}>
            {(Object.keys(ROTULO_PROCURACAO) as TipoProcuracao[]).map((t) => <option key={t} value={t}>{ROTULO_PROCURACAO[t]}</option>)}
          </select>
        </label>
        <label>Data de outorga<input type="date" required value={f.dataOutorga} onChange={(e) => setF({ ...f, dataOutorga: e.target.value })} /></label>
        <label>Vencimento<input type="date" value={f.dataVencimento} onChange={(e) => setF({ ...f, dataVencimento: e.target.value })} /></label>
        <label>Poderes outorgados<textarea rows={2} maxLength={1000} value={f.poderes} onChange={(e) => setF({ ...f, poderes: e.target.value })} /></label>
        <label>Comprovante (PDF)<input type="file" accept=".pdf" onChange={(e) => setArquivo(e.target.files?.[0] ?? null)} /></label>
        <small>Sem vencimento, a procuração não gera alertas.</small>
        <button className="primario">Adicionar procuração</button>
      </form>
    </div>
  )
}

// ---------------------------------------------------------------- checklist
function Checklist({ ficha, base, agir }: { ficha: Ficha; base: string; agir: Agir }) {
  const grupos: ['ONBOARDING' | 'OFFBOARDING', string][] = [['ONBOARDING', 'Onboarding (entrada da empresa)'], ['OFFBOARDING', 'Offboarding (encerramento)']]
  const algum = ficha.checklist.length > 0
  return (
    <>
      {!algum && <section className="cartao"><p className="vazio">Sem checklist. Mude a situação para Onboarding ou Offboarding, na aba Dados fiscais, para gerar a lista.</p></section>}
      {grupos.map(([tipo, titulo]) => {
        const itens = ficha.checklist.filter((i) => i.tipo === tipo)
        if (itens.length === 0) return null
        const feitos = itens.filter((i) => i.concluido).length
        return (
          <section className="cartao" key={tipo}>
            <h3>{titulo} <small className="sub">{feitos} de {itens.length}</small></h3>
            <ul className="ficha-lista">
              {itens.map((i) => (
                <li key={i.id}>
                  <label className="chk">
                    <input type="checkbox" checked={i.concluido} onChange={() => agir(() => api.patch(`${base}/checklist/${i.id}`, { concluido: !i.concluido }))} />
                    <span className={i.concluido ? 'feito' : ''}>{i.descricao}</span>
                  </label>
                </li>
              ))}
            </ul>
          </section>
        )
      })}
    </>
  )
}

// ---------------------------------------------------------------- obrigações
/** Obrigações vinculadas à empresa: sugeridas pelo regime, UF e CNAE; liga, desliga e define o responsável padrão. */
function ObrigacoesDaEmpresa({ base }: { base: string }) {
  const [lista, setLista] = useState<ObrigacaoDaEmpresa[]>([])
  const [usuarios, setUsuarios] = useState<Usuario[]>([])
  const [erro, setErro] = useState('')
  const carregar = useCallback(() => { api.get<ObrigacaoDaEmpresa[]>(`${base}/obrigacoes`).then(setLista).catch((e) => setErro(e.message)) }, [base])
  useEffect(() => { carregar(); api.pagina<Usuario>('/usuarios', 0, 500).then((r) => setUsuarios(r.itens.filter((u) => u.ativo))) }, [carregar])
  const agir = (fn: () => Promise<unknown>) => fn().then(() => { setErro(''); carregar() }).catch((e) => setErro(e.message))
  const rotuloPeriodo = { MENSAL: 'Mensal', TRIMESTRAL: 'Trimestral', ANUAL: 'Anual' }
  return (
    <section className="cartao">
      <h3>Obrigações desta empresa</h3>
      <p className="sub">Vinculadas sozinhas pelo regime tributário, UF e CNAE da aba Dados fiscais. Desligue as que não se aplicam e defina quem cuida de cada uma; os processos do mês são abertos para as que estiverem ligadas.</p>
      {erro && <p className="erro">{erro}</p>}
      <div className="tabela-wrap"><table>
        <thead><tr><th>Obrigação</th><th>Periodicidade</th><th>Vence</th><th>Responsável</th><th>Situação</th></tr></thead>
        <tbody>
          {lista.map((o) => (
            <tr key={o.modeloId}>
              <td><b>{o.nome}</b>{!o.compativel && <small className="sub"> · fora do perfil da empresa</small>}</td>
              <td>{rotuloPeriodo[o.periodicidade]}</td>
              <td>{o.vencimento}</td>
              <td>
                <select disabled={!o.vinculada} aria-label={`Responsável por ${o.nome}`} value={o.responsavelId ?? ''} onChange={(e) => agir(() => api.patch(`${base}/obrigacoes/${o.modeloId}`, e.target.value ? { responsavelId: Number(e.target.value) } : { limparResponsavel: true }))}>
                  <option value="">— o padrão da obrigação —</option>
                  {usuarios.map((u) => <option key={u.id} value={u.id}>{u.nome}</option>)}
                </select>
              </td>
              <td><label className="chk"><input type="checkbox" checked={o.vinculada && o.ativa} onChange={() => agir(() => api.patch(`${base}/obrigacoes/${o.modeloId}`, { ativa: !(o.vinculada && o.ativa) }))} /> {o.vinculada && o.ativa ? 'Ligada' : 'Desligada'}</label></td>
            </tr>
          ))}
          {lista.length === 0 && <tr><td colSpan={5} className="vazio">Nenhuma obrigação cadastrada. Crie os modelos em Obrigações.</td></tr>}
        </tbody>
      </table></div>
    </section>
  )
}
