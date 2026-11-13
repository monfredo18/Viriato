import { useState, type FormEvent } from 'react'
import { api, ROTULO_AUTOMACAO, type Etapa, type EventoAutomacao, type Fluxo, type RegraAutomacao, type TipoAutomacao } from '../api'

/**
 * Regras de automação de uma etapa (ao entrar nela) ou do fluxo (ao cancelar um processo, quando {@code etapa} é nula).
 * Cada alteração grava na hora e pede à tela de fluxos que recarregue.
 */
export function AutomacoesSecao({ fluxo, etapa, regras, recarregar }: {
  fluxo: Fluxo; etapa: Etapa | null; regras: RegraAutomacao[]; recarregar: () => Promise<unknown>
}) {
  const evento: EventoAutomacao = etapa ? 'ENTRAR_ETAPA' : 'CANCELADO'
  const minhas = regras.filter((r) => r.evento === evento && (!etapa || r.etapaId === etapa.id))
  const jaGera = minhas.some((r) => r.tipo === 'GERAR_DOCUMENTO')
  const [tipo, setTipo] = useState<TipoAutomacao>('WEBHOOK')
  const [f, setF] = useState({ url: '', segredo: '' })
  const [erro, setErro] = useState('')
  const base = `/fluxos/${fluxo.id}/automacoes`

  const tentar = async (fn: () => Promise<unknown>) => {
    setErro('')
    try {
      await fn()
      await recarregar()
    } catch (e) {
      setErro((e as Error).message)
    }
  }

  const adicionar = (e: FormEvent) => {
    e.preventDefault()
    tentar(async () => {
      await api.post(base, {
        evento, etapaId: etapa?.id ?? null, tipo,
        ...(tipo === 'WEBHOOK' ? { url: f.url, segredo: f.segredo || null } : {}),
      })
      setF({ url: '', segredo: '' })
    })
  }

  const resumo = (r: RegraAutomacao) => {
    if (r.tipo === 'WEBHOOK') return `${r.url}${r.temSegredo ? ' · assinado' : ' · sem assinatura'}`
    return 'Anexa a ficha ao processo'
  }

  return (
    <>
      {erro && <p className="erro">{erro}</p>}
      <ul className="config-lista">
        {minhas.map((r) => (
          <li key={r.id} className={r.ativo ? undefined : 'regra-inativa'}>
            <span className="req-texto"><b>{ROTULO_AUTOMACAO[r.tipo]}</b><small>{resumo(r)}</small></span>
            <label className="chk">
              <input type="checkbox" checked={r.ativo} onChange={(e) => tentar(() => api.patch(`${base}/${r.id}`, { ativo: e.target.checked }))} /> {r.ativo ? 'ativa' : 'desligada'}
            </label>
            <button type="button" className="link perigo-txt" onClick={() => confirm('Remover esta automação?') && tentar(() => api.del(`${base}/${r.id}`))}>remover</button>
          </li>
        ))}
        {minhas.length === 0 && <li className="vazio">Nenhuma automação.</li>}
      </ul>

      <form className="auto-form" onSubmit={adicionar}>
        <select value={tipo} onChange={(e) => setTipo(e.target.value as TipoAutomacao)} aria-label="O que fazer">
          {(Object.keys(ROTULO_AUTOMACAO) as TipoAutomacao[]).map((t) => (
            <option key={t} value={t} disabled={t === 'GERAR_DOCUMENTO' && jaGera}>{ROTULO_AUTOMACAO[t]}{t === 'GERAR_DOCUMENTO' && jaGera ? ' (já existe)' : ''}</option>
          ))}
        </select>

        {tipo === 'GERAR_DOCUMENTO' && <small>Gera um PDF com os dados e o histórico do processo e o anexa a ele.</small>}

        {tipo === 'WEBHOOK' && (
          <>
            <input required maxLength={500} type="url" placeholder="https://sistema.exemplo.com/entrada" value={f.url} onChange={(e) => setF({ ...f, url: e.target.value })} />
            <input maxLength={200} placeholder="Segredo para assinar (opcional)" autoComplete="off" value={f.segredo} onChange={(e) => setF({ ...f, segredo: e.target.value })} />
            <small>Envia um JSON com os dados do processo (POST). Só endereços https públicos. Com segredo, o cabeçalho X-Viriato-Assinatura traz o HMAC-SHA256 do corpo.</small>
          </>
        )}
        <button className="primario" disabled={tipo === 'GERAR_DOCUMENTO' && jaGera}>Adicionar automação</button>
      </form>
    </>
  )
}

/** Automações do fluxo inteiro: o que acontece quando um processo dele é cancelado. */
export function JanelaCancelamento({ fluxo, regras, recarregar, fechar }: {
  fluxo: Fluxo; regras: RegraAutomacao[]; recarregar: () => Promise<unknown>; fechar: () => void
}) {
  return (
    <div className="fundo" onClick={fechar}>
      <div className="modal modal-largo" onClick={(e) => e.stopPropagation()}>
        <h3>Ao cancelar um processo — {fluxo.nome}</h3>
        <section className="config-secao">
          <p className="sub">Roda quando qualquer processo deste fluxo é cancelado.</p>
          <AutomacoesSecao fluxo={fluxo} etapa={null} regras={regras} recarregar={recarregar} />
        </section>
        <div className="acoes"><button type="button" onClick={fechar}>Fechar</button></div>
      </div>
    </div>
  )
}
