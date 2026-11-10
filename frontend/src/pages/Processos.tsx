import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { api, type Fluxo, type Pagina, type Processo, type Status, type Usuario } from '../api'

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
  const [novo, setNovo] = useState(false)
  const [erro, setErro] = useState('')

  const carregar = useCallback(() => {
    const q = new URLSearchParams({ pagina: String(pagina), tamanho: '15' })
    if (busca) q.set('busca', busca)
    if (status) q.set('status', status)
    if (meus) q.set('meus', 'true')
    if (atrasados) q.set('atrasados', 'true')
    api.get<Pagina<Processo>>(`/processos?${q}`).then(setDados).catch((e) => setErro(e.message))
  }, [pagina, busca, status, meus, atrasados])

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
        <input placeholder="Buscar por título ou número…" value={busca} onChange={(e) => { setBusca(e.target.value); setPagina(0) }} />
        <select value={status} onChange={(e) => { setStatus(e.target.value as Status | ''); setPagina(0) }}>
          <option value="">Todos</option>
          <option value="ABERTO">Abertos</option>
          <option value="CONCLUIDO">Concluídos</option>
          <option value="CANCELADO">Cancelados</option>
        </select>
        <label className="chk"><input type="checkbox" checked={meus} onChange={(e) => { setMeus(e.target.checked); setPagina(0) }} /> Meus</label>
        <label className="chk"><input type="checkbox" checked={atrasados} onChange={(e) => { setAtrasados(e.target.checked); setPagina(0) }} /> Atrasados</label>
      </div>

      {erro && <p className="erro">{erro}</p>}
      <div className="tabela-wrap"><table>
        <thead>
          <tr><th>Número</th><th>Título</th><th>Etapa</th><th>Responsável</th><th>Prazo</th><th>Situação</th></tr>
        </thead>
        <tbody>
          {dados?.content.map((p) => (
            <tr key={p.id}>
              <td><Link to={`/processos/${p.id}`}>{p.numero}</Link></td>
              <td><Link to={`/processos/${p.id}`} className="titulo-link">{p.titulo}</Link></td>
              <td>{p.etapaAtual}</td>
              <td>{p.responsavelNome ?? '—'}</td>
              <td>{fmtData(p.prazoEtapa)}</td>
              <td><Selo p={p} /></td>
            </tr>
          ))}
          {dados && dados.content.length === 0 && <tr><td colSpan={6} className="vazio">Nenhum processo encontrado.</td></tr>}
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
  const [fluxos, setFluxos] = useState<Fluxo[]>([])
  const [usuarios, setUsuarios] = useState<Usuario[]>([])
  const [f, setF] = useState({ titulo: '', descricao: '', fluxoId: '', responsavelId: '' })
  const [erro, setErro] = useState('')

  useEffect(() => {
    api.get<Fluxo[]>('/fluxos').then((l) => {
      const ativos = l.filter((x) => x.ativo)
      setFluxos(ativos)
      if (ativos[0]) setF((s) => ({ ...s, fluxoId: String(ativos[0].id) }))
    })
    api.get<Usuario[]>('/usuarios').then((l) => setUsuarios(l.filter((u) => u.ativo)))
  }, [])

  const enviar = async (e: FormEvent) => {
    e.preventDefault()
    try {
      await api.post('/processos', {
        titulo: f.titulo,
        descricao: f.descricao || null,
        fluxoId: Number(f.fluxoId),
        responsavelId: f.responsavelId ? Number(f.responsavelId) : null,
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
        <label>Título<input required maxLength={200} value={f.titulo} onChange={(e) => setF({ ...f, titulo: e.target.value })} /></label>
        <label>Descrição<textarea rows={3} value={f.descricao} onChange={(e) => setF({ ...f, descricao: e.target.value })} /></label>
        <label>Fluxo
          <select required value={f.fluxoId} onChange={(e) => setF({ ...f, fluxoId: e.target.value })}>
            {fluxos.map((x) => <option key={x.id} value={x.id}>{x.nome}</option>)}
          </select>
        </label>
        <label>Responsável
          <select value={f.responsavelId} onChange={(e) => setF({ ...f, responsavelId: e.target.value })}>
            <option value="">— sem responsável —</option>
            {usuarios.map((u) => <option key={u.id} value={u.id}>{u.nome}</option>)}
          </select>
        </label>
        {erro && <p className="erro">{erro}</p>}
        <div className="acoes">
          <button type="button" onClick={fechar}>Cancelar</button>
          <button className="primario">Criar</button>
        </div>
      </form>
    </div>
  )
}
