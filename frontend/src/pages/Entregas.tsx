import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { api, type Entrega, type Fluxo, type RegraAutomacao } from '../api'
import BotaoApagar from '../BotaoApagar'
import { useAuth } from '../auth'
import ConfigurarEtapa from './ConfigurarEtapa'
import { JanelaCancelamento } from './AutomacoesSecao'

interface EtapaForm { nome: string; prazoDias: string; inicial: boolean; finalEtapa: boolean }

const modelo = (): EtapaForm[] => [
  { nome: 'Abertura', prazoDias: '2', inicial: true, finalEtapa: false },
  { nome: 'Análise', prazoDias: '5', inicial: false, finalEtapa: false },
  { nome: 'Concluído', prazoDias: '', inicial: false, finalEtapa: true },
]

/**
 * Entregas e seus fluxos em um lugar só: cada entrega traz as etapas do próprio fluxo, com requisitos, aprovação e
 * automações. Administrador mexe em todas; o gestor, nas exclusivas do próprio setor (as gerais ele só consulta).
 */
export default function Entregas() {
  const { podeGerenciar, ehAdmin } = useAuth()
  const [entregas, setEntregas] = useState<Entrega[]>([])
  const [fluxos, setFluxos] = useState<Fluxo[]>([])
  const [regras, setRegras] = useState<Record<number, RegraAutomacao[]>>({})
  const [f, setF] = useState({ nome: '', etapas: modelo() })
  const [erro, setErro] = useState('')
  const [erroForm, setErroForm] = useState('')
  const [config, setConfig] = useState<{ fluxoId: number; etapaId: number | null } | null>(null)
  const [apagando, setApagando] = useState<{ e: Entrega; qtd: number } | null>(null)

  // Só administrador e gestor consultam as automações (podem ter endereços e segredos de integração).
  const carregar = useCallback(async () => {
    const [es, fs] = await Promise.all([api.get<Entrega[]>('/entregas'), api.get<Fluxo[]>('/fluxos')])
    setEntregas(es)
    setFluxos(fs)
    if (podeGerenciar) {
      const pares = await Promise.all(fs.map(async (x) => [x.id, await api.get<RegraAutomacao[]>(`/fluxos/${x.id}/automacoes`).catch(() => [])] as const))
      setRegras(Object.fromEntries(pares))
    }
  }, [podeGerenciar])
  useEffect(() => { carregar() }, [carregar])

  const agir = (fn: () => Promise<unknown>) => fn().then(carregar).catch((e) => setErro(e.message))
  const podeMexer = (e: Entrega) => ehAdmin || (podeGerenciar && e.setorDonoId !== null)
  // Sem processo em andamento: confirmação simples. Com processos: pede a entrega que vai substituí-la.
  const apagar = async (e: Entrega) => {
    setErro('')
    try {
      const { emAndamento } = await api.get<{ emAndamento: number }>(`/entregas/${e.id}/pendencias`)
      if (emAndamento === 0) {
        if (!confirm(`Apagar "${e.nome}"? Esta ação não pode ser desfeita.\n\nO fluxo dela (etapas, requisitos e automações) também é apagado e ela sai da lista dos setores. Processos já concluídos ou cancelados continuam existindo, sem o vínculo.`)) return
        await api.post(`/entregas/${e.id}/apagar`, {})
        carregar()
      } else {
        setApagando({ e, qtd: emAndamento })
      }
    } catch (err) {
      setErro((err as Error).message)
    }
  }
  const compartilhado = (e: Entrega) => entregas.filter((o) => o.id !== e.id && o.fluxoId === e.fluxoId)

  const upd = (i: number, patch: Partial<EtapaForm>) => setF({ ...f, etapas: f.etapas.map((e, j) => (j === i ? { ...e, ...patch } : e)) })
  const marcarInicial = (i: number) => setF({ ...f, etapas: f.etapas.map((e, j) => ({ ...e, inicial: j === i })) })

  const criar = async (ev: FormEvent) => {
    ev.preventDefault()
    setErroForm('')
    try {
      await api.post('/entregas', {
        nome: f.nome,
        etapas: f.etapas.map((x) => ({ nome: x.nome, prazoDias: x.prazoDias === '' ? null : Number(x.prazoDias), inicial: x.inicial, finalEtapa: x.finalEtapa })),
      })
      setF({ nome: '', etapas: modelo() })
      carregar()
    } catch (err) {
      setErroForm((err as Error).message)
    }
  }

  return (
    <>
      <h2>Entregas</h2>
      <p className="sub">
        Cada entrega tem o seu fluxo: as etapas pelas quais o processo passa, com prazos, requisitos, aprovação e automações.
        {podeGerenciar && !ehAdmin ? ' As entregas que você criar vão direto para o seu setor e só ele as vê e usa; as gerais, do administrador, você só consulta.' : ''}
        {!podeGerenciar ? ' Somente consulta. Quem pode alterar: administrador e gestor.' : ''}
      </p>
      <div className="grade">
        <section>
          {erro && <p className="erro">{erro}</p>}
          {entregas.map((e) => {
            const fluxo = fluxos.find((x) => x.id === e.fluxoId)
            const mexer = podeMexer(e)
            const outras = compartilhado(e)
            // fluxo compartilhado por mais de uma entrega (dados antigos): só o administrador configura
            const configurar = mexer && (ehAdmin || outras.length === 0)
            const regrasDoFluxo = fluxo ? regras[fluxo.id] ?? [] : []
            return (
              <article key={e.id} className="cartao fluxo-item">
                <div className="fluxo-topo">
                  <span>
                    <b>{e.nome}</b>{' '}
                    {e.setorDonoId === null ? <span className="selo cancelado">Geral</span> : <span className="selo aberto">Só o setor {e.setorDonoNome}</span>}{' '}
                    {!e.ativo && <span className="selo cancelado">inativa</span>}
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
                              {et.prazoDias !== null && <small> · prazo {et.prazoDias} d</small>}
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
                      <button type="button" className="link" onClick={() => setConfig({ fluxoId: fluxo.id, etapaId: null })}>
                        Ao cancelar{regrasDoFluxo.filter((r) => r.evento === 'CANCELADO').length > 0 ? ` (${regrasDoFluxo.filter((r) => r.evento === 'CANCELADO').length})` : ''}
                      </button>
                    )}
                  </>
                ) : <p className="vazio">Esta entrega ainda não tem um fluxo visível para você.</p>}
              </article>
            )
          })}
          {entregas.length === 0 && <section className="cartao"><p className="vazio">Nenhuma entrega cadastrada.</p></section>}
        </section>

        {podeGerenciar && (
          <form className="cartao" onSubmit={criar}>
            <h3>Nova entrega</h3>
            {!ehAdmin && <p className="dica-campo">Ela vai direto para o seu setor e só ele poderá vê-la e usá-la.</p>}
            <label>Nome<input required maxLength={150} value={f.nome} onChange={(e) => setF({ ...f, nome: e.target.value })} /></label>
            <p className="dica-campo">Etapas do fluxo (as etapas se conectam em sequência, cada uma leva à próxima):</p>
            {f.etapas.map((et, i) => (
              <div key={i} className="etapa-form">
                <input required placeholder={`Etapa ${i + 1}`} value={et.nome} onChange={(e) => upd(i, { nome: e.target.value })} />
                <input type="number" min={0} placeholder="prazo (dias)" value={et.prazoDias} onChange={(e) => upd(i, { prazoDias: e.target.value })} />
                <label className="chk"><input type="radio" name="inicial" checked={et.inicial} onChange={() => marcarInicial(i)} /> inicial</label>
                <label className="chk"><input type="checkbox" checked={et.finalEtapa} onChange={(e) => upd(i, { finalEtapa: e.target.checked })} /> final</label>
                <button type="button" className="link" disabled={f.etapas.length <= 2} onClick={() => setF({ ...f, etapas: f.etapas.filter((_, j) => j !== i) })}>✕</button>
              </div>
            ))}
            <button type="button" onClick={() => setF({ ...f, etapas: [...f.etapas.slice(0, -1), { nome: '', prazoDias: '', inicial: false, finalEtapa: false }, f.etapas[f.etapas.length - 1]] })}>+ Etapa</button>
            {erroForm && <p className="erro">{erroForm}</p>}
            <button className="primario">Criar entrega</button>
          </form>
        )}
      </div>
      {apagando && (
        <SubstituirEntrega
          alvo={apagando.e} qtd={apagando.qtd} candidatas={entregas.filter((o) => o.id !== apagando.e.id && o.ativo)}
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
        <p className="dica-campo">Cada processo vai para a etapa de mesmo nome no fluxo da substituta (ou para a etapa inicial, se não houver), com o prazo daquela etapa. Pedidos de aprovação pendentes são cancelados. A substituta precisa ser uma entrega que o setor do processo faz.</p>
        <label>Entrega substituta
          <select required value={substituta} onChange={(e) => setSubstituta(e.target.value)}>
            <option value="" disabled>Selecione…</option>
            {candidatas.map((c) => <option key={c.id} value={c.id}>{c.nome}{c.setorDonoNome ? ` (só o setor ${c.setorDonoNome})` : ''}</option>)}
          </select>
        </label>
        {candidatas.length === 0 && <small className="aviso-campo">Não há outra entrega ativa. Crie ou reative uma antes de apagar esta.</small>}
        {erro && <p className="erro">{erro}</p>}
        <div className="acoes">
          <button type="button" onClick={fechar}>Desistir de apagar</button>
          <button className="perigo" disabled={enviando || !substituta}>Apagar e transferir os processos</button>
        </div>
      </form>
    </div>
  )
}
