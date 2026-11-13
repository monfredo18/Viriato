import { useCallback, useEffect, useState } from 'react'
import { api, type Painel as PainelDados, type PainelEtapa, type Setor } from '../api'
import { useAuth } from '../auth'

const PERIODOS = [30, 90, 180, 365]
const n = (v: number) => v.toLocaleString('pt-BR')
const dec = (v: number) => v.toLocaleString('pt-BR', { minimumFractionDigits: 1, maximumFractionDigits: 1 })
/** Menos de um dia em horas; a partir daí em dias com uma casa. */
const duracao = (horas: number) => (horas < 24 ? `${Math.max(1, Math.round(horas))} h` : `${dec(horas / 24)} d`)

/** Barra fina dividida em "no prazo" (azul) e "atrasados" (vermelho); o comprimento é proporcional ao maior total da lista. */
export function BarraCarga({ abertos, atrasados, maximo, rotulo }: { abertos: number; atrasados: number; maximo: number; rotulo: string }) {
  const noPrazo = abertos - atrasados
  const largura = maximo > 0 ? (abertos / maximo) * 100 : 0
  return (
    <div className="barra-trilho" role="img" aria-label={rotulo} title={rotulo}>
      {abertos > 0 && (
        <div className="barra-grupo" style={{ width: `${largura}%` }}>
          {noPrazo > 0 && <span className="seg seg-azul" style={{ flex: noPrazo }} />}
          {atrasados > 0 && <span className="seg seg-vermelho" style={{ flex: atrasados }} />}
        </div>
      )}
    </div>
  )
}

function BarraEtapa({ e, escala, mostrarFluxo }: { e: PainelEtapa; escala: number; mostrarFluxo: boolean }) {
  const mediaDias = e.mediaHoras / 24
  const acima = e.prazoDias !== null && mediaDias > e.prazoDias
  const pct = (d: number) => `${Math.min(100, (d / escala) * 100)}%`
  const detalhe = [
    e.prazoDias !== null ? `prazo ${e.prazoDias} d` : 'sem prazo definido',
    `${n(e.passagens)} ${e.passagens === 1 ? 'passagem' : 'passagens'}`,
    e.foraDoPrazo > 0 ? `${n(e.foraDoPrazo)} fora do prazo` : null,
  ].filter(Boolean).join(' · ')
  const rotulo = `${e.etapa}: média de ${duracao(e.mediaHoras)}. ${detalhe}.`
  return (
    <li className="etapa-barra" title={rotulo}>
      <span className="etapa-rotulo">
        <b>{e.etapa}</b>
        <small>{mostrarFluxo ? `${e.fluxo} · ` : ''}{detalhe}</small>
      </span>
      <div className="barra-trilho barra-grossa" role="img" aria-label={rotulo}>
        <span className={`barra-preench ${acima ? 'seg-vermelho' : 'seg-azul'}`} style={{ width: pct(mediaDias) }} />
        {e.prazoDias !== null && <span className="marca-prazo" style={{ left: pct(e.prazoDias) }} aria-hidden="true" />}
      </div>
      <span className="etapa-valor">{duracao(e.mediaHoras)}{acima && <small className="acima"> acima do prazo</small>}</span>
    </li>
  )
}

/**
 * Painel de indicadores. O servidor decide a abrangência pelo papel: o administrador vê todos os setores (e pode
 * filtrar por um); gestor e usuário veem o próprio setor.
 */
export default function Painel() {
  const { ehAdmin } = useAuth()
  const [dados, setDados] = useState<PainelDados | null>(null)
  const [dias, setDias] = useState(90)
  const [setorId, setSetorId] = useState('')
  const [setores, setSetores] = useState<Setor[]>([])
  const [erro, setErro] = useState('')

  const carregar = useCallback(() => {
    const q = new URLSearchParams({ dias: String(dias) })
    if (ehAdmin && setorId) q.set('setorId', setorId)
    api.get<PainelDados>(`/indicadores?${q}`).then((d) => { setDados(d); setErro('') }).catch((e) => setErro(e.message))
  }, [dias, setorId, ehAdmin])

  useEffect(() => { carregar() }, [carregar])
  useEffect(() => { if (ehAdmin) api.get<Setor[]>('/setores').then((l) => setSetores(l.filter((s) => s.ativo))) }, [ehAdmin])

  const r = dados?.resumo
  const maxSetor = Math.max(0, ...(dados?.setores.map((s) => s.abertos) ?? []))
  const maxFunc = Math.max(0, ...(dados?.funcionarios.map((f) => f.abertos) ?? []))
  const escalaEtapa = Math.max(1, ...(dados?.etapas.flatMap((e) => [e.mediaHoras / 24, e.prazoDias ?? 0]) ?? []))
  const fluxosDistintos = new Set(dados?.etapas.map((e) => e.fluxo)).size

  return (
    <>
      <div className="barra">
        <h2>Painel</h2>
        <span className="espaco" />
        {dados && <small className="sub-inline">Atualizado às {new Date(dados.geradoEm).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' })}</small>}
        <button type="button" onClick={carregar}>Atualizar</button>
      </div>

      <div className="filtros">
        <select value={dias} onChange={(e) => setDias(Number(e.target.value))} aria-label="Período">
          {PERIODOS.map((p) => <option key={p} value={p}>Últimos {p} dias</option>)}
        </select>
        {ehAdmin && (
          <select value={setorId} onChange={(e) => setSetorId(e.target.value)} aria-label="Setor">
            <option value="">Todos os setores</option>
            {setores.map((s) => <option key={s.id} value={s.id}>{s.nome}</option>)}
          </select>
        )}
      </div>
      {erro && <p className="erro">{erro}</p>}
      {dados && <p className="sub">{dados.escopo}. Concluídos, cancelados e tempos por etapa consideram os últimos {dados.periodoDias} dias; os processos em andamento aparecem todos.</p>}

      {r && dados && (
        <>
          <div className="resumo-caixas kpis">
            <div className="caixa-azul"><b>{n(r.abertos)}</b><span>Em andamento</span></div>
            <div className="caixa-vermelha"><b>{n(r.atrasados)}</b><span>Atrasados{r.abertos > 0 ? ` (${Math.round((r.atrasados / r.abertos) * 100)}%)` : ''}</span></div>
            <div className="caixa-ouro"><b>{n(r.concluidos)}</b><span>Concluídos</span></div>
            <div><b>{n(r.cancelados)}</b><span>Cancelados</span></div>
            <div className="caixa-total"><b>{r.tempoMedioConclusaoDias === null ? '—' : `${dec(r.tempoMedioConclusaoDias)} d`}</b><span>Tempo médio até concluir</span></div>
          </div>

          <section className="cartao">
            <h3>{dados.todosOsSetores ? 'Atrasados por setor' : 'Situação do setor'}</h3>
            <div className="legenda"><span><i className="seg-azul" /> no prazo</span><span><i className="seg-vermelho" /> atrasados</span></div>
            <div className="tabela-wrap tabela-painel"><table>
              <thead><tr><th>Setor</th><th className="num">Em andamento</th><th className="num">Atrasados</th><th className="num">Concluídos</th><th className="num">Cancelados</th><th className="col-barra" aria-label="Distribuição" /></tr></thead>
              <tbody>
                {dados.setores.map((s) => (
                  <tr key={s.setorId ?? 'sem'}>
                    <td>{s.setor}</td>
                    <td className="num">{n(s.abertos)}</td>
                    <td className={`num ${s.atrasados > 0 ? 'texto-atraso' : ''}`}>{n(s.atrasados)}</td>
                    <td className="num">{n(s.concluidos)}</td>
                    <td className="num">{n(s.cancelados)}</td>
                    <td className="col-barra"><BarraCarga abertos={s.abertos} atrasados={s.atrasados} maximo={maxSetor} rotulo={`${s.setor}: ${s.abertos} em andamento, ${s.atrasados} atrasados`} /></td>
                  </tr>
                ))}
                {dados.setores.length === 0 && <tr><td colSpan={6} className="vazio">Nenhum setor para mostrar.</td></tr>}
              </tbody>
            </table></div>
          </section>

          <section className="cartao">
            <h3>Tempo médio por etapa</h3>
            <p className="sub">Quanto tempo os processos ficaram em cada etapa antes de seguir. A marca dourada é o prazo previsto da etapa; a barra fica vermelha quando a média passa dele. Etapa atual e etapa final não entram.</p>
            {dados.etapas.length === 0 ? <p className="vazio">Nenhuma etapa concluída no período.</p> : (
              <ul className="etapas-barras">
                {dados.etapas.map((e) => <BarraEtapa key={`${e.fluxo}|${e.etapa}`} e={e} escala={escalaEtapa} mostrarFluxo={fluxosDistintos > 1} />)}
              </ul>
            )}
          </section>

          <section className="cartao">
            <h3>Carga por funcionário</h3>
            <div className="legenda"><span><i className="seg-azul" /> no prazo</span><span><i className="seg-vermelho" /> atrasados</span></div>
            <div className="tabela-wrap tabela-painel"><table>
              <thead><tr><th>Funcionário</th><th>Setor</th><th className="num">Em andamento</th><th className="num">Atrasados</th><th className="num">Concluídos</th><th className="col-barra" aria-label="Distribuição" /></tr></thead>
              <tbody>
                {dados.funcionarios.map((f) => (
                  <tr key={f.usuarioId}>
                    <td>{f.nome}</td>
                    <td>{f.setor ?? '—'}</td>
                    <td className="num">{n(f.abertos)}</td>
                    <td className={`num ${f.atrasados > 0 ? 'texto-atraso' : ''}`}>{n(f.atrasados)}</td>
                    <td className="num">{n(f.concluidos)}</td>
                    <td className="col-barra"><BarraCarga abertos={f.abertos} atrasados={f.atrasados} maximo={maxFunc} rotulo={`${f.nome}: ${f.abertos} em andamento, ${f.atrasados} atrasados`} /></td>
                  </tr>
                ))}
                {dados.funcionarios.length === 0 && <tr><td colSpan={6} className="vazio">Nenhum funcionário para mostrar.</td></tr>}
              </tbody>
            </table></div>
          </section>
        </>
      )}
    </>
  )
}
