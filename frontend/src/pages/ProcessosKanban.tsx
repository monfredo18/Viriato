import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, type Entrega, type Fluxo, type Pagina, type Processo } from '../api'
import { fmtData } from '../formato'

/**
 * Kanban de uma entrega: uma coluna por etapa do fluxo. Arrastar um cartão para outra coluna faz o mesmo que o botão
 * de avançar no detalhe do processo: o servidor confere a transição, os requisitos, as aprovações e as dependências.
 */
export default function ProcessosKanban() {
  const [entregas, setEntregas] = useState<Entrega[]>([])
  const [fluxos, setFluxos] = useState<Fluxo[]>([])
  const [entregaId, setEntregaId] = useState('')
  const [competencia, setCompetencia] = useState('')   // aaaa-mm
  const [abertos, setAbertos] = useState<Processo[]>([])
  const [concluidos, setConcluidos] = useState<Processo[]>([])
  const [arrastando, setArrastando] = useState<Processo | null>(null)
  const [sobre, setSobre] = useState<number | null>(null)
  const [erro, setErro] = useState('')
  const [aviso, setAviso] = useState('')

  useEffect(() => {
    Promise.all([api.get<Entrega[]>('/entregas'), api.pagina<Fluxo>('/fluxos', 0, 200)]).then(([e, f]) => {
      setEntregas(e.filter((x) => x.ativo))
      setFluxos(f.itens)
      const primeira = e.find((x) => x.ativo)
      if (primeira) setEntregaId(String(primeira.id))
    }).catch((err) => setErro(err.message))
  }, [])

  const entrega = entregas.find((e) => String(e.id) === entregaId)
  const fluxo = fluxos.find((f) => f.id === entrega?.fluxoId)

  const carregar = useCallback(() => {
    if (!entregaId) return
    const base = new URLSearchParams({ entregaId, tamanho: '100' })
    if (competencia) { base.set('competenciaAno', competencia.slice(0, 4)); base.set('competenciaMes', String(Number(competencia.slice(5, 7)))) }
    Promise.all([
      api.get<Pagina<Processo>>(`/processos?${new URLSearchParams({ ...Object.fromEntries(base), status: 'ABERTO' })}`),
      api.get<Pagina<Processo>>(`/processos?${new URLSearchParams({ ...Object.fromEntries(base), status: 'CONCLUIDO', tamanho: '30' })}`),
    ]).then(([a, c]) => { setAbertos(a.content); setConcluidos(c.content); setErro('') }).catch((e) => setErro(e.message))
  }, [entregaId, competencia])
  useEffect(carregar, [carregar])

  const permitidas = useMemo(() => {
    if (!arrastando || !fluxo) return new Set<number>()
    return new Set(fluxo.transicoes.filter((t) => t.de === arrastando.etapaAtualId).map((t) => t.para))
  }, [arrastando, fluxo])

  const soltar = async (etapaId: number) => {
    const p = arrastando
    setArrastando(null); setSobre(null)
    if (!p || p.etapaAtualId === etapaId) return
    setErro(''); setAviso('')
    try {
      await api.post(`/processos/${p.id}/avancar`, { etapaId })
      setAviso(`${p.numero} movido.`)
    } catch (e) { setErro((e as Error).message) }
    carregar()
  }

  if (!entregas.length && !erro) return <p className="vazio">Nenhuma entrega disponível.</p>

  return (
    <>
      <div className="filtros">
        <select value={entregaId} onChange={(e) => setEntregaId(e.target.value)} aria-label="Entrega">
          {entregas.map((e) => <option key={e.id} value={e.id}>{e.nome}{e.setorDonoNome ? ` (${e.setorDonoNome})` : ''}</option>)}
        </select>
        <label className="chk">Competência <input type="month" value={competencia} onChange={(e) => setCompetencia(e.target.value)} /></label>
        {competencia && <button type="button" className="link" onClick={() => setCompetencia('')}>limpar</button>}
      </div>
      {erro && <p className="erro">{erro}</p>}
      {aviso && !erro && <p className="sub">{aviso}</p>}
      {fluxo && (
        <div className="kanban" role="list">
          {fluxo.etapas.map((et) => {
            const cartoes = et.finalEtapa ? concluidos : abertos.filter((p) => p.etapaAtualId === et.id)
            const alvo = arrastando && permitidas.has(et.id)
            return (
              <section key={et.id} role="listitem" className={`kanban-coluna${alvo ? ' permitida' : ''}${sobre === et.id && alvo ? ' sobre' : ''}${arrastando && !alvo && arrastando.etapaAtualId !== et.id ? ' bloqueada' : ''}`}
                onDragOver={(e) => { if (alvo) { e.preventDefault(); setSobre(et.id) } }} onDragLeave={() => setSobre((s) => (s === et.id ? null : s))}
                onDrop={(e) => { e.preventDefault(); soltar(et.id) }}>
                <h4>{et.nome} <small>{cartoes.length}</small></h4>
                <div className="kanban-cartoes">
                  {cartoes.map((p) => (
                    <article key={p.id} className={`kanban-cartao${p.atrasado ? ' atrasado' : ''}${et.finalEtapa ? ' feito' : ''}`} draggable={!et.finalEtapa} onDragStart={() => setArrastando(p)} onDragEnd={() => { setArrastando(null); setSobre(null) }}>
                      <Link to={`/processos/${p.id}`}><b>{p.titulo}</b></Link>
                      <span className="sub">{p.numero} · {p.clienteNome ?? '—'}</span>
                      {!et.finalEtapa && <span className={p.atrasado ? 'texto-atraso' : 'sub'}>{p.prazoEtapa ? `vence ${fmtData(p.prazoEtapa)}` : 'sem prazo'}</span>}
                      <span className="sub">{p.responsavelNome ?? 'sem responsável'}</span>
                    </article>
                  ))}
                  {cartoes.length === 0 && <p className="vazio">—</p>}
                </div>
              </section>
            )
          })}
        </div>
      )}
      <p className="sub">Arraste um cartão para uma coluna destacada. Etapas com requisitos pendentes, aprovações ou dependências abertas respondem com o motivo.</p>
    </>
  )
}
