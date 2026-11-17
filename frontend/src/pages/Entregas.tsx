import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { api, type Entrega, type Fluxo, type RegraAutomacao, type Setor } from '../api'
import BotaoApagar from '../BotaoApagar'
import { useAuth } from '../auth'
import ConfigurarEtapa from './ConfigurarEtapa'
import { JanelaCancelamento } from './AutomacoesSecao'

/** Etapas do formulário: "Abertura" e "Concluído" são fixas; entre elas, as etapas intermediárias que a pessoa criar. */
const INTERMEDIARIAS = () => ['Análise']

/**
 * Entregas e seus fluxos em um lugar só, separados por setor: cada setor tem as suas entregas e não vê as dos outros.
 * O administrador navega por sub-abas, uma por setor; gestor e usuário veem só o próprio setor. Cada entrega traz as
 * etapas do próprio fluxo; só a etapa de conclusão tem prazo (em dias, para concluir o processo).
 */
export default function Entregas() {
  const { usuario, podeGerenciar, ehAdmin } = useAuth()
  const [setores, setSetores] = useState<Setor[]>([])
  const [setorAba, setSetorAba] = useState<number | null>(null)
  const [entregas, setEntregas] = useState<Entrega[]>([])
  const [fluxos, setFluxos] = useState<Fluxo[]>([])
  const [regras, setRegras] = useState<Record<number, RegraAutomacao[]>>({})
  const [f, setF] = useState({ nome: '', prazo: '', etapas: INTERMEDIARIAS() })
  const [erro, setErro] = useState('')
  const [erroForm, setErroForm] = useState('')
  const [config, setConfig] = useState<{ fluxoId: number; etapaId: number | null } | null>(null)
  const [apagando, setApagando] = useState<{ e: Entrega; qtd: number } | null>(null)
  const [novaEtapa, setNovaEtapa] = useState<{ fluxoId: number; nome: string } | null>(null)

  // Só administrador e gestor consultam as automações (podem ter endereços e segredos de integração).
  const carregar = useCallback(async () => {
    const [es, fs, ss] = await Promise.all([api.get<Entrega[]>('/entregas'), api.get<Fluxo[]>('/fluxos'), api.get<Setor[]>('/setores')])
    setEntregas(es)
    setFluxos(fs)
    setSetores(ss)
    if (podeGerenciar) {
      const pares = await Promise.all(fs.map(async (x) => [x.id, await api.get<RegraAutomacao[]>(`/fluxos/${x.id}/automacoes`).catch(() => [])] as const))
      setRegras(Object.fromEntries(pares))
    }
  }, [podeGerenciar])
  useEffect(() => { carregar() }, [carregar])

  // O administrador escolhe o setor pela sub-aba (a primeira, por padrão); os demais ficam no próprio setor.
  const abas = ehAdmin ? setores.filter((s) => s.ativo) : []
  const setorAtual = ehAdmin ? (setorAba ?? abas[0]?.id ?? null) : null
  const doSetor = entregas.filter((e) => (ehAdmin ? e.setorDonoId === setorAtual : true))
  const nomeSetor = ehAdmin ? abas.find((s) => s.id === setorAtual)?.nome : usuario?.setorNome

  const agir = (fn: () => Promise<unknown>) => fn().then(carregar).catch((e) => setErro(e.message))
  const podeMexer = () => ehAdmin || podeGerenciar
  const compartilhado = (e: Entrega) => entregas.filter((o) => o.id !== e.id && o.fluxoId === e.fluxoId)

  // Sem processo em andamento: confirmação simples. Com processos: pede a entrega que vai substituí-la.
  const apagar = async (e: Entrega) => {
    setErro('')
    try {
      const { emAndamento } = await api.get<{ emAndamento: number }>(`/entregas/${e.id}/pendencias`)
      if (emAndamento === 0) {
        if (!confirm(`Apagar "${e.nome}"? Esta ação não pode ser desfeita.\n\nO fluxo dela (etapas, requisitos e automações) também é apagado. Processos já concluídos ou cancelados continuam existindo, sem o vínculo.`)) return
        await api.post(`/entregas/${e.id}/apagar`, {})
        carregar()
      } else {
        setApagando({ e, qtd: emAndamento })
      }
    } catch (err) {
      setErro((err as Error).message)
    }
  }

  const criar = async (ev: FormEvent) => {
    ev.preventDefault()
    setErroForm('')
    try {
      await api.post('/entregas', {
        nome: f.nome,
        ...(ehAdmin ? { setorId: setorAtual } : {}),
        etapas: [
          { nome: 'Abertura', prazoDias: null, inicial: true, finalEtapa: false },
          ...f.etapas.filter((n) => n.trim() !== '').map((n) => ({ nome: n, prazoDias: null, inicial: false, finalEtapa: false })),
          { nome: 'Concluído', prazoDias: f.prazo === '' ? null : Number(f.prazo), inicial: false, finalEtapa: true },
        ],
      })
      setF({ nome: '', prazo: '', etapas: INTERMEDIARIAS() })
      carregar()
    } catch (err) {
      setErroForm((err as Error).message)
    }
  }

  return (
    <>
      <h2>Entregas</h2>
      <p className="sub">
        Cada setor tem as suas entregas e não vê as dos outros. Cada entrega tem o seu fluxo: as etapas pelas quais o processo passa, com requisitos, aprovação e automações.
        Só a etapa de conclusão tem prazo (para concluir o processo).
        {!podeGerenciar ? ' Somente consulta. Quem pode alterar: administrador e gestor.' : ''}
      </p>

      {ehAdmin && (
        <div className="abas" role="tablist" aria-label="Setores">
          {abas.map((s) => (
            <button key={s.id} role="tab" aria-selected={s.id === setorAtual} className={s.id === setorAtual ? 'ativa' : ''} onClick={() => setSetorAba(s.id)}>
              {s.nome} <small>({entregas.filter((e) => e.setorDonoId === s.id).length})</small>
            </button>
          ))}
          {abas.length === 0 && <span className="vazio">Crie um setor em Cadastros para começar.</span>}
        </div>
      )}
      {!ehAdmin && nomeSetor && <p className="sub"><b>Setor {nomeSetor}</b></p>}

      <div className="grade">
        <section>
          {erro && <p className="erro">{erro}</p>}
          {doSetor.map((e) => {
            const fluxo = fluxos.find((x) => x.id === e.fluxoId)
            const mexer = podeMexer()
            const outras = compartilhado(e)
            // fluxo compartilhado por mais de uma entrega (dados antigos): só o administrador configura
            const configurar = mexer && (ehAdmin || outras.length === 0)
            const regrasDoFluxo = fluxo ? regras[fluxo.id] ?? [] : []
            return (
              <article key={e.id} className="cartao fluxo-item">
                <div className="fluxo-topo">
                  <span>
                    <b>{e.nome}</b> {!e.ativo && <span className="selo cancelado">inativa</span>}
                  </span>
                  {mexer && (
                    <span className="acoes-linha">
                      <button type="button" className="link" onClick={() => agir(() => api.patch(`/entregas/${e.id}`, { ativo: !e.ativo }))}>{e.ativo ? 'Desativar' : 'Reativar'}</button>
                      <BotaoApagar nome={e.nome} semConfirmar apagar={() => apagar(e)} />
                    </span>
                  )}
                </div>
                {outras.length > 0 && <p className="dica-campo">Este fluxo é compartilhado com: {outras.map((o) => o.nome).join(', ')}.</p>}
                {fluxo ? (
                  <>
                    <ol className="etapas-lista">
                      {fluxo.etapas.map((et) => {
                        const auto = regrasDoFluxo.filter((r) => r.evento === 'ENTRAR_ETAPA' && r.etapaId === et.id).length
                        return (
                          <li key={et.id}>
                            <span className="etapa-nome"><b>{et.nome}</b>
                              {et.finalEtapa && <small> · {et.prazoDias !== null ? `prazo padrão para concluir: ${et.prazoDias} dia(s)` : 'sem prazo padrão para concluir'}</small>}
                              {et.inicial && <small> · inicial</small>}{et.finalEtapa && <small> · final</small>}
                              {auto > 0 && <small className="so-gestor"> · {auto} automação(ões)</small>}
                              {et.papelMinimo !== 'USUARIO' && <small className="so-gestor"> · {et.papelMinimo === 'ADMIN' ? 'só administrador' : 'só gestor'} move para cá</small>}
                            </span>
                            {et.requisitos.length > 0 && (
                              <ul className="etapa-reqs">
                                {et.requisitos.map((r) => <li key={r.id}><small>{r.tipo === 'DOCUMENTO' ? 'Anexar' : 'Confirmar'}: {r.descricao}</small></li>)}
                              </ul>
                            )}
                            {configurar && <button type="button" className="link" onClick={() => setConfig({ fluxoId: fluxo.id, etapaId: et.id })}>Configurar</button>}
                          </li>
                        )
                      })}
                    </ol>
                    {configurar && (
                      novaEtapa?.fluxoId === fluxo.id ? (
                        <form className="linha-form" onSubmit={(ev) => { ev.preventDefault(); agir(() => api.post(`/fluxos/${fluxo.id}/etapas`, { nome: novaEtapa.nome })).then(() => setNovaEtapa(null)) }}>
                          <input autoFocus required maxLength={100} placeholder="Nome da nova etapa (entra antes da conclusão)" value={novaEtapa.nome} onChange={(ev) => setNovaEtapa({ fluxoId: fluxo.id, nome: ev.target.value })} />
                          <button className="primario">Adicionar</button>
                          <button type="button" onClick={() => setNovaEtapa(null)}>Cancelar</button>
                        </form>
                      ) : (
                        <>
                          <button type="button" className="link" onClick={() => setNovaEtapa({ fluxoId: fluxo.id, nome: '' })}>+ Nova etapa</button>
                          <button type="button" className="link" onClick={() => setConfig({ fluxoId: fluxo.id, etapaId: null })}>
                            Ao cancelar{regrasDoFluxo.filter((r) => r.evento === 'CANCELADO').length > 0 ? ` (${regrasDoFluxo.filter((r) => r.evento === 'CANCELADO').length})` : ''}
                          </button>
                        </>
                      )
                    )}
                  </>
                ) : <p className="vazio">Esta entrega ainda não tem um fluxo visível para você.</p>}
              </article>
            )
          })}
          {doSetor.length === 0 && <section className="cartao"><p className="vazio">Nenhuma entrega neste setor ainda.</p></section>}
        </section>

        {podeGerenciar && (ehAdmin ? setorAtual !== null : true) && (
          <form className="cartao" onSubmit={criar}>
            <h3>Nova entrega{nomeSetor ? ` — ${nomeSetor}` : ''}</h3>
            <p className="dica-campo">Ela fica neste setor e só ele a vê e usa.</p>
            <label>Nome<input required maxLength={150} value={f.nome} onChange={(e) => setF({ ...f, nome: e.target.value })} /></label>
            <p className="dica-campo">Etapas do fluxo, em ordem. “Abertura” e “Concluído” são fixas; crie as do meio:</p>
            {f.etapas.map((n, i) => (
              <div key={i} className="etapa-form etapa-simples">
                <input required placeholder={`Etapa ${i + 1}`} value={n} onChange={(e) => setF({ ...f, etapas: f.etapas.map((x, j) => (j === i ? e.target.value : x)) })} />
                <button type="button" className="link" aria-label="Remover etapa" onClick={() => setF({ ...f, etapas: f.etapas.filter((_, j) => j !== i) })}>✕</button>
              </div>
            ))}
            <button type="button" onClick={() => setF({ ...f, etapas: [...f.etapas, ''] })}>+ Etapa</button>
            <label>Prazo padrão para concluir (dias, opcional)
              <input type="number" min={0} placeholder="sem prazo" value={f.prazo} onChange={(e) => setF({ ...f, prazo: e.target.value })} />
            </label>
            {erroForm && <p className="erro">{erroForm}</p>}
            <button className="primario">Criar entrega</button>
          </form>
        )}
      </div>
      {apagando && (
        <SubstituirEntrega
          alvo={apagando.e} qtd={apagando.qtd} candidatas={entregas.filter((o) => o.id !== apagando.e.id && o.ativo && o.setorDonoId === apagando.e.setorDonoId)}
          fechar={() => setApagando(null)} apagado={() => { setApagando(null); carregar() }}
        />
      )}
      {config && (() => {
        const fl = fluxos.find((x) => x.id === config.fluxoId)
        if (!fl) return null
        if (config.etapaId === null) return <JanelaCancelamento fluxo={fl} regras={regras[fl.id] ?? []} recarregar={carregar} fechar={() => setConfig(null)} />
        const et = fl.etapas.find((x) => x.id === config.etapaId)
        return et ? <ConfigurarEtapa fluxo={fl} etapa={et} regras={regras[fl.id] ?? []} recarregar={carregar} fechar={() => setConfig(null)} /> : null
      })()}
    </>
  )
}

/** Apagar entrega com processos em andamento: escolhe a entrega que os recebe (cada processo vai para a etapa de mesmo nome, ou a inicial). */
function SubstituirEntrega({ alvo, qtd, candidatas, fechar, apagado }: { alvo: Entrega; qtd: number; candidatas: Entrega[]; fechar: () => void; apagado: () => void }) {
  const [substituta, setSubstituta] = useState('')
  const [erro, setErro] = useState('')
  const [enviando, setEnviando] = useState(false)

  const enviar = async (ev: FormEvent) => {
    ev.preventDefault()
    setErro('')
    setEnviando(true)
    try {
      await api.post(`/entregas/${alvo.id}/apagar`, { entregaSubstitutaId: Number(substituta) })
      apagado()
    } catch (err) {
      setErro((err as Error).message)
      setEnviando(false)
    }
  }

  return (
    <div className="fundo" onClick={fechar}>
      <form className="modal" onClick={(e) => e.stopPropagation()} onSubmit={enviar}>
        <h3>Apagar "{alvo.nome}"</h3>
        <p>Há <b>{qtd}</b> processo(s) em andamento nesta entrega. Escolha a entrega que vai <b>substituí-la</b>: eles passam para ela, mantendo número, responsável e histórico.</p>
        <p className="dica-campo">Cada processo vai para a etapa de mesmo nome no fluxo da substituta (ou para a etapa inicial, se não houver), com o prazo daquela etapa. Pedidos de aprovação pendentes são cancelados. A substituta precisa ser do mesmo setor.</p>
        <label>Entrega substituta
          <select required value={substituta} onChange={(e) => setSubstituta(e.target.value)}>
            <option value="" disabled>Selecione…</option>
            {candidatas.map((c) => <option key={c.id} value={c.id}>{c.nome}</option>)}
          </select>
        </label>
        {candidatas.length === 0 && <small className="aviso-campo">Não há outra entrega ativa neste setor. Crie ou reative uma antes de apagar esta.</small>}
        {erro && <p className="erro">{erro}</p>}
        <div className="acoes">
          <button type="button" onClick={fechar}>Desistir de apagar</button>
          <button className="perigo" disabled={enviando || !substituta}>Apagar e transferir os processos</button>
        </div>
      </form>
    </div>
  )
}
