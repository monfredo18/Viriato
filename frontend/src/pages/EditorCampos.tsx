import { useEffect, useState } from 'react'
import { api, ROTULO_TIPO_CAMPO, type CampoExtra, type Entrega, type TipoCampo } from '../api'

interface Linha { rotulo: string; tipo: TipoCampo; obrigatorio: boolean; opcoes: string }

/** Campos extras da entrega (texto, número, data, valor em reais e seleção): aparecem para preencher no detalhe de cada processo. */
export default function EditorCampos({ entrega, fechar }: { entrega: Entrega; fechar: () => void }) {
  const [linhas, setLinhas] = useState<Linha[]>([])
  const [erro, setErro] = useState('')
  const [ok, setOk] = useState('')
  useEffect(() => {
    api.get<CampoExtra[]>(`/entregas/${entrega.id}/campos`).then((cs) => setLinhas(cs.map((c) => ({ rotulo: c.rotulo, tipo: c.tipo, obrigatorio: c.obrigatorio, opcoes: c.opcoes.join(', ') })))).catch((e) => setErro(e.message))
  }, [entrega.id])
  const set = (i: number, parte: Partial<Linha>) => setLinhas(linhas.map((l, j) => (j === i ? { ...l, ...parte } : l)))
  const salvar = async () => {
    setErro(''); setOk('')
    try {
      await api.put(`/entregas/${entrega.id}/campos`, linhas.map((l) => ({ rotulo: l.rotulo, tipo: l.tipo, obrigatorio: l.obrigatorio, opcoes: l.tipo === 'SELECAO' ? l.opcoes.split(',').map((o) => o.trim()).filter(Boolean) : [] })))
      setOk('Campos salvos.')
    } catch (e) { setErro((e as Error).message) }
  }
  return (
    <div className="fundo" onClick={fechar}>
      <div className="modal modal-largo" onClick={(e) => e.stopPropagation()}>
        <h3>Campos extras de "{entrega.nome}"</h3>
        <p className="sub">Informações específicas desta entrega, preenchidas em cada processo. Marque <b>obrigatório</b> para o campo ser exigido antes de concluir o processo.</p>
        {linhas.map((l, i) => (
          <div key={i} className="campo-linha">
            <input aria-label="Nome do campo" placeholder="Nome do campo" maxLength={80} value={l.rotulo} onChange={(e) => set(i, { rotulo: e.target.value })} />
            <select aria-label="Tipo" value={l.tipo} onChange={(e) => set(i, { tipo: e.target.value as TipoCampo })}>
              {(Object.keys(ROTULO_TIPO_CAMPO) as TipoCampo[]).map((t) => <option key={t} value={t}>{ROTULO_TIPO_CAMPO[t]}</option>)}
            </select>
            {l.tipo === 'SELECAO' && <input aria-label="Opções" placeholder="Opções, separadas por vírgula" value={l.opcoes} onChange={(e) => set(i, { opcoes: e.target.value })} />}
            <label className="chk"><input type="checkbox" checked={l.obrigatorio} onChange={(e) => set(i, { obrigatorio: e.target.checked })} /> obrigatório</label>
            <span className="acoes-linha">
              <button type="button" className="link" disabled={i === 0} onClick={() => setLinhas(troca(linhas, i, i - 1))} aria-label="Subir">▲</button>
              <button type="button" className="link" disabled={i === linhas.length - 1} onClick={() => setLinhas(troca(linhas, i, i + 1))} aria-label="Descer">▼</button>
              <button type="button" className="link" onClick={() => setLinhas(linhas.filter((_, j) => j !== i))} aria-label="Remover">✕</button>
            </span>
          </div>
        ))}
        {linhas.length === 0 && <p className="vazio">Nenhum campo extra definido.</p>}
        <div className="linha-acoes"><button type="button" onClick={() => setLinhas([...linhas, { rotulo: '', tipo: 'TEXTO', obrigatorio: false, opcoes: '' }])}>+ Campo</button></div>
        {erro && <p className="erro">{erro}</p>}
        {ok && <p className="sub">{ok}</p>}
        <div className="acoes"><button type="button" className="primario" onClick={salvar}>Salvar campos</button><button type="button" onClick={fechar}>Fechar</button></div>
      </div>
    </div>
  )
}

function troca<T>(l: T[], a: number, b: number) { const n = [...l];[n[a], n[b]] = [n[b], n[a]]; return n }
