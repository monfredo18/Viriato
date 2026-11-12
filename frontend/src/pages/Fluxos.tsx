import { useEffect, useState, type FormEvent } from 'react'
import { api, type Fluxo, type RegraAutomacao } from '../api'
import BotaoApagar from '../BotaoApagar'
import { useAuth } from '../auth'
import ConfigurarEtapa from './ConfigurarEtapa'
import { JanelaCancelamento } from './AutomacoesSecao'

interface EtapaForm { nome: string; prazoDias: string; inicial: boolean; finalEtapa: boolean }

const modelo = (): EtapaForm[] => [
  { nome: 'Abertura', prazoDias: '2', inicial: true, finalEtapa: false },
  { nome: 'Concluído', prazoDias: '', inicial: false, finalEtapa: true },
]

export default function Fluxos() {
  const { podeGerenciar } = useAuth()
  const [fluxos, setFluxos] = useState<Fluxo[]>([])
  const [nome, setNome] = useState('')
  const [etapas, setEtapas] = useState<EtapaForm[]>(modelo())
  const [erro, setErro] = useState('')
  const [erroLista, setErroLista] = useState('')
  const [config, setConfig] = useState<{ fluxoId: number; etapaId: number | null } | null>(null)
  const [regras, setRegras] = useState<Record<number, RegraAutomacao[]>>({})

  // Só administrador e gestor consultam as automações (podem ter endereços e segredos de integração).
  const carregar = async () => {
    const lista = await api.get<Fluxo[]>('/fluxos')
    setFluxos(lista)
    if (podeGerenciar) {
      const pares = await Promise.all(lista.map(async (f) => [f.id, await api.get<RegraAutomacao[]>(`/fluxos/${f.id}/automacoes`)] as const))
      setRegras(Object.fromEntries(pares))
    }
  }
  useEffect(() => { carregar() }, [])  // eslint-disable-line react-hooks/exhaustive-deps

  const apagarFluxo = (f: Fluxo) => {
    setErroLista('')
    api.del(`/fluxos/${f.id}`).then(carregar).catch((e) => setErroLista(e.message))
  }

  const upd = (i: number, patch: Partial<EtapaForm>) => setEtapas(etapas.map((e, j) => (j === i ? { ...e, ...patch } : e)))
  const marcarInicial = (i: number) => setEtapas(etapas.map((e, j) => ({ ...e, inicial: j === i })))

  const criar = async (e: FormEvent) => {
    e.preventDefault()
    setErro('')
    try {
      await api.post('/fluxos', {
        nome,
        etapas: etapas.map((x) => ({ nome: x.nome, prazoDias: x.prazoDias === '' ? null : Number(x.prazoDias), inicial: x.inicial, finalEtapa: x.finalEtapa })),
      })
      setNome('')
      setEtapas(modelo())
      carregar()
    } catch (err) {
      setErro((err as Error).message)
    }
  }

  return (
    <>
      <h2>Fluxos</h2>
      <p className="sub">
        {podeGerenciar
          ? 'As etapas são conectadas em sequência (cada uma leva à próxima).'
          : 'Somente consulta. Quem pode alterar: administrador e gestor.'}
      </p>
      <div className="grade">
        <section className="cartao">
          <h3>Fluxos existentes</h3>
          {erroLista && <p className="erro">{erroLista}</p>}
          {fluxos.map((f) => (
            <div key={f.id} className="fluxo-item">
              <div className="fluxo-topo">
                <span><b>{f.nome}</b> {!f.ativo && <span className="selo cancelado">inativo</span>}</span>
                {podeGerenciar && (
                  <BotaoApagar
                    nome={f.nome}
                    aviso="As etapas e transições do fluxo também são apagadas. Processos já concluídos ou cancelados continuam existindo, sem fluxo. Não apaga se uma entrega usa o fluxo ou se há processo em andamento nele."
                    apagar={() => apagarFluxo(f)}
                  />
                )}
              </div>
              <ol className="etapas-lista">
                {f.etapas.map((e) => (
                  <li key={e.id}>
                    <span className="etapa-nome"><b>{e.nome}</b>
                      {e.prazoDias !== null && <small> · prazo {e.prazoDias} d</small>}
                      {e.inicial && <small> · inicial</small>}{e.finalEtapa && <small> · final</small>}
                      {(regras[f.id] ?? []).filter((r) => r.evento === 'ENTRAR_ETAPA' && r.etapaId === e.id).length > 0 && <small className="so-gestor"> · {(regras[f.id] ?? []).filter((r) => r.evento === 'ENTRAR_ETAPA' && r.etapaId === e.id).length} automação(ões)</small>}
                      {e.papelMinimo !== 'USUARIO' && <small className="so-gestor"> · {e.papelMinimo === 'ADMIN' ? 'só administrador' : 'só gestor'} move para cá</small>}
                    </span>
                    {e.requisitos.length > 0 && (
                      <ul className="etapa-reqs">
                        {e.requisitos.map((r) => <li key={r.id}><small>{r.tipo === 'DOCUMENTO' ? 'Anexar' : 'Confirmar'}: {r.descricao}</small></li>)}
                      </ul>
                    )}
                    {podeGerenciar && <button type="button" className="link" onClick={() => setConfig({ fluxoId: f.id, etapaId: e.id })}>Configurar</button>}
                  </li>
                ))}
              </ol>
              {podeGerenciar && (
                <button className="link" onClick={() => setConfig({ fluxoId: f.id, etapaId: null })}>
                  Ao cancelar{(regras[f.id] ?? []).filter((r) => r.evento === 'CANCELADO').length > 0 ? ` (${(regras[f.id] ?? []).filter((r) => r.evento === 'CANCELADO').length})` : ''}
                </button>
              )}
              {podeGerenciar && (
                <button className="link" onClick={() => api.patch(`/fluxos/${f.id}`, { ativo: !f.ativo }).then(carregar)}>
                  {f.ativo ? 'Desativar' : 'Reativar'}
                </button>
              )}
            </div>
          ))}
        </section>

        {podeGerenciar && <form className="cartao" onSubmit={criar}>
          <h3>Novo fluxo</h3>
          <label>Nome<input required value={nome} onChange={(e) => setNome(e.target.value)} /></label>
          {etapas.map((et, i) => (
            <div key={i} className="etapa-form">
              <input required placeholder={`Etapa ${i + 1}`} value={et.nome} onChange={(e) => upd(i, { nome: e.target.value })} />
              <input type="number" min={0} placeholder="prazo (dias)" value={et.prazoDias} onChange={(e) => upd(i, { prazoDias: e.target.value })} />
              <label className="chk"><input type="radio" name="inicial" checked={et.inicial} onChange={() => marcarInicial(i)} /> inicial</label>
              <label className="chk"><input type="checkbox" checked={et.finalEtapa} onChange={(e) => upd(i, { finalEtapa: e.target.checked })} /> final</label>
              <button type="button" className="link" disabled={etapas.length <= 2} onClick={() => setEtapas(etapas.filter((_, j) => j !== i))}>✕</button>
            </div>
          ))}
          <button type="button" onClick={() => setEtapas([...etapas.slice(0, -1), { nome: '', prazoDias: '', inicial: false, finalEtapa: false }, etapas[etapas.length - 1]])}>+ Etapa</button>
          {erro && <p className="erro">{erro}</p>}
          <button className="primario">Criar fluxo</button>
        </form>}
      </div>
      {config && (() => {
        const f = fluxos.find((x) => x.id === config.fluxoId)
        if (!f) return null
        if (config.etapaId === null) return <JanelaCancelamento fluxo={f} regras={regras[f.id] ?? []} recarregar={carregar} fechar={() => setConfig(null)} />
        const e = f.etapas.find((x) => x.id === config.etapaId)
        return e ? <ConfigurarEtapa fluxo={f} etapa={e} regras={regras[f.id] ?? []} recarregar={carregar} fechar={() => setConfig(null)} /> : null
      })()}
    </>
  )
}
