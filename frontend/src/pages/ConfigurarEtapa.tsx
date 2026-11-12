import { useState, type FormEvent } from 'react'
import { api, ROTULO_QUEM_MOVE, type Etapa, type Fluxo, type Papel, type TipoRequisito } from '../api'

export const ROTULO_TIPO: Record<TipoRequisito, string> = { DOCUMENTO: 'Anexar documento', CONFIRMACAO: 'Confirmação do responsável' }

/**
 * Configuração de uma etapa do fluxo (administrador e gestor). Cada seção grava na hora e pede à lista
 * de fluxos que recarregue, então a janela sempre mostra o que está salvo.
 */
export default function ConfigurarEtapa({ fluxo, etapa, recarregar, fechar }: {
  fluxo: Fluxo; etapa: Etapa; recarregar: () => Promise<unknown>; fechar: () => void
}) {
  const [tipo, setTipo] = useState<TipoRequisito>('DOCUMENTO')
  const [descricao, setDescricao] = useState('')
  const [erro, setErro] = useState('')
  const base = `/fluxos/${fluxo.id}/etapas/${etapa.id}`

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
      await api.post(`${base}/requisitos`, { tipo, descricao })
      setDescricao('')
    })
  }

  return (
    <div className="fundo" onClick={fechar}>
      <div className="modal modal-largo" onClick={(e) => e.stopPropagation()}>
        <h3>Etapa “{etapa.nome}” — {fluxo.nome}</h3>
        {erro && <p className="erro">{erro}</p>}

        <section className="config-secao">
          <h4>Quem pode mover o processo para esta etapa</h4>
          <p className="sub">Quem não tem esse papel pede a aprovação; o gestor (ou administrador) aprova e o processo avança em nome dele.</p>
          <select value={etapa.papelMinimo} aria-label="Quem pode mover para esta etapa"
            onChange={(e) => tentar(() => api.patch(base, { papelMinimo: e.target.value as Papel }))}>
            {(['USUARIO', 'GESTOR', 'ADMIN'] as Papel[]).map((papel) => (
              <option key={papel} value={papel}>{ROTULO_QUEM_MOVE[papel]}</option>
            ))}
          </select>
        </section>

        <section className="config-secao">
          <h4>Requisitos para sair desta etapa</h4>
          {etapa.finalEtapa ? (
            <p className="sub">A etapa final não tem saída, então não tem requisitos.</p>
          ) : (
            <>
              <p className="sub">O processo só avança depois que todos estiverem cumpridos (vale para todos, inclusive administrador e gestor). Voltar para uma etapa anterior não exige nada.</p>
              <ul className="config-lista">
                {etapa.requisitos.map((r) => (
                  <li key={r.id}>
                    <span className="req-texto"><b>{r.descricao}</b><small>{ROTULO_TIPO[r.tipo]}</small></span>
                    <button type="button" className="link" onClick={() => {
                      const nova = prompt('Nova descrição do requisito:', r.descricao)
                      if (nova && nova.trim() && nova !== r.descricao) tentar(() => api.patch(`${base}/requisitos/${r.id}`, { descricao: nova }))
                    }}>editar</button>
                    <button type="button" className="link perigo-txt" onClick={() => confirm(`Remover o requisito “${r.descricao}”?`) && tentar(() => api.del(`${base}/requisitos/${r.id}`))}>remover</button>
                  </li>
                ))}
                {etapa.requisitos.length === 0 && <li className="vazio">Nenhum requisito: o processo pode avançar livremente.</li>}
              </ul>
              <form className="linha-form" onSubmit={adicionar}>
                <select value={tipo} onChange={(e) => setTipo(e.target.value as TipoRequisito)} aria-label="Tipo do requisito">
                  {(Object.keys(ROTULO_TIPO) as TipoRequisito[]).map((t) => <option key={t} value={t}>{ROTULO_TIPO[t]}</option>)}
                </select>
                <input required maxLength={200} placeholder={tipo === 'DOCUMENTO' ? 'Ex.: Laudo técnico assinado' : 'Ex.: Dados do cliente conferidos'} value={descricao} onChange={(e) => setDescricao(e.target.value)} />
                <button className="primario">Adicionar</button>
              </form>
            </>
          )}
        </section>

        <div className="acoes"><button type="button" onClick={fechar}>Fechar</button></div>
      </div>
    </div>
  )
}
