import { useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { api, type CampoExtra, type Detalhe, type Pagina, type Processo } from '../api'
import { useAuth } from '../auth'
import { fmtData } from '../formato'

/** Processos dos quais este depende: enquanto algum não estiver concluído, o processo não avança. */
export function Dependencias({ d, recarregar }: { d: Detalhe; recarregar: () => unknown }) {
  const { podeGerenciar } = useAuth()
  const p = d.processo
  const [busca, setBusca] = useState('')
  const [achados, setAchados] = useState<Processo[]>([])
  const [erro, setErro] = useState('')
  useEffect(() => {
    if (busca.trim().length < 2) { setAchados([]); return }
    const t = setTimeout(() => api.get<Pagina<Processo>>(`/processos?busca=${encodeURIComponent(busca)}&tamanho=6`).then((r) => setAchados(r.content.filter((x) => x.id !== p.id))).catch(() => {}), 250)
    return () => clearTimeout(t)
  }, [busca, p.id])
  const agir = async (fn: () => Promise<unknown>) => { setErro(''); try { await fn(); await recarregar() } catch (e) { setErro((e as Error).message) } }
  if (d.dependencias.length === 0 && !(podeGerenciar && p.status === 'ABERTO')) return null
  const pendentes = d.dependencias.filter((x) => !x.resolvida)
  return (
    <section className="cartao">
      <h3>Dependências {pendentes.length > 0 && <span className="selo atrasado">{pendentes.length} pendente(s)</span>}</h3>
      {d.dependencias.length === 0 && <p className="sub">Este processo não depende de outro.</p>}
      {pendentes.length > 0 && <p className="sub">O processo só avança quando os de baixo estiverem concluídos.</p>}
      <ul className="ficha-lista">
        {d.dependencias.map((x) => (
          <li key={x.id}>
            <span className={`selo ${x.resolvida ? 'concluido' : 'atrasado'}`}>{x.resolvida ? 'Concluído' : x.status === 'CANCELADO' ? 'Cancelado' : x.etapaAtual ?? 'Em andamento'}</span>
            <span><Link to={`/processos/${x.predecessorId}`}><b>{x.titulo}</b></Link> <small className="sub">{x.numero}{x.empresa ? ` · ${x.empresa}` : ''}</small></span>
            {podeGerenciar && <button type="button" className="link" onClick={() => confirm('Remover esta dependência?') && agir(() => api.del(`/processos/${p.id}/dependencias/${x.id}`))}>remover</button>}
          </li>
        ))}
      </ul>
      {podeGerenciar && p.status === 'ABERTO' && (
        <div>
          <input placeholder="Adicionar dependência: busque o processo pelo título ou número…" value={busca} onChange={(e) => setBusca(e.target.value)} />
          {achados.length > 0 && (
            <ul className="ficha-lista">
              {achados.map((x) => <li key={x.id}><button type="button" className="link" onClick={() => agir(async () => { await api.post(`/processos/${p.id}/dependencias`, { predecessorId: x.id }); setBusca('') })}>{x.titulo} · {x.numero}</button></li>)}
            </ul>
          )}
        </div>
      )}
      {erro && <p className="erro">{erro}</p>}
    </section>
  )
}

/** Campos extras da entrega, tipados, para preencher no processo. */
export function CamposExtras({ d, podeMexer, recarregar }: { d: Detalhe; podeMexer: boolean; recarregar: () => unknown }) {
  const p = d.processo
  const campos = d.campos
  const inicial = (c: CampoExtra) => { const v = p.camposExtras?.[c.chave]; return v === undefined || v === null ? '' : String(v) }
  const [v, setV] = useState<Record<string, string>>({})
  const [erro, setErro] = useState('')
  const [ok, setOk] = useState('')
  useEffect(() => { setV(Object.fromEntries(campos.map((c) => [c.chave, inicial(c)]))) }, [d]) // eslint-disable-line react-hooks/exhaustive-deps
  if (campos.length === 0) return null
  const editavel = podeMexer && p.status === 'ABERTO'

  const salvar = async (e: FormEvent) => {
    e.preventDefault()
    setErro(''); setOk('')
    const mudou = Object.fromEntries(campos.filter((c) => (v[c.chave] ?? '') !== inicial(c)).map((c) => [c.chave, v[c.chave] ?? '']))
    if (Object.keys(mudou).length === 0) { setOk('Nada mudou.'); return }
    try { await api.patch(`/processos/${p.id}/campos`, { valores: mudou }); setOk('Campos salvos.'); await recarregar() } catch (err) { setErro((err as Error).message) }
  }

  return (
    <form className="cartao" onSubmit={salvar}>
      <h3>Campos da entrega</h3>
      <div className="ficha-form">
        {campos.map((c) => (
          <label key={c.chave}>{c.rotulo}{c.obrigatorio && <span className="so-gestor"> · obrigatório para concluir</span>}
            {c.tipo === 'SELECAO' ? (
              <select disabled={!editavel} value={v[c.chave] ?? ''} onChange={(e) => setV({ ...v, [c.chave]: e.target.value })}>
                <option value="">—</option>{c.opcoes.map((o) => <option key={o}>{o}</option>)}
              </select>
            ) : c.tipo === 'DATA' ? (
              <input type="date" disabled={!editavel} value={v[c.chave] ?? ''} onChange={(e) => setV({ ...v, [c.chave]: e.target.value })} />
            ) : (
              <input disabled={!editavel} inputMode={c.tipo === 'TEXTO' ? 'text' : 'decimal'} value={v[c.chave] ?? ''} placeholder={c.tipo === 'MOEDA' ? 'R$ 0,00' : undefined} maxLength={c.tipo === 'TEXTO' ? 500 : 30} onChange={(e) => setV({ ...v, [c.chave]: e.target.value })} />
            )}
            {c.tipo === 'MOEDA' && p.camposExtras?.[c.chave] !== undefined && <small className="sub">{Number(p.camposExtras[c.chave]).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' })}</small>}
            {c.tipo === 'DATA' && p.camposExtras?.[c.chave] !== undefined && <small className="sub">{fmtData(String(p.camposExtras[c.chave]))}</small>}
          </label>
        ))}
      </div>
      {erro && <p className="erro">{erro}</p>}
      {ok && <p className="sub">{ok}</p>}
      {editavel && <div className="linha-acoes"><button className="primario">Salvar campos</button></div>}
    </form>
  )
}
