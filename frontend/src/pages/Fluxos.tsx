import { useEffect, useState, type FormEvent } from 'react'
import { api, type Fluxo } from '../api'

interface EtapaForm { nome: string; prazoDias: string; inicial: boolean; finalEtapa: boolean }

const modelo = (): EtapaForm[] => [
  { nome: 'Abertura', prazoDias: '2', inicial: true, finalEtapa: false },
  { nome: 'Concluído', prazoDias: '', inicial: false, finalEtapa: true },
]

export default function Fluxos() {
  const [fluxos, setFluxos] = useState<Fluxo[]>([])
  const [nome, setNome] = useState('')
  const [etapas, setEtapas] = useState<EtapaForm[]>(modelo())
  const [erro, setErro] = useState('')

  const carregar = () => api.get<Fluxo[]>('/fluxos').then(setFluxos)
  useEffect(() => { carregar() }, [])

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
      <p className="sub">As etapas são conectadas em sequência (cada uma leva à próxima).</p>
      <div className="grade">
        <section className="cartao">
          <h3>Fluxos existentes</h3>
          {fluxos.map((f) => (
            <div key={f.id} className="fluxo-item">
              <b>{f.nome}</b> {!f.ativo && <span className="selo cancelado">inativo</span>}
              <div className="trilha">{f.etapas.map((e) => e.nome).join(' → ')}</div>
              <button className="link" onClick={() => api.patch(`/fluxos/${f.id}`, { ativo: !f.ativo }).then(carregar)}>
                {f.ativo ? 'Desativar' : 'Reativar'}
              </button>
            </div>
          ))}
        </section>

        <form className="cartao" onSubmit={criar}>
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
        </form>
      </div>
    </>
  )
}
