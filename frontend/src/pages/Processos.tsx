import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { api, type Cliente, type Entrega, type Pagina, type Processo, type Setor, type Status, type Usuario } from '../api'
import { useAuth } from '../auth'

const fmtData = (d: string | null) => (d ? new Date(d + 'T00:00:00').toLocaleDateString('pt-BR') : '—')

export function Selo({ p }: { p: Processo }) {
  if (p.status !== 'ABERTO') return <span className={`selo ${p.status.toLowerCase()}`}>{p.status === 'CONCLUIDO' ? 'Concluído' : 'Cancelado'}</span>
  return <span className={`selo ${p.atrasado ? 'atrasado' : 'aberto'}`}>{p.atrasado ? 'Atrasado' : 'Aberto'}</span>
}

export default function Processos() {
  const [dados, setDados] = useState<Pagina<Processo> | null>(null)
  const [pagina, setPagina] = useState(0)
  const [busca, setBusca] = useState('')
  const [status, setStatus] = useState<Status | ''>('ABERTO')
  const [meus, setMeus] = useState(false)
  const [atrasados, setAtrasados] = useState(false)
  const [clienteId, setClienteId] = useState('')
  const [setorId, setSetorId] = useState('')
  const [clientes, setClientes] = useState<Cliente[]>([])
  const [setores, setSetores] = useState<Setor[]>([])
  const [novo, setNovo] = useState(false)
  const [erro, setErro] = useState('')

  const carregar = useCallback(() => {
    const q = new URLSearchParams({ pagina: String(pagina), tamanho: '15' })
    if (busca) q.set('busca', busca)
    if (status) q.set('status', status)
    if (meus) q.set('meus', 'true')
    if (atrasados) q.set('atrasados', 'true')
    if (clienteId) q.set('clienteId', clienteId)
    if (setorId) q.set('setorId', setorId)
    api.get<Pagina<Processo>>(`/processos?${q}`).then(setDados).catch((e) => setErro(e.message))
  }, [pagina, busca, status, meus, atrasados, clienteId, setorId])

  useEffect(() => {
    api.get<Cliente[]>('/clientes').then(setClientes)
    api.get<Setor[]>('/setores').then(setSetores)
  }, [])

  useEffect(() => {
    const t = setTimeout(carregar, 250) // debounce da busca
    return () => clearTimeout(t)
  }, [carregar])

  return (
    <>
      <div className="barra">
        <h2>Processos</h2>
        <span className="espaco" />
        <button className="primario" onClick={() => setNovo(true)}>+ Novo processo</button>
      </div>

      <div className="filtros">
        <input placeholder="Buscar por empresa, entrega ou número…" value={busca} onChange={(e) => { setBusca(e.target.value); setPagina(0) }} />
        <select value={status} onChange={(e) => { setStatus(e.target.value as Status | ''); setPagina(0) }}>
          <option value="">Todos</option>
          <option value="ABERTO">Abertos</option>
          <option value="CONCLUIDO">Concluídos</option>
          <option value="CANCELADO">Cancelados</option>
        </select>
        <select value={clienteId} onChange={(e) => { setClienteId(e.target.value); setPagina(0) }} aria-label="Empresa">
          <option value="">Todas as empresas</option>
          {clientes.map((c) => <option key={c.id} value={c.id}>{c.nome}</option>)}
        </select>
        <select value={setorId} onChange={(e) => { setSetorId(e.target.value); setPagina(0) }} aria-label="Departamento">
          <option value="">Todos os setores</option>
          {setores.map((x) => <option key={x.id} value={x.id}>{x.nome}</option>)}
        </select>
        <label className="chk"><input type="checkbox" checked={meus} onChange={(e) => { setMeus(e.target.checked); setPagina(0) }} /> Meus</label>
        <label className="chk"><input type="checkbox" checked={atrasados} onChange={(e) => { setAtrasados(e.target.checked); setPagina(0) }} /> Atrasados</label>
      </div>

      {erro && <p className="erro">{erro}</p>}
      <div className="tabela-wrap"><table>
        <thead>
          <tr><th>Número</th><th>Empresa</th><th>Setor</th><th>Entrega</th><th>Prazo</th><th>Responsável</th><th>Situação</th></tr>
        </thead>
        <tbody>
          {dados?.content.map((p) => (
            <tr key={p.id}>
              <td><Link to={`/processos/${p.id}`}>{p.numero}</Link></td>
              <td><Link to={`/processos/${p.id}`} className="titulo-link">{p.clienteNome ?? '—'}</Link></td>
              <td>{p.setorNome ?? '—'}</td>
              <td>{p.entregaNome ?? p.titulo}</td>
              <td>{fmtData(p.prazoEtapa)}</td>
              <td>{p.responsavelNome ?? '—'}</td>
              <td><Selo p={p} /></td>
            </tr>
          ))}
          {dados && dados.content.length === 0 && <tr><td colSpan={7} className="vazio">Nenhum processo encontrado.</td></tr>}
        </tbody>
      </table></div>

      {dados && dados.totalPages > 1 && (
        <div className="paginacao">
          <button disabled={pagina === 0} onClick={() => setPagina(pagina - 1)}>‹ Anterior</button>
          <span>Página {dados.number + 1} de {dados.totalPages}</span>
          <button disabled={pagina + 1 >= dados.totalPages} onClick={() => setPagina(pagina + 1)}>Próxima ›</button>
        </div>
      )}

      {novo && <NovoProcesso fechar={() => setNovo(false)} criado={() => { setNovo(false); setPagina(0); carregar() }} />}
    </>
  )
}

function NovoProcesso({ fechar, criado }: { fechar: () => void; criado: () => void }) {
  const { usuario } = useAuth()
  const [clientes, setClientes] = useState<Cliente[]>([])
  const [setores, setSetores] = useState<Setor[]>([])
  const [entregas, setEntregas] = useState<Entrega[]>([])
  const [usuarios, setUsuarios] = useState<Usuario[]>([])
  const [f, setF] = useState({ clienteId: '', setorId: '', entregaId: '', responsavelId: String(usuario?.id ?? ''), descricao: '' })
  const [erro, setErro] = useState('')

  useEffect(() => {
    api.get<Cliente[]>('/clientes').then((l) => setClientes(l.filter((c) => c.ativo)))
    api.get<Setor[]>('/setores').then((l) => setSetores(l.filter((x) => x.ativo)))
    api.get<Entrega[]>('/entregas').then(setEntregas)
    api.get<Usuario[]>('/usuarios').then((l) => setUsuarios(l.filter((u) => u.ativo)))
  }, [])

  // Ao escolher o setor, aparecem só as entregas que ele faz.
  const setor = setores.find((x) => String(x.id) === f.setorId)
  const entregasDoSetor = (setor?.entregas ?? []).filter((e) => e.ativo)
  const entrega = entregas.find((e) => String(e.id) === f.entregaId)

  const escolherSetor = (id: string) => setF({ ...f, setorId: id, entregaId: '' })

  const enviar = async (e: FormEvent) => {
    e.preventDefault()
    setErro('')
    try {
      await api.post('/processos', {
        clienteId: Number(f.clienteId),
        setorId: Number(f.setorId),
        entregaId: Number(f.entregaId),
        responsavelId: Number(f.responsavelId),
        descricao: f.descricao || null,
      })
      criado()
    } catch (err) {
      setErro((err as Error).message)
    }
  }

  return (
    <div className="fundo" onClick={fechar}>
      <form className="modal" onClick={(e) => e.stopPropagation()} onSubmit={enviar}>
        <h3>Novo processo</h3>

        <label>Empresa
          <select required value={f.clienteId} onChange={(e) => setF({ ...f, clienteId: e.target.value })}>
            <option value="" disabled>Selecione a empresa…</option>
            {clientes.map((c) => <option key={c.id} value={c.id}>{c.nome}</option>)}
          </select>
        </label>
        {clientes.length === 0 && <small className="aviso-campo">Nenhuma empresa cadastrada. O administrador cadastra em Cadastros → Empresas.</small>}

        <label>Departamento
          <select required value={f.setorId} onChange={(e) => escolherSetor(e.target.value)}>
            <option value="" disabled>Selecione o departamento…</option>
            {setores.map((x) => <option key={x.id} value={x.id}>{x.nome}</option>)}
          </select>
        </label>

        <label>O que vai entregar
          <select required value={f.entregaId} disabled={!setor} onChange={(e) => setF({ ...f, entregaId: e.target.value })}>
            <option value="" disabled>{setor ? 'Selecione a entrega…' : 'Escolha o departamento primeiro'}</option>
            {entregasDoSetor.map((e) => <option key={e.id} value={e.id}>{e.nome}</option>)}
          </select>
        </label>
        {setor && entregasDoSetor.length === 0 && <small className="aviso-campo">Este setor ainda não tem entregas. Um gestor pode defini-las em Cadastros → Setores.</small>}
        {entrega && <small className="dica-campo">Fluxo: {entrega.fluxoNome}</small>}

        <label>Responsável
          <select required value={f.responsavelId} onChange={(e) => setF({ ...f, responsavelId: e.target.value })}>
            <option value="" disabled>Selecione o responsável…</option>
            {usuarios.map((u) => <option key={u.id} value={u.id}>{u.nome}{u.setorNome ? ` — ${u.setorNome}` : ''}</option>)}
          </select>
        </label>

        <label>Observações (opcional)
          <textarea rows={3} maxLength={10000} value={f.descricao} onChange={(e) => setF({ ...f, descricao: e.target.value })} />
        </label>

        {erro && <p className="erro">{erro}</p>}
        <div className="acoes">
          <button type="button" onClick={fechar}>Cancelar</button>
          <button className="primario">Criar processo</button>
        </div>
      </form>
    </div>
  )
}
