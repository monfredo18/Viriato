import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react'
import {
  api, CATEGORIAS_GED, type DocumentoGed, type EstadoSefaz, type NotaXml, type PastaGed, type ResultadoImportacao, type ResultadoSefaz, type Setor,
} from '../api'
import { useAuth } from '../auth'
import { fmtData } from '../formato'

const MESES = ['Jan', 'Fev', 'Mar', 'Abr', 'Mai', 'Jun', 'Jul', 'Ago', 'Set', 'Out', 'Nov', 'Dez']
const tam = (b: number) => (b < 1024 * 1024 ? `${Math.max(1, Math.round(b / 1024))} KB` : `${(b / 1024 / 1024).toFixed(1)} MB`)
const moeda = (v: number) => v.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' })

interface Selecao { ano: number | null; mes: number | null; setorId: number | null }

/** GED da empresa: explorador Ano > Mês > Setor e documentos com guarda legal (arquivar em vez de excluir). */
export function Ged({ clienteId }: { clienteId: number }) {
  const { ehAdmin } = useAuth()
  const base = `/clientes/${clienteId}/ged`
  const [pastas, setPastas] = useState<PastaGed[]>([])
  const [docs, setDocs] = useState<DocumentoGed[]>([])
  const [setores, setSetores] = useState<Setor[]>([])
  const [sel, setSel] = useState<Selecao | null>(null)
  const [busca, setBusca] = useState('')
  const [categoria, setCategoria] = useState('')
  const [arquivados, setArquivados] = useState(false)
  const [erro, setErro] = useState('')
  const [form, setForm] = useState({ categoria: 'GUIA', ano: String(new Date().getFullYear()), mes: String(new Date().getMonth() + 1), setorId: '', guarda: true })
  const [arquivo, setArquivo] = useState<File | null>(null)

  const carregar = useCallback(() => {
    api.get<PastaGed[]>(`${base}/pastas`).then(setPastas).catch((e) => setErro(e.message))
    const q = new URLSearchParams()
    if (sel?.ano) q.set('ano', String(sel.ano))
    if (sel?.mes) q.set('mes', String(sel.mes))
    if (sel?.setorId) q.set('setorId', String(sel.setorId))
    if (categoria) q.set('categoria', categoria)
    if (busca.trim()) q.set('busca', busca.trim())
    if (arquivados) q.set('arquivados', 'true')
    api.get<DocumentoGed[]>(`${base}/documentos?${q}`).then(setDocs).catch((e) => setErro(e.message))
  }, [base, sel, categoria, busca, arquivados])
  useEffect(() => { const t = setTimeout(carregar, 200); return () => clearTimeout(t) }, [carregar])
  useEffect(() => { if (ehAdmin) api.get<Setor[]>('/setores').then(setSetores) }, [ehAdmin])
  const agir = (fn: () => Promise<unknown>) => fn().then(() => { setErro(''); carregar() }).catch((e) => setErro(e.message))

  const arvore = useMemo(() => {
    const anos = new Map<number, Map<number, PastaGed[]>>()
    for (const p of pastas) {
      const a = p.ano ?? 0, m = p.mes ?? 0
      if (!anos.has(a)) anos.set(a, new Map())
      anos.get(a)!.set(m, [...(anos.get(a)!.get(m) ?? []), p])
    }
    return anos
  }, [pastas])

  const enviar = (e: FormEvent) => {
    e.preventDefault()
    if (!arquivo) return
    const f = new FormData()
    f.append('arquivo', arquivo)
    f.append('categoria', form.categoria)
    f.append('ano', form.ano)
    f.append('mes', form.mes)
    if (form.setorId) f.append('setorId', form.setorId)
    f.append('guardaLegal', String(form.guarda))
    return agir(async () => { await api.upload(`${base}/documentos`, f); setArquivo(null) })
  }
  const remover = (d: DocumentoGed) => {
    if (d.guardaLegal) return
    if (confirm(`Excluir "${d.nome}"? Esta ação não pode ser desfeita.`)) agir(() => api.del(`/documentos/${d.id}`))
  }

  return (
    <div className="ged">
      <aside className="cartao ged-arvore">
        <h3>Pastas</h3>
        <button type="button" className={`link${sel === null ? ' ativa' : ''}`} onClick={() => setSel(null)}>Todos os documentos</button>
        {[...arvore.entries()].map(([ano, meses]) => (
          <details key={ano} open={sel?.ano === ano || arvore.size === 1}>
            <summary><button type="button" className={`link${sel?.ano === ano && sel.mes === null ? ' ativa' : ''}`} onClick={() => setSel({ ano, mes: null, setorId: null })}>{ano || 'Sem ano'}</button></summary>
            {[...meses.entries()].map(([mes, linhas]) => (
              <details key={mes} open={sel?.ano === ano && sel.mes === mes}>
                <summary><button type="button" className={`link${sel?.ano === ano && sel.mes === mes && sel.setorId === null ? ' ativa' : ''}`} onClick={() => setSel({ ano, mes, setorId: null })}>{mes ? MESES[mes - 1] : 'Sem mês'}</button></summary>
                <ul>
                  {linhas.map((l) => (
                    <li key={String(l.setorId)}>
                      <button type="button" className={`link${sel?.ano === ano && sel.mes === mes && sel.setorId === l.setorId ? ' ativa' : ''}`} onClick={() => setSel({ ano, mes, setorId: l.setorId })}>{l.setorNome} <small>({l.quantidade})</small></button>
                    </li>
                  ))}
                </ul>
              </details>
            ))}
          </details>
        ))}
        {pastas.length === 0 && <p className="vazio">Nenhum documento ainda.</p>}
      </aside>

      <div className="ged-conteudo">
        <section className="cartao">
          <h3>Documentos {sel?.ano ? `· ${sel.mes ? MESES[sel.mes - 1] + '/' : ''}${sel.ano}` : ''}</h3>
          <div className="filtros">
            <input placeholder="Buscar pelo nome…" value={busca} onChange={(e) => setBusca(e.target.value)} />
            <select value={categoria} onChange={(e) => setCategoria(e.target.value)} aria-label="Categoria"><option value="">Todas as categorias</option>{CATEGORIAS_GED.map((c) => <option key={c}>{c}</option>)}</select>
            <label className="chk"><input type="checkbox" checked={arquivados} onChange={(e) => setArquivados(e.target.checked)} /> Mostrar arquivados</label>
          </div>
          {erro && <p className="erro">{erro}</p>}
          <div className="tabela-wrap"><table>
            <thead><tr><th>Documento</th><th>Categoria</th><th>Período</th><th>Setor</th><th>Tamanho</th><th></th></tr></thead>
            <tbody>
              {docs.map((d) => (
                <tr key={d.id} className={d.arquivado ? 'arquivado' : undefined}>
                  <td><button type="button" className="link" onClick={() => api.download(`/documentos/${d.id}/download`, d.nome).catch((e) => setErro(e.message))}>{d.nome}</button>
                    {d.guardaLegal && <span className="selo concluido" title="Guarda legal: mínimo de 5 anos. Não se exclui; arquive.">Guarda legal</span>}
                    {d.arquivado && <span className="selo cancelado">Arquivado</span>}
                    {d.processoId && <small className="sub"> · processo #{d.processoId}</small>}</td>
                  <td>{d.categoria ?? '—'}</td>
                  <td>{d.mes ? `${String(d.mes).padStart(2, '0')}/` : ''}{d.ano ?? '—'}</td>
                  <td>{d.setorNome ?? '—'}</td>
                  <td>{tam(d.tamanho)}</td>
                  <td className="acoes-linha">
                    {d.arquivado
                      ? <button type="button" className="link" onClick={() => agir(() => api.post(`/documentos/${d.id}/desarquivar`))}>desarquivar</button>
                      : <button type="button" className="link" onClick={() => agir(() => api.post(`/documentos/${d.id}/arquivar`))}>arquivar</button>}
                    {!d.guardaLegal && <button type="button" className="link" onClick={() => remover(d)}>excluir</button>}
                  </td>
                </tr>
              ))}
              {docs.length === 0 && <tr><td colSpan={6} className="vazio">Nenhum documento nesta pasta.</td></tr>}
            </tbody>
          </table></div>
        </section>

        <form className="cartao" onSubmit={enviar}>
          <h3>Enviar para a pasta da empresa</h3>
          <div className="ficha-form">
            <label>Arquivo<input type="file" required onChange={(e) => setArquivo(e.target.files?.[0] ?? null)} /></label>
            <label>Categoria
              <select value={form.categoria} onChange={(e) => setForm({ ...form, categoria: e.target.value, guarda: ['GUIA', 'RECIBO', 'XML', 'EXTRATO'].includes(e.target.value) })}>{CATEGORIAS_GED.map((c) => <option key={c}>{c}</option>)}</select>
            </label>
            <label>Mês<input type="number" min={1} max={12} required value={form.mes} onChange={(e) => setForm({ ...form, mes: e.target.value })} /></label>
            <label>Ano<input type="number" min={2000} max={2100} required value={form.ano} onChange={(e) => setForm({ ...form, ano: e.target.value })} /></label>
            {ehAdmin && <label>Setor<select value={form.setorId} onChange={(e) => setForm({ ...form, setorId: e.target.value })}><option value="">Sem setor</option>{setores.map((s) => <option key={s.id} value={s.id}>{s.nome}</option>)}</select></label>}
          </div>
          <label className="chk"><input type="checkbox" checked={form.guarda} onChange={(e) => setForm({ ...form, guarda: e.target.checked })} /> Guarda legal (mínimo de 5 anos: não se exclui, só se arquiva)</label>
          <div className="linha-acoes"><button className="primario" disabled={!arquivo}>Enviar</button></div>
        </form>
      </div>
    </div>
  )
}

/** Notas fiscais lidas dos XMLs (importação em lote e consulta à SEFAZ). */
export function NotasXml({ clienteId }: { clienteId: number }) {
  const [notas, setNotas] = useState<NotaXml[]>([])
  const [ano, setAno] = useState('')
  const [mes, setMes] = useState('')
  const [tipo, setTipo] = useState('')
  const [arquivos, setArquivos] = useState<FileList | null>(null)
  const [resultado, setResultado] = useState<ResultadoImportacao | null>(null)
  const [sefaz, setSefaz] = useState<EstadoSefaz | null>(null)
  const [consulta, setConsulta] = useState<ResultadoSefaz | null>(null)
  const [erro, setErro] = useState('')
  const [ocupado, setOcupado] = useState(false)

  const carregar = useCallback(() => {
    const q = new URLSearchParams()
    if (ano) q.set('ano', ano)
    if (mes) q.set('mes', mes)
    if (tipo) q.set('tipo', tipo)
    api.get<NotaXml[]>(`/clientes/${clienteId}/xml?${q}`).then(setNotas).catch((e) => setErro(e.message))
  }, [clienteId, ano, mes, tipo])
  useEffect(carregar, [carregar])
  useEffect(() => { api.get<EstadoSefaz>(`/clientes/${clienteId}/sefaz`).then(setSefaz).catch(() => {}) }, [clienteId])

  const importar = async (e: FormEvent) => {
    e.preventDefault()
    if (!arquivos || arquivos.length === 0) return
    const f = new FormData()
    Array.from(arquivos).forEach((a) => f.append('arquivos', a))
    f.append('clienteId', String(clienteId))
    setErro(''); setOcupado(true)
    try { setResultado(await api.upload<ResultadoImportacao>('/xml/importar', f)); setArquivos(null); carregar() } catch (err) { setErro((err as Error).message) } finally { setOcupado(false) }
  }
  const consultar = async () => {
    setErro(''); setConsulta(null); setOcupado(true)
    try { setConsulta(await api.post<ResultadoSefaz>(`/clientes/${clienteId}/sefaz/consultar`)); carregar(); setSefaz(await api.get<EstadoSefaz>(`/clientes/${clienteId}/sefaz`)) } catch (err) { setErro((err as Error).message) } finally { setOcupado(false) }
  }
  const totais = useMemo(() => ({
    entrada: notas.filter((n) => n.tipoOperacao === 'ENTRADA').reduce((s, n) => s + n.valorTotal, 0),
    saida: notas.filter((n) => n.tipoOperacao === 'SAIDA').reduce((s, n) => s + n.valorTotal, 0),
  }), [notas])

  return (
    <>
      <section className="cartao">
        <h3>Notas fiscais (XML)</h3>
        <div className="filtros">
          <label className="chk">Ano <input type="number" className="campo-numero" value={ano} onChange={(e) => setAno(e.target.value)} /></label>
          <label className="chk">Mês <input type="number" min={1} max={12} className="campo-numero" value={mes} onChange={(e) => setMes(e.target.value)} /></label>
          <select value={tipo} onChange={(e) => setTipo(e.target.value)} aria-label="Operação"><option value="">Entradas e saídas</option><option value="ENTRADA">Entradas</option><option value="SAIDA">Saídas</option></select>
          <span className="sub">{notas.length} nota(s) · entradas {moeda(totais.entrada)} · saídas {moeda(totais.saida)}</span>
        </div>
        {erro && <p className="erro">{erro}</p>}
        <div className="tabela-wrap"><table>
          <thead><tr><th>Tipo</th><th>Número</th><th>Emissão</th><th>Emitente</th><th>Destinatário</th><th>Operação</th><th>Valor</th><th>Impostos</th></tr></thead>
          <tbody>
            {notas.map((n) => (
              <tr key={n.id} title={n.chaveAcesso}>
                <td>{n.modelo}</td>
                <td>{n.numeroNota ?? '—'}{n.serie ? `/${n.serie}` : ''}</td>
                <td>{fmtData(n.dataEmissao)}</td>
                <td className="corta">{n.nomeEmitente ?? n.cnpjEmitente}</td>
                <td className="corta">{n.nomeDestinatario ?? n.cnpjDestinatario ?? '—'}</td>
                <td><span className={`selo ${n.tipoOperacao === 'ENTRADA' ? 'aberto' : 'concluido'}`}>{n.tipoOperacao === 'ENTRADA' ? 'Entrada' : 'Saída'}</span></td>
                <td>{moeda(n.valorTotal)}</td>
                <td title={`ICMS ${moeda(n.valorIcms)} · IPI ${moeda(n.valorIpi)} · PIS ${moeda(n.valorPis)} · COFINS ${moeda(n.valorCofins)} · ISS ${moeda(n.valorIss)}`}>{moeda(n.valorIcms + n.valorIpi + n.valorPis + n.valorCofins + n.valorIss)}</td>
              </tr>
            ))}
            {notas.length === 0 && <tr><td colSpan={8} className="vazio">Nenhuma nota importada.</td></tr>}
          </tbody>
        </table></div>
      </section>

      <div className="ficha-2col">
        <form className="cartao" onSubmit={importar}>
          <h3>Importar XMLs</h3>
          <p className="sub">NF-e, NFC-e, CT-e e NFS-e, em arquivos soltos ou num .zip. A nota é ligada à empresa pelo CNPJ, e a mesma chave nunca entra duas vezes.</p>
          <label>Arquivos (.xml ou .zip)<input type="file" accept=".xml,.zip" multiple required onChange={(e) => setArquivos(e.target.files)} /></label>
          <div className="linha-acoes"><button className="primario" disabled={ocupado || !arquivos}>{ocupado ? 'Importando…' : 'Importar'}</button></div>
          {resultado && (
            <div className="aviso-resultado">
              <p><b>{resultado.importados}</b> importada(s), {resultado.duplicados} repetida(s), {resultado.rejeitados.length} recusada(s) de {resultado.lidos} lida(s).</p>
              {resultado.rejeitados.length > 0 && <ul>{resultado.rejeitados.map((r, i) => <li key={i}><b>{r.arquivo}</b>: {r.motivo}</li>)}</ul>}
            </div>
          )}
        </form>

        <section className="cartao">
          <h3>Consulta à SEFAZ</h3>
          {!sefaz?.habilitado ? <p className="sub">A consulta automática à SEFAZ está desligada neste ambiente (VIRIATO_SEFAZ_HABILITADO). Quando ligada, ela usa o certificado A1 do cofre desta empresa.</p> : (
            <>
              <p className="sub">Baixa as notas emitidas contra o CNPJ da empresa, com o certificado A1 do cofre. Último NSU: {sefaz.ultimoNsu}{sefaz.ultimaConsulta ? ` · última consulta ${new Date(sefaz.ultimaConsulta).toLocaleString('pt-BR')}` : ''}.
                {sefaz.proximaConsulta && new Date(sefaz.proximaConsulta) > new Date() ? ` A SEFAZ pede espera até ${new Date(sefaz.proximaConsulta).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' })}.` : ''}</p>
              <div className="linha-acoes"><button className="primario" disabled={ocupado} onClick={consultar}>Consultar agora</button></div>
              {consulta && <div className="aviso-resultado"><p><b>{consulta.importadas}</b> nota(s) importada(s), {consulta.duplicadas} repetida(s), {consulta.resumosIgnorados} resumo(s) ignorado(s).</p><p className="sub">{consulta.mensagem}</p></div>}
            </>
          )}
        </section>
      </div>
    </>
  )
}
