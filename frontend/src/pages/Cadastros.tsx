import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { api, ROTULO_REGIME, ROTULO_STATUS, type Cliente, type Setor } from '../api'
import SaudeCadastral from '../SaudeCadastral'
import BotaoApagar from '../BotaoApagar'
import { useAuth } from '../auth'

type Aba = 'empresas' | 'setores'

export default function Cadastros() {
  const { podeGerenciar, ehAdmin } = useAuth()
  const [aba, setAba] = useState<Aba>('empresas')

  return (
    <>
      <div className="barra">
        <h2>Cadastros</h2>
      </div>
      <div className="abas" role="tablist">
        <button role="tab" aria-selected={aba === 'empresas'} className={aba === 'empresas' ? 'ativa' : ''} onClick={() => setAba('empresas')}>Empresas</button>
        <button role="tab" aria-selected={aba === 'setores'} className={aba === 'setores' ? 'ativa' : ''} onClick={() => setAba('setores')}>Setores</button>
      </div>
      {aba === 'empresas' && <Empresas pode={podeGerenciar} />}
      {aba === 'setores' && <Setores pode={ehAdmin} />}
    </>
  )
}

function Aviso({ pode, quem }: { pode: boolean; quem: string }) {
  return pode ? null : <p className="sub">Somente consulta. Quem pode alterar: {quem}.</p>
}

/** Nome com edição no próprio lugar (só para quem pode). */
function NomeEditavel({ nome, pode, salvar }: { nome: string; pode: boolean; salvar: (n: string) => Promise<unknown> }) {
  const [editando, setEditando] = useState(false)
  const [valor, setValor] = useState(nome)
  if (!editando) {
    return (
      <>
        {nome}
        {pode && <button className="link" onClick={() => { setValor(nome); setEditando(true) }}>editar</button>}
      </>
    )
  }
  return (
    <form className="linha" onSubmit={async (e) => { e.preventDefault(); await salvar(valor); setEditando(false) }}>
      <input autoFocus value={valor} onChange={(e) => setValor(e.target.value)} maxLength={150} required />
      <button className="primario">Salvar</button>
      <button type="button" onClick={() => setEditando(false)}>Cancelar</button>
    </form>
  )
}

const soDigitos = (v: string) => v.replace(/\D/g, '').slice(0, 10)

/** Nome da empresa com edição no próprio lugar: número (só dígitos) e nome. */
function EmpresaEditavel({ c, pode, salvar }: { c: Cliente; pode: boolean; salvar: (numero: string, nome: string) => Promise<unknown> }) {
  const [editando, setEditando] = useState(false)
  const [numero, setNumero] = useState(c.numero)
  const [nome, setNome] = useState(c.nome)
  if (!editando) {
    return (
      <>
        {c.nome}
        {pode && <button className="link" onClick={() => { setNumero(c.numero); setNome(c.nome); setEditando(true) }}>editar</button>}
      </>
    )
  }
  return (
    <form className="linha" onSubmit={async (e) => { e.preventDefault(); await salvar(numero, nome); setEditando(false) }}>
      <input className="campo-numero" inputMode="numeric" value={numero} onChange={(e) => setNumero(soDigitos(e.target.value))} placeholder="Nº" aria-label="Número da empresa" required />
      <input autoFocus value={nome} onChange={(e) => setNome(e.target.value)} maxLength={150} aria-label="Nome da empresa" required />
      <button className="primario">Salvar</button>
      <button type="button" onClick={() => setEditando(false)}>Cancelar</button>
    </form>
  )
}

function Situacao({ ativo, pode, alternar }: { ativo: boolean; pode: boolean; alternar: () => void }) {
  if (!pode) return <span className={`selo ${ativo ? 'aberto' : 'cancelado'}`}>{ativo ? 'Ativo' : 'Inativo'}</span>
  return (
    <label className="chk">
      <input type="checkbox" checked={ativo} onChange={alternar} /> {ativo ? 'Ativo' : 'Inativo'}
    </label>
  )
}

// ---------------------------------------------------------------- Empresas
function Empresas({ pode }: { pode: boolean }) {
  const [lista, setLista] = useState<Cliente[]>([])
  const [nome, setNome] = useState('')
  const [numero, setNumero] = useState('')
  const [erro, setErro] = useState('')

  const carregar = useCallback(() => { api.get<Cliente[]>('/clientes').then(setLista) }, [])
  useEffect(carregar, [carregar])

  const agir = (fn: () => Promise<unknown>) => fn().then(carregar).catch((e) => setErro(e.message))

  const criar = async (e: FormEvent) => {
    e.preventDefault()
    setErro('')
    await agir(async () => { await api.post('/clientes', { numero, nome }); setNome(''); setNumero('') })
  }

  return (
    <>
    <SaudeCadastral />
    <div className="grade">
      <section className="cartao">
        <h3>Empresas atendidas</h3>
        <Aviso pode={pode} quem="administrador e gestor" />
        {erro && <p className="erro">{erro}</p>}
        <table>
          <thead><tr><th>Número</th><th>Nome</th><th>CNPJ</th><th>Regime</th><th>Contrato</th><th>Situação</th>{pode && <th></th>}</tr></thead>
          <tbody>
            {lista.map((c) => (
              <tr key={c.id}>
                <td className="num">{c.numero}</td>
                <td><EmpresaEditavel c={c} pode={pode} salvar={(n, nm) => agir(() => api.patch(`/clientes/${c.id}`, { numero: n, nome: nm }))} /></td>
                <td>{c.cnpjFormatado ?? <span className="sub">—</span>}</td>
                <td>{c.regimeTributario ? ROTULO_REGIME[c.regimeTributario] : <span className="sub">—</span>}</td>
                <td><span className={`selo ${c.statusContratual === 'ATIVO' ? 'concluido' : c.statusContratual === 'ENCERRADO' || c.statusContratual === 'SUSPENSO' ? 'cancelado' : 'aberto'}`}>{ROTULO_STATUS[c.statusContratual]}</span></td>
                <td><Situacao ativo={c.ativo} pode={pode} alternar={() => agir(() => api.patch(`/clientes/${c.id}`, { ativo: !c.ativo }))} /></td>
                {pode && <td className="acoes-linha"><Link to={`/cadastros/empresas/${c.id}`}>Ficha</Link> <BotaoApagar nome={c.nome} aviso="Processos já concluídos ou cancelados dela continuam existindo, sem o vínculo com a empresa." apagar={() => { setErro(''); agir(() => api.del(`/clientes/${c.id}`)) }} /></td>}
              </tr>
            ))}
            {lista.length === 0 && <tr><td colSpan={pode ? 7 : 6} className="vazio">Nenhuma empresa cadastrada.</td></tr>}
          </tbody>
        </table>
      </section>
      {pode && (
        <form className="cartao" onSubmit={criar}>
          <h3>Nova empresa</h3>
          <label>Número da empresa<input required inputMode="numeric" pattern="\d{1,10}" maxLength={10} placeholder="Ex.: 1042" value={numero} onChange={(e) => setNumero(soDigitos(e.target.value))} /></label>
          <small>Código interno: de 1 a 10 dígitos, diferente do de outras empresas. É o número que identifica os processos da empresa.</small>
          <label>Nome<input required maxLength={150} value={nome} onChange={(e) => setNome(e.target.value)} /></label>
          <button className="primario">Adicionar empresa</button>
          <small>Depois de criar, abra a <b>Ficha</b> para cadastrar CNPJ, regime, sócios, certificado digital e procurações.</small>
        </form>
      )}
    </div>
    </>
  )
}

// ---------------------------------------------------------------- Entregas
// ---------------------------------------------------------------- Setores
function Setores({ pode }: { pode: boolean }) {
  const [lista, setLista] = useState<Setor[]>([])
  const [nome, setNome] = useState('')
  const [erro, setErro] = useState('')

  const carregar = useCallback(() => { api.get<Setor[]>('/setores').then(setLista) }, [])
  useEffect(carregar, [carregar])

  const agir = (fn: () => Promise<unknown>) => fn().then(carregar).catch((e) => setErro(e.message))

  const criar = async (e: FormEvent) => {
    e.preventDefault()
    setErro('')
    await agir(async () => { await api.post('/setores', { nome }); setNome('') })
  }

  return (
    <div className="grade">
      <section className="cartao">
        <h3>Setores</h3>
        <p className="sub">Cada setor tem as suas entregas, criadas na tela <b>Entregas</b>; um setor não vê nem usa as do outro.</p>
        <Aviso pode={pode} quem="administrador" />
        {erro && <p className="erro">{erro}</p>}
        <table>
          <thead><tr><th>Setor</th><th>Entregas</th><th>Situação</th>{pode && <th></th>}</tr></thead>
          <tbody>
            {lista.map((s) => (
              <tr key={s.id}>
                <td>
                  <NomeEditavel nome={s.nome} pode={pode} salvar={(n) => agir(() => api.patch(`/setores/${s.id}`, { nome: n }))} />
                </td>
                <td>
                  <div className="chips">
                    {s.entregas.map((e) => <span key={e.id} className={`chip ${e.ativo ? '' : 'inativo'}`}>{e.nome}</span>)}
                    {s.entregas.length === 0 && <span className="vazio">nenhuma</span>}
                  </div>
                </td>
                <td><Situacao ativo={s.ativo} pode={pode} alternar={() => agir(() => api.patch(`/setores/${s.id}`, { ativo: !s.ativo }))} /></td>
                {pode && <td><BotaoApagar nome={s.nome} aviso="As entregas deste setor também são apagadas e os funcionários dele ficam sem setor. Processos já concluídos ou cancelados continuam existindo, sem o vínculo com o setor. Não apaga se houver processo em andamento." apagar={() => { setErro(''); agir(() => api.del(`/setores/${s.id}`)) }} /></td>}
              </tr>
            ))}
            {lista.length === 0 && <tr><td colSpan={pode ? 7 : 6} className="vazio">Nenhum setor cadastrado.</td></tr>}
          </tbody>
        </table>
      </section>
      {pode && (
        <form className="cartao" onSubmit={criar}>
          <h3>Novo setor</h3>
          <label>Nome<input required maxLength={100} value={nome} onChange={(e) => setNome(e.target.value)} /></label>
          <button className="primario">Adicionar setor</button>
        </form>
      )}
    </div>
  )
}
