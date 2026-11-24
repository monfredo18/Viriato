import { useState } from 'react'
import { api, type Fluxo } from '../api'

/**
 * Editor do fluxo de uma entrega: ordem das etapas intermediárias e transições (quem pode ir para quem), inclusive as
 * não lineares (ramificações e retornos). A etapa inicial é sempre a primeira e as de conclusão ficam por último.
 */
export default function EditorFluxo({ fluxo, recarregar, fechar }: { fluxo: Fluxo; recarregar: () => Promise<unknown>; fechar: () => void }) {
  const etapas = [...fluxo.etapas].sort((a, b) => a.ordem - b.ordem)
  const meio = etapas.filter((e) => !e.inicial && !e.finalEtapa)
  const [ordem, setOrdem] = useState<number[]>(meio.map((e) => e.id))
  const [arestas, setArestas] = useState<Set<string>>(new Set(fluxo.transicoes.map((t) => `${t.de}>${t.para}`)))
  const [erro, setErro] = useState('')
  const [ok, setOk] = useState('')
  const [ocupado, setOcupado] = useState(false)

  const exibidas = [...etapas.filter((e) => e.inicial), ...ordem.map((id) => etapas.find((e) => e.id === id)!), ...etapas.filter((e) => e.finalEtapa)]
  const mover = (i: number, d: -1 | 1) => {
    const n = [...ordem]
    const j = i + d
    if (j < 0 || j >= n.length) return
    ;[n[i], n[j]] = [n[j], n[i]]
    setOrdem(n)
  }
  const alternar = (de: number, para: number) => {
    const n = new Set(arestas)
    const k = `${de}>${para}`
    if (n.has(k)) n.delete(k); else n.add(k)
    setArestas(n)
  }

  const rodar = async (fn: () => Promise<unknown>, msg: string) => {
    setErro(''); setOk(''); setOcupado(true)
    try { await fn(); await recarregar(); setOk(msg) } catch (e) { setErro((e as Error).message) } finally { setOcupado(false) }
  }
  const salvarOrdem = () => rodar(() => api.put(`/fluxos/${fluxo.id}/ordem`, { etapaIds: ordem }), 'Ordem salva.')
  const salvarTransicoes = () => rodar(() => api.put(`/fluxos/${fluxo.id}/transicoes`, { transicoes: [...arestas].map((k) => { const [de, para] = k.split('>').map(Number); return { de, para } }) }), 'Transições salvas.')
  const ordemMudou = ordem.some((id, i) => id !== meio[i]?.id)

  return (
    <div className="fundo" onClick={fechar}>
      <div className="modal modal-largo" onClick={(e) => e.stopPropagation()}>
        <h3>Editar o fluxo "{fluxo.nome}"</h3>

        <div className="config-secao">
          <h4>1. Ordem das etapas</h4>
          <p className="sub">Só as etapas do meio mudam de lugar. Em fluxo em sequência, as transições acompanham a nova ordem.</p>
          <ol className="etapas-lista">
            {exibidas.map((e) => {
              const i = ordem.indexOf(e.id)
              return (
                <li key={e.id}>
                  <span className="etapa-nome"><b>{e.nome}</b>{e.inicial && <small> · inicial</small>}{e.finalEtapa && <small> · conclusão</small>}</span>
                  {i >= 0 && <span className="acoes-linha"><button type="button" className="link" disabled={i === 0} onClick={() => mover(i, -1)} aria-label={`Subir ${e.nome}`}>▲</button><button type="button" className="link" disabled={i === ordem.length - 1} onClick={() => mover(i, 1)} aria-label={`Descer ${e.nome}`}>▼</button></span>}
                </li>
              )
            })}
          </ol>
          <button type="button" disabled={ocupado || !ordemMudou} onClick={salvarOrdem}>Salvar a ordem</button>
        </div>

        <div className="config-secao">
          <h4>2. Transições: de uma etapa (linha) para outra (coluna)</h4>
          <p className="sub">Marque para onde cada etapa pode levar o processo. Uma etapa pode ter várias saídas (por exemplo, Revisão leva a Concluído ou volta para Análise). Toda etapa precisa ser alcançável a partir da inicial e chegar a uma conclusão.</p>
          <div className="tabela-wrap"><table className="matriz">
            <thead><tr><th>De ↓ · Para →</th>{exibidas.map((p) => <th key={p.id}>{p.nome}</th>)}</tr></thead>
            <tbody>
              {exibidas.map((de) => (
                <tr key={de.id}>
                  <th>{de.nome}</th>
                  {exibidas.map((para) => {
                    const bloqueada = de.id === para.id || de.finalEtapa || para.inicial
                    return <td key={para.id}>{bloqueada ? <span className="sub">·</span> : <input type="checkbox" aria-label={`${de.nome} para ${para.nome}`} checked={arestas.has(`${de.id}>${para.id}`)} onChange={() => alternar(de.id, para.id)} />}</td>
                  })}
                </tr>
              ))}
            </tbody>
          </table></div>
          <p className="sub">{[...arestas].map((k) => { const [a, b] = k.split('>').map(Number); return `${etapas.find((e) => e.id === a)?.nome} → ${etapas.find((e) => e.id === b)?.nome}` }).join('  ·  ') || 'Nenhuma transição marcada.'}</p>
          <button type="button" className="primario" disabled={ocupado} onClick={salvarTransicoes}>Salvar as transições</button>
        </div>

        {erro && <p className="erro">{erro}</p>}
        {ok && <p className="sub">{ok}</p>}
        <div className="acoes"><button type="button" onClick={fechar}>Fechar</button></div>
      </div>
    </div>
  )
}
