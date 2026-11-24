import { useCallback, useEffect, useState, type FormEvent } from 'react'
import {
  api, ROTULO_REGIME, ROTULO_REGRA, type Entrega, type Esfera, type Feriado, type ItemCatalogo, type ModeloObrigacao, type ModoVencimento,
  type Periodicidade, type RegraDiaUtil, type Regime, type ResultadoGeracao, type Usuario,
} from '../api'
import BotaoApagar from '../BotaoApagar'
import { useAuth } from '../auth'
import { fmtData } from '../formato'

const MESES = ['Jan', 'Fev', 'Mar', 'Abr', 'Mai', 'Jun', 'Jul', 'Ago', 'Set', 'Out', 'Nov', 'Dez']
const TODOS = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]
type Aba = 'modelos' | 'feriados' | 'gerar'

/** Matriz tributária: modelos de obrigação, feriados do calendário fiscal e geração dos processos da competência (administrador e gestor). */
export default function Obrigacoes() {
  const { podeGerenciar } = useAuth()
  const [aba, setAba] = useState<Aba>('modelos')
  if (!podeGerenciar) return <p className="sub">Somente administrador e gestor acessam as obrigações.</p>
  return (
    <>
      <div className="barra"><h2>Obrigações</h2></div>
      <p className="sub">Defina o que o escritório entrega todo mês (ou trimestre, ou ano), quando vence e para quais empresas vale. O sistema abre os processos sozinho, com o prazo calculado em dias úteis.</p>
      <div className="abas" role="tablist">
        {([['modelos', 'Modelos de obrigação'], ['gerar', 'Gerar competência'], ['feriados', 'Feriados']] as [Aba, string][]).map(([k, r]) => (
          <button key={k} role="tab" aria-selected={aba === k} className={aba === k ? 'ativa' : ''} onClick={() => setAba(k)}>{r}</button>
        ))}
      </div>
      {aba === 'modelos' && <Modelos />}
      {aba === 'gerar' && <Gerar />}
      {aba === 'feriados' && <Feriados />}
    </>
  )
}

// ---------------------------------------------------------------- modelos
interface Form {
  id: number | null; nome: string; entregaId: string; periodicidade: Periodicidade; modoVencimento: ModoVencimento; diaVencimento: string; mesesDefasagem: string
  meses: number[]; regraDiaUtil: RegraDiaUtil; esfera: Esfera; regimes: Regime[]; ufs: string; cnaes: string; responsavelId: string; depende: number[]
}
const vazio: Form = { id: null, nome: '', entregaId: '', periodicidade: 'MENSAL', modoVencimento: 'DIA_DO_MES', diaVencimento: '20', mesesDefasagem: '1', meses: TODOS, regraDiaUtil: 'ANTECIPAR_DIA_UTIL', esfera: 'FEDERAL', regimes: [], ufs: '', cnaes: '', responsavelId: '', depende: [] }

function Modelos() {
  const { ehAdmin } = useAuth()
  const [lista, setLista] = useState<ModeloObrigacao[]>([])
  const [catalogo, setCatalogo] = useState<ItemCatalogo[]>([])
  const [entregas, setEntregas] = useState<Entrega[]>([])
  const [usuarios, setUsuarios] = useState<Usuario[]>([])
  const [f, setF] = useState<Form>(vazio)
  const [erro, setErro] = useState('')
  const carregar = useCallback(() => { api.get<ModeloObrigacao[]>('/obrigacoes/modelos').then(setLista).catch((e) => setErro(e.message)) }, [])
  useEffect(() => {
    carregar()
    api.get<ItemCatalogo[]>('/obrigacoes/catalogo').then(setCatalogo)
    api.get<Entrega[]>('/entregas').then(setEntregas)
    api.pagina<Usuario>('/usuarios', 0, 500).then((r) => setUsuarios(r.itens.filter((u) => u.ativo)))
  }, [carregar])
  const agir = (fn: () => Promise<unknown>) => fn().then(() => { setErro(''); carregar() }).catch((e) => setErro(e.message))

  const usarCatalogo = (i: number) => {
    const c = catalogo[i]
    if (!c) return
    setF({ ...f, id: null, nome: c.nome, periodicidade: c.periodicidade, modoVencimento: c.modoVencimento, diaVencimento: c.diaVencimento ? String(c.diaVencimento) : '', mesesDefasagem: String(c.mesesDefasagem), meses: c.mesesAplicaveis, regraDiaUtil: c.regraDiaUtil, esfera: c.esfera, regimes: c.regimesAplicaveis })
  }
  const editar = (m: ModeloObrigacao) => setF({
    id: m.id, nome: m.nome, entregaId: String(m.entregaId), periodicidade: m.periodicidade, modoVencimento: m.modoVencimento, diaVencimento: m.diaVencimento ? String(m.diaVencimento) : '',
    mesesDefasagem: String(m.mesesDefasagem), meses: m.mesesAplicaveis, regraDiaUtil: m.regraDiaUtil, esfera: m.esfera, regimes: m.regimesAplicaveis, ufs: m.ufsAplicaveis.join(', '),
    cnaes: m.cnaesAplicaveis.join(', '), responsavelId: m.responsavelPadraoId ? String(m.responsavelPadraoId) : '', depende: m.dependeDeModeloIds,
  })
  const alternar = <T,>(lista: T[], v: T) => (lista.includes(v) ? lista.filter((x) => x !== v) : [...lista, v])

  const enviar = (e: FormEvent) => {
    e.preventDefault()
    const corpo = {
      nome: f.nome, entregaId: Number(f.entregaId), periodicidade: f.periodicidade, modoVencimento: f.modoVencimento,
      diaVencimento: f.modoVencimento === 'ULTIMO_DIA_UTIL' ? null : Number(f.diaVencimento), mesesDefasagem: Number(f.mesesDefasagem), mesesAplicaveis: f.meses,
      regraDiaUtil: f.regraDiaUtil, esfera: f.esfera, regimesAplicaveis: f.regimes, ufsAplicaveis: f.ufs.split(/[ ,;]+/).filter(Boolean), cnaesAplicaveis: f.cnaes.split(/[ ,;]+/).filter(Boolean),
      responsavelPadraoId: f.responsavelId ? Number(f.responsavelId) : null, dependeDeModeloIds: f.depende,
    }
    return agir(async () => { await (f.id ? api.patch(`/obrigacoes/modelos/${f.id}`, corpo) : api.post('/obrigacoes/modelos', corpo)); setF(vazio) })
  }

  return (
    <>
      <section className="cartao">
        <h3>Modelos cadastrados</h3>
        {erro && <p className="erro">{erro}</p>}
        <div className="tabela-wrap"><table>
          <thead><tr><th>Obrigação</th><th>Entrega</th><th>Periodicidade</th><th>Vencimento</th><th>Esfera</th><th>Empresas</th><th>Situação</th><th></th></tr></thead>
          <tbody>
            {lista.map((m) => (
              <tr key={m.id}>
                <td><b>{m.nome}</b>{m.dependeDeModeloIds.length > 0 && <small className="sub"> · depende de {m.dependeDeModeloIds.map((d) => lista.find((x) => x.id === d)?.nome ?? '?').join(', ')}</small>}</td>
                <td>{m.entregaNome}</td>
                <td>{m.periodicidade === 'MENSAL' ? 'Mensal' : m.periodicidade === 'TRIMESTRAL' ? 'Trimestral' : 'Anual'}{m.periodicidade !== 'MENSAL' && <small className="sub"> ({m.mesesAplicaveis.map((x) => MESES[x - 1]).join(', ')})</small>}</td>
                <td>{descrever(m)}</td>
                <td>{m.esfera === 'FEDERAL' ? 'Federal' : m.esfera === 'ESTADUAL' ? 'Estadual' : 'Municipal'}</td>
                <td>{m.empresasVinculadas}</td>
                <td><label className="chk"><input type="checkbox" checked={m.ativo} onChange={() => agir(() => api.patch(`/obrigacoes/modelos/${m.id}`, { ativo: !m.ativo }))} /> {m.ativo ? 'Ativa' : 'Inativa'}</label></td>
                <td className="acoes-linha"><button type="button" className="link" onClick={() => editar(m)}>editar</button><BotaoApagar nome={m.nome} aviso="Os processos já gerados continuam existindo." apagar={() => agir(() => api.del(`/obrigacoes/modelos/${m.id}`))} /></td>
              </tr>
            ))}
            {lista.length === 0 && <tr><td colSpan={8} className="vazio">Nenhum modelo ainda. Use o formulário abaixo, partindo de um modelo do catálogo.</td></tr>}
          </tbody>
        </table></div>
      </section>

      <form className="cartao" onSubmit={enviar}>
        <h3>{f.id ? 'Editar obrigação' : 'Nova obrigação'}</h3>
        {!f.id && (
          <label>Começar de um modelo do catálogo
            <select defaultValue="" onChange={(e) => usarCatalogo(Number(e.target.value))}>
              <option value="" disabled>— escolha —</option>
              {catalogo.map((c, i) => <option key={c.nome} value={i}>{c.nome}</option>)}
            </select>
          </label>
        )}
        <small>Os prazos do catálogo são pontos de partida: confira com a legislação e com o estado antes de usar.</small>
        <div className="ficha-form">
          <label>Nome<input required maxLength={120} value={f.nome} onChange={(e) => setF({ ...f, nome: e.target.value })} /></label>
          <label>Entrega (define o fluxo e o setor)
            <select required value={f.entregaId} disabled={!!f.id} onChange={(e) => setF({ ...f, entregaId: e.target.value })}>
              <option value="">—</option>
              {entregas.filter((e) => e.ativo).map((e) => <option key={e.id} value={e.id}>{e.nome}{ehAdmin && e.setorDonoNome ? ` (${e.setorDonoNome})` : ''}</option>)}
            </select>
          </label>
          <label>Periodicidade
            <select value={f.periodicidade} onChange={(e) => setF({ ...f, periodicidade: e.target.value as Periodicidade, meses: e.target.value === 'MENSAL' ? TODOS : e.target.value === 'TRIMESTRAL' ? [3, 6, 9, 12] : [12] })}>
              <option value="MENSAL">Mensal</option><option value="TRIMESTRAL">Trimestral</option><option value="ANUAL">Anual</option>
            </select>
          </label>
          <label>Esfera
            <select value={f.esfera} onChange={(e) => setF({ ...f, esfera: e.target.value as Esfera })}><option value="FEDERAL">Federal</option><option value="ESTADUAL">Estadual</option><option value="MUNICIPAL">Municipal</option></select>
          </label>
          <label>Como vence
            <select value={f.modoVencimento} onChange={(e) => setF({ ...f, modoVencimento: e.target.value as ModoVencimento })}>
              <option value="DIA_DO_MES">Num dia do mês</option><option value="ENESIMO_DIA_UTIL">No enésimo dia útil</option><option value="ULTIMO_DIA_UTIL">No último dia útil</option>
            </select>
          </label>
          {f.modoVencimento !== 'ULTIMO_DIA_UTIL' && (
            <label>{f.modoVencimento === 'DIA_DO_MES' ? 'Dia do mês (1 a 31)' : 'Qual dia útil (1º a 22º)'}<input type="number" min={1} max={31} required value={f.diaVencimento} onChange={(e) => setF({ ...f, diaVencimento: e.target.value })} /></label>
          )}
          <label>Meses depois da competência<input type="number" min={0} max={24} required value={f.mesesDefasagem} onChange={(e) => setF({ ...f, mesesDefasagem: e.target.value })} /></label>
          <label>Se cair em fim de semana ou feriado
            <select value={f.regraDiaUtil} disabled={f.modoVencimento !== 'DIA_DO_MES'} onChange={(e) => setF({ ...f, regraDiaUtil: e.target.value as RegraDiaUtil })}>
              {(Object.keys(ROTULO_REGRA) as RegraDiaUtil[]).map((r) => <option key={r} value={r}>{ROTULO_REGRA[r]}</option>)}
            </select>
          </label>
          <label>Responsável padrão
            <select value={f.responsavelId} onChange={(e) => setF({ ...f, responsavelId: e.target.value })}>
              <option value="">— o gestor do setor —</option>
              {usuarios.map((u) => <option key={u.id} value={u.id}>{u.nome}</option>)}
            </select>
          </label>
        </div>
        <fieldset className="caixas"><legend>Competências em que existe</legend>
          {TODOS.map((m) => <label key={m} className="chk"><input type="checkbox" checked={f.meses.includes(m)} onChange={() => setF({ ...f, meses: alternar(f.meses, m).sort((a, b) => a - b) })} /> {MESES[m - 1]}</label>)}
        </fieldset>
        <fieldset className="caixas"><legend>Regimes tributários (nenhum marcado = todos)</legend>
          {(Object.keys(ROTULO_REGIME) as Regime[]).map((r) => <label key={r} className="chk"><input type="checkbox" checked={f.regimes.includes(r)} onChange={() => setF({ ...f, regimes: alternar(f.regimes, r) })} /> {ROTULO_REGIME[r]}</label>)}
        </fieldset>
        <div className="ficha-form">
          <label>UFs (opcional, separadas por vírgula)<input value={f.ufs} placeholder="SP, RJ" onChange={(e) => setF({ ...f, ufs: e.target.value })} /></label>
          <label>CNAEs (opcional, início do código)<input value={f.cnaes} placeholder="62, 4120" onChange={(e) => setF({ ...f, cnaes: e.target.value })} /></label>
        </div>
        {lista.filter((m) => m.id !== f.id).length > 0 && (
          <fieldset className="caixas"><legend>Só avança quando concluírem (mesma empresa e competência)</legend>
            {lista.filter((m) => m.id !== f.id).map((m) => <label key={m.id} className="chk"><input type="checkbox" checked={f.depende.includes(m.id)} onChange={() => setF({ ...f, depende: alternar(f.depende, m.id) })} /> {m.nome}</label>)}
          </fieldset>
        )}
        <div className="linha-acoes">
          <button className="primario">{f.id ? 'Salvar alterações' : 'Criar obrigação'}</button>
          {f.id && <button type="button" onClick={() => setF(vazio)}>Cancelar edição</button>}
        </div>
        <small>Ao salvar, as empresas ativas compatíveis (regime, UF e CNAE) já ficam vinculadas.</small>
      </form>
    </>
  )
}

function descrever(m: ModeloObrigacao) {
  const quando = m.mesesDefasagem === 0 ? 'no mês' : m.mesesDefasagem === 1 ? 'no mês seguinte' : `+${m.mesesDefasagem} meses`
  if (m.modoVencimento === 'DIA_DO_MES') return `dia ${m.diaVencimento} ${quando}${m.regraDiaUtil === 'ANTECIPAR_DIA_UTIL' ? ' (antecipa)' : m.regraDiaUtil === 'POSTERGAR_DIA_UTIL' ? ' (posterga)' : ''}`
  if (m.modoVencimento === 'ENESIMO_DIA_UTIL') return `${m.diaVencimento}º dia útil ${quando}`
  return `último dia útil ${quando}`
}

// ---------------------------------------------------------------- gerar competência
function Gerar() {
  const hoje = new Date()
  const anterior = new Date(hoje.getFullYear(), hoje.getMonth() - 1, 1)
  const [ano, setAno] = useState(String(anterior.getFullYear()))
  const [mes, setMes] = useState(String(anterior.getMonth() + 1))
  const [r, setR] = useState<ResultadoGeracao | null>(null)
  const [erro, setErro] = useState('')
  const [rodando, setRodando] = useState(false)
  const rodar = async (e: FormEvent) => {
    e.preventDefault()
    setErro(''); setR(null); setRodando(true)
    try { setR(await api.post<ResultadoGeracao>('/obrigacoes/gerar', { ano: Number(ano), mes: Number(mes) })) } catch (err) { setErro((err as Error).message) } finally { setRodando(false) }
  }
  return (
    <form className="cartao" onSubmit={rodar}>
      <h3>Gerar obrigações da competência</h3>
      <p className="sub">Abre um processo para cada empresa ativa e cada obrigação vinculada que vale na competência escolhida, com o prazo calculado pelo calendário fiscal (feriados nacionais, da UF e do município da empresa). Nada é duplicado: pode repetir. O sistema também faz isso sozinho no dia 1º de cada mês, para o mês anterior.</p>
      <div className="ficha-form">
        <label>Mês da competência
          <select value={mes} onChange={(e) => setMes(e.target.value)}>{MESES.map((n, i) => <option key={n} value={i + 1}>{n}</option>)}</select>
        </label>
        <label>Ano<input type="number" min={2000} max={2100} value={ano} onChange={(e) => setAno(e.target.value)} /></label>
      </div>
      {erro && <p className="erro">{erro}</p>}
      <div className="linha-acoes"><button className="primario" disabled={rodando}>{rodando ? 'Gerando…' : 'Gerar processos'}</button></div>
      {r && (
        <div className="aviso-resultado">
          <p><b>{r.gerados}</b> processo(s) aberto(s) para {String(r.mes).padStart(2, '0')}/{r.ano}; {r.jaExistiam} já existia(m); {r.dependenciasCriadas} dependência(s) ligada(s).</p>
          {r.ignorados.length > 0 && <><p className="sub">Não foram gerados:</p><ul>{r.ignorados.map((i) => <li key={i}>{i}</li>)}</ul></>}
        </div>
      )}
    </form>
  )
}

// ---------------------------------------------------------------- feriados
function Feriados() {
  const [lista, setLista] = useState<Feriado[]>([])
  const [erro, setErro] = useState('')
  const [f, setF] = useState({ escopo: 'ESTADUAL', uf: '', ibge: '', tipo: 'fixo', dia: '', mes: '', data: '', descricao: '' })
  const carregar = useCallback(() => { api.get<Feriado[]>('/feriados').then(setLista).catch((e) => setErro(e.message)) }, [])
  useEffect(carregar, [carregar])
  const agir = (fn: () => Promise<unknown>) => fn().then(() => { setErro(''); carregar() }).catch((e) => setErro(e.message))
  const criar = (e: FormEvent) => {
    e.preventDefault()
    const corpo: Record<string, unknown> = { escopo: f.escopo, descricao: f.descricao, uf: f.escopo === 'ESTADUAL' ? f.uf : null, codigoMunicipioIbge: f.escopo === 'MUNICIPAL' ? f.ibge : null }
    if (f.tipo === 'fixo') { corpo.dia = Number(f.dia); corpo.mes = Number(f.mes) } else corpo.data = f.data
    return agir(async () => { await api.post('/feriados', corpo); setF({ ...f, descricao: '', dia: '', mes: '', data: '' }) })
  }
  return (
    <div className="grade">
      <section className="cartao">
        <h3>Feriados do calendário fiscal</h3>
        <p className="sub">Sábados, domingos e estes feriados não são dias úteis. A Sexta-feira Santa é sempre considerada. Feriados estaduais e municipais valem só para empresas atendidas da UF ou do município.</p>
        {erro && <p className="erro">{erro}</p>}
        <div className="tabela-wrap"><table>
          <thead><tr><th>Data</th><th>Descrição</th><th>Escopo</th><th></th></tr></thead>
          <tbody>
            {lista.map((h) => (
              <tr key={h.id}>
                <td>{h.data ? fmtData(h.data) : `${String(h.dia).padStart(2, '0')}/${String(h.mes).padStart(2, '0')} (todo ano)`}</td>
                <td>{h.descricao}</td>
                <td>{h.escopo === 'NACIONAL' ? 'Nacional' : h.escopo === 'ESTADUAL' ? `Estadual · ${h.uf}` : `Municipal · IBGE ${h.codigoMunicipioIbge}`}</td>
                <td className="acoes-linha"><BotaoApagar nome={h.descricao} apagar={() => agir(() => api.del(`/feriados/${h.id}`))} /></td>
              </tr>
            ))}
          </tbody>
        </table></div>
      </section>
      <form className="cartao" onSubmit={criar}>
        <h3>Novo feriado</h3>
        <label>Escopo
          <select value={f.escopo} onChange={(e) => setF({ ...f, escopo: e.target.value })}><option value="NACIONAL">Nacional</option><option value="ESTADUAL">Estadual</option><option value="MUNICIPAL">Municipal</option></select>
        </label>
        {f.escopo === 'ESTADUAL' && <label>UF<input required maxLength={2} value={f.uf} onChange={(e) => setF({ ...f, uf: e.target.value.toUpperCase() })} /></label>}
        {f.escopo === 'MUNICIPAL' && <label>Código do município (IBGE)<input required inputMode="numeric" maxLength={7} value={f.ibge} onChange={(e) => setF({ ...f, ibge: e.target.value })} /></label>}
        <label>Repete
          <select value={f.tipo} onChange={(e) => setF({ ...f, tipo: e.target.value })}><option value="fixo">Todo ano (dia e mês)</option><option value="data">Numa data só</option></select>
        </label>
        {f.tipo === 'fixo'
          ? <div className="ficha-form"><label>Dia<input type="number" min={1} max={31} required value={f.dia} onChange={(e) => setF({ ...f, dia: e.target.value })} /></label><label>Mês<input type="number" min={1} max={12} required value={f.mes} onChange={(e) => setF({ ...f, mes: e.target.value })} /></label></div>
          : <label>Data<input type="date" required value={f.data} onChange={(e) => setF({ ...f, data: e.target.value })} /></label>}
        <label>Descrição<input required maxLength={150} value={f.descricao} onChange={(e) => setF({ ...f, descricao: e.target.value })} /></label>
        <button className="primario">Adicionar feriado</button>
      </form>
    </div>
  )
}
