import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { api, type Cliente, type Entrega, type Fluxo, type Setor } from '../api'
import { useAuth } from '../auth'

type Aba = 'empresas' | 'setores' | 'entregas'

export default function Cadastros() {
  const { podeGerenciar } = useAuth()
  const [aba, setAba] = useState<Aba>('empresas')

  return (
    <>
      <div className="barra">
        <h2>Cadastros</h2>
      </div>
      <div className="abas" role="tablist">
        <button role="tab" aria-selected={aba === 'empresas'} className={aba === 'empresas' ? 'ativa' : ''} onClick={() => setAba('empresas')}>Empresas</button>
        <button role="tab" aria-selected={aba === 'setores'} className={aba === 'setores' ? 'ativa' : ''} onClick={() => setAba('setores')}>Setores</button>
        <button role="tab" aria-selected={aba === 'entregas'} className={aba === 'entregas' ? 'ativa' : ''} onClick={() => setAba('entregas')}>Entregas</button>
      </div>
      {aba === 'empresas' && <Empresas pode={podeGerenciar} />}
      {aba === 'setores' && <Setores pode={podeGerenciar} />}
      {aba === 'entregas' && <Entregas pode={podeGerenciar} />}
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

/** Apaga depois de confirmar; se o item estiver em uso, o servidor recusa e a mensagem aparece na tela. */
function Apagar({ nome, aviso = '', apagar }: { nome: string; aviso?: string; apagar: () => void }) {
  return (
    <button
      className="link perigo-txt"
      onClick={() => confirm(`Apagar "${nome}"? Esta ação não pode ser desfeita.${aviso ? `\n\n${aviso}` : ''}`) && apagar()}
    >
      apagar
    </button>
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
  const [erro, setErro] = useState('')

  const carregar = useCallback(() => { api.get<Cliente[]>('/clientes').then(setLista) }, [])
  useEffect(carregar, [carregar])

  const agir = (fn: () => Promise<unknown>) => fn().then(carregar).catch((e) => setErro(e.message))

  const criar = async (e: FormEvent) => {
    e.preventDefault()
    setErro('')
    await agir(async () => { await api.post('/clientes', { nome }); setNome('') })
  }

  return (
    <div className="grade">
      <section className="cartao">
        <h3>Empresas atendidas</h3>
        <Aviso pode={pode} quem="administrador e gestor" />
        {erro && <p className="erro">{erro}</p>}
        <table>
          <thead><tr><th>Nome</th><th>Situação</th>{pode && <th></th>}</tr></thead>
          <tbody>
            {lista.map((c) => (
              <tr key={c.id}>
                <td><NomeEditavel nome={c.nome} pode={pode} salvar={(n) => agir(() => api.patch(`/clientes/${c.id}`, { nome: n }))} /></td>
                <td><Situacao ativo={c.ativo} pode={pode} alternar={() => agir(() => api.patch(`/clientes/${c.id}`, { ativo: !c.ativo }))} /></td>
                {pode && <td><Apagar nome={c.nome} apagar={() => { setErro(''); agir(() => api.del(`/clientes/${c.id}`)) }} /></td>}
              </tr>
            ))}
            {lista.length === 0 && <tr><td colSpan={pode ? 3 : 2} className="vazio">Nenhuma empresa cadastrada.</td></tr>}
          </tbody>
        </table>
      </section>
      {pode && (
        <form className="cartao" onSubmit={criar}>
          <h3>Nova empresa</h3>
          <label>Nome<input required maxLength={150} value={nome} onChange={(e) => setNome(e.target.value)} /></label>
          <button className="primario">Adicionar empresa</button>
        </form>
      )}
    </div>
  )
}

// ---------------------------------------------------------------- Entregas
function Entregas({ pode }: { pode: boolean }) {
  const [lista, setLista] = useState<Entrega[]>([])
  const [fluxos, setFluxos] = useState<Fluxo[]>([])
  const [f, setF] = useState({ nome: '', fluxoId: '' })
  const [erro, setErro] = useState('')

  const carregar = useCallback(() => { api.get<Entrega[]>('/entregas').then(setLista) }, [])
  useEffect(() => {
    carregar()
    api.get<Fluxo[]>('/fluxos').then((l) => {
      setFluxos(l)
      const primeiro = l.find((x) => x.ativo)
      if (primeiro) setF((s) => (s.fluxoId ? s : { ...s, fluxoId: String(primeiro.id) }))
    })
  }, [carregar])

  const agir = (fn: () => Promise<unknown>) => fn().then(carregar).catch((e) => setErro(e.message))

  const criar = async (e: FormEvent) => {
    e.preventDefault()
    setErro('')
    await agir(async () => { await api.post('/entregas', { nome: f.nome, fluxoId: Number(f.fluxoId) }); setF({ ...f, nome: '' }) })
  }

  return (
    <div className="grade">
      <section className="cartao">
        <h3>O que pode ser entregue</h3>
        <p className="sub">Cada entrega define o fluxo (as etapas) do processo.</p>
        <Aviso pode={pode} quem="administrador e gestor" />
        {erro && <p className="erro">{erro}</p>}
        <table>
          <thead><tr><th>Entrega</th><th>Fluxo</th><th>Situação</th>{pode && <th></th>}</tr></thead>
          <tbody>
            {lista.map((e) => (
              <tr key={e.id}>
                <td><NomeEditavel nome={e.nome} pode={pode} salvar={(n) => agir(() => api.patch(`/entregas/${e.id}`, { nome: n }))} /></td>
                <td>
                  {pode ? (
                    <select value={e.fluxoId} onChange={(ev) => agir(() => api.patch(`/entregas/${e.id}`, { fluxoId: Number(ev.target.value) }))}>
                      {fluxos.map((x) => <option key={x.id} value={x.id}>{x.nome}{x.ativo ? '' : ' (inativo)'}</option>)}
                    </select>
                  ) : e.fluxoNome}
                </td>
                <td><Situacao ativo={e.ativo} pode={pode} alternar={() => agir(() => api.patch(`/entregas/${e.id}`, { ativo: !e.ativo }))} /></td>
                {pode && <td><Apagar nome={e.nome} aviso="Ela também sai da lista dos setores que a faziam." apagar={() => { setErro(''); agir(() => api.del(`/entregas/${e.id}`)) }} /></td>}
              </tr>
            ))}
            {lista.length === 0 && <tr><td colSpan={pode ? 4 : 3} className="vazio">Nenhuma entrega cadastrada.</td></tr>}
          </tbody>
        </table>
      </section>
      {pode && (
        <form className="cartao" onSubmit={criar}>
          <h3>Nova entrega</h3>
          <label>Nome<input required maxLength={150} value={f.nome} onChange={(e) => setF({ ...f, nome: e.target.value })} /></label>
          <label>Fluxo
            <select required value={f.fluxoId} onChange={(e) => setF({ ...f, fluxoId: e.target.value })}>
              {fluxos.filter((x) => x.ativo).map((x) => <option key={x.id} value={x.id}>{x.nome}</option>)}
            </select>
          </label>
          <button className="primario">Adicionar entrega</button>
        </form>
      )}
    </div>
  )
}

// ---------------------------------------------------------------- Setores
function Setores({ pode }: { pode: boolean }) {
  const [lista, setLista] = useState<Setor[]>([])
  const [entregas, setEntregas] = useState<Entrega[]>([])
  const [nome, setNome] = useState('')
  const [marcadas, setMarcadas] = useState<number[]>([])
  const [editando, setEditando] = useState<Setor | null>(null)
  const [erro, setErro] = useState('')

  const carregar = useCallback(() => {
    api.get<Setor[]>('/setores').then(setLista)
    api.get<Entrega[]>('/entregas').then(setEntregas)
  }, [])
  useEffect(carregar, [carregar])

  const agir = (fn: () => Promise<unknown>) => fn().then(carregar).catch((e) => setErro(e.message))

  const criar = async (e: FormEvent) => {
    e.preventDefault()
    setErro('')
    await agir(async () => { await api.post('/setores', { nome, entregaIds: marcadas }); setNome(''); setMarcadas([]) })
  }

  return (
    <div className="grade">
      <section className="cartao">
        <h3>Setores e o que cada um entrega</h3>
        <Aviso pode={pode} quem="administrador e gestor" />
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
                    {pode && <button className="link" onClick={() => setEditando(s)}>escolher</button>}
                  </div>
                </td>
                <td><Situacao ativo={s.ativo} pode={pode} alternar={() => agir(() => api.patch(`/setores/${s.id}`, { ativo: !s.ativo }))} /></td>
                {pode && <td><Apagar nome={s.nome} aviso="Os funcionários deste setor ficarão sem setor." apagar={() => { setErro(''); agir(() => api.del(`/setores/${s.id}`)) }} /></td>}
              </tr>
            ))}
            {lista.length === 0 && <tr><td colSpan={pode ? 4 : 3} className="vazio">Nenhum setor cadastrado.</td></tr>}
          </tbody>
        </table>
      </section>
      {pode && (
        <form className="cartao" onSubmit={criar}>
          <h3>Novo setor</h3>
          <label>Nome<input required maxLength={100} value={nome} onChange={(e) => setNome(e.target.value)} /></label>
          <SeletorEntregas entregas={entregas} marcadas={marcadas} mudar={setMarcadas} />
          <button className="primario">Adicionar setor</button>
        </form>
      )}
      {editando && (
        <EditarEntregasDoSetor
          setor={editando}
          entregas={entregas}
          fechar={() => setEditando(null)}
          salvar={(ids) => agir(() => api.patch(`/setores/${editando.id}`, { entregaIds: ids })).then(() => setEditando(null))}
        />
      )}
    </div>
  )
}

function SeletorEntregas({ entregas, marcadas, mudar }: { entregas: Entrega[]; marcadas: number[]; mudar: (ids: number[]) => void }) {
  const alternar = (id: number) => mudar(marcadas.includes(id) ? marcadas.filter((x) => x !== id) : [...marcadas, id])
  const ativas = entregas.filter((e) => e.ativo || marcadas.includes(e.id))
  return (
    <fieldset className="caixas">
      <legend>Entregas que este setor faz</legend>
      {ativas.map((e) => (
        <label key={e.id} className="chk">
          <input type="checkbox" checked={marcadas.includes(e.id)} onChange={() => alternar(e.id)} /> {e.nome}
        </label>
      ))}
      {ativas.length === 0 && <span className="vazio">Cadastre entregas primeiro.</span>}
    </fieldset>
  )
}

function EditarEntregasDoSetor({ setor, entregas, fechar, salvar }: { setor: Setor; entregas: Entrega[]; fechar: () => void; salvar: (ids: number[]) => void }) {
  const [marcadas, setMarcadas] = useState<number[]>(setor.entregas.map((e) => e.id))
  return (
    <div className="fundo" onClick={fechar}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h3>Entregas do setor {setor.nome}</h3>
        <SeletorEntregas entregas={entregas} marcadas={marcadas} mudar={setMarcadas} />
        <div className="acoes">
          <button onClick={fechar}>Cancelar</button>
          <button className="primario" onClick={() => salvar(marcadas)}>Salvar</button>
        </div>
      </div>
    </div>
  )
}
