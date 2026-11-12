import { Fragment, useCallback, useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, type Detalhe, type Documento, type Usuario } from '../api'
import { useAuth } from '../auth'
import { Selo } from './Processos'

const ROTULO: Record<string, string> = {
  CRIADO: 'Criado', MOVIDO: 'Movimentado', COMENTARIO: 'Comentário', ATRIBUIDO: 'Atribuído', CANCELADO: 'Cancelado', REQUISITO: 'Requisito',
}
const fmtTam = (b: number) => (b < 1024 * 1024 ? `${Math.max(1, Math.round(b / 1024))} KB` : `${(b / 1024 / 1024).toFixed(1)} MB`)

export default function ProcessoDetalhe() {
  const { id } = useParams()
  const { podeGerenciar, usuario } = useAuth()
  const [d, setD] = useState<Detalhe | null>(null)
  const [docs, setDocs] = useState<Documento[]>([])
  const [usuarios, setUsuarios] = useState<Usuario[]>([])
  const [comentario, setComentario] = useState('')
  const [erro, setErro] = useState('')
  const input = useRef<HTMLInputElement>(null)
  const requisitoAlvo = useRef<number | null>(null)   // requisito que o arquivo escolhido vai cumprir (se houver)

  const carregar = useCallback(async () => {
    try {
      const [det, ds] = await Promise.all([
        api.get<Detalhe>(`/processos/${id}`),
        api.get<Documento[]>(`/processos/${id}/documentos`),
      ])
      setD(det)
      setDocs(ds)
    } catch (e) {
      setErro((e as Error).message)
    }
  }, [id])

  useEffect(() => {
    carregar()
    api.get<Usuario[]>('/usuarios').then((l) => setUsuarios(l.filter((u) => u.ativo)))
  }, [carregar])

  const acao = async (fn: () => Promise<unknown>) => {
    setErro('')
    try {
      await fn()
      await carregar()
    } catch (e) {
      setErro((e as Error).message)
    }
  }

  if (!d) return erro ? <p className="erro">{erro}</p> : <p>Carregando…</p>
  const p = d.processo
  const aberto = p.status === 'ABERTO'
  // Administrador e gestor mexem em qualquer processo; o usuário comum só nos seus.
  const podeMexer = podeGerenciar || p.responsavelId === usuario?.id
  const pendentes = d.requisitos.filter((r) => !r.cumprido).length
  // só avançar exige os requisitos; voltar para uma etapa anterior não
  const bloqueia = (ordemDestino: number) => pendentes > 0 && d.etapaOrdem !== null && ordemDestino > d.etapaOrdem

  return (
    <>
      <p><Link to="/processos">‹ Voltar</Link></p>
      <div className="barra">
        <h2>{p.numero} — {p.titulo}</h2>
        <Selo p={p} />
      </div>
      {erro && <p className="erro">{erro}</p>}
      {!podeMexer && aberto && <p className="sub">Somente consulta: só o responsável (ou um gestor) altera este processo.</p>}

      <div className="grade">
        <section className="cartao">
          <h3>Dados</h3>
          <dl>
            <dt>Empresa</dt><dd>{p.clienteNome ?? '—'}</dd>
            <dt>Entrega</dt><dd>{p.entregaNome ?? '—'}</dd>
            <dt>Setor</dt><dd>{p.setorNome ?? '—'}</dd>
            <dt>Fluxo</dt><dd>{p.fluxoNome ?? '—'}</dd>
            <dt>Etapa atual</dt><dd>{p.etapaAtual ?? '—'}</dd>
            <dt>Prazo da etapa</dt><dd>{p.prazoEtapa ? new Date(p.prazoEtapa + 'T00:00:00').toLocaleDateString('pt-BR') : '—'}</dd>
            <dt>Responsável</dt>
            <dd>
              {aberto && podeMexer ? (
                <select value={p.responsavelId ?? ''} onChange={(e) => acao(() => api.put(`/processos/${p.id}/responsavel`, { responsavelId: e.target.value ? Number(e.target.value) : null }))}>
                  <option value="">— ninguém —</option>
                  {usuarios.filter((u) => u.papel === 'ADMIN' || u.setorId === p.setorId).map((u) => <option key={u.id} value={u.id}>{u.nome}</option>)}
                </select>
              ) : (p.responsavelNome ?? '—')}
            </dd>
          </dl>
          {p.descricao && <p className="desc">{p.descricao}</p>}
          {Object.entries(p.camposExtras).length > 0 && (
            <dl>{Object.entries(p.camposExtras).map(([k, v]) => <Fragment key={k}><dt>{k}</dt><dd>{String(v)}</dd></Fragment>)}</dl>
          )}

          {aberto && d.requisitos.length > 0 && (
            <div className="requisitos">
              <h4>Requisitos para sair da etapa “{p.etapaAtual}”</h4>
              <ul>
                {d.requisitos.map((r) => (
                  <li key={r.id} className={r.cumprido ? 'req-ok' : 'req-pendente'}>
                    <span className="req-marca" aria-hidden="true">{r.cumprido ? '✓' : '●'}</span>
                    <span className="req-texto">
                      <b>{r.descricao}</b>
                      <small>
                        {r.tipo === 'DOCUMENTO' ? 'Anexar documento' : 'Confirmação do responsável'}
                        {r.cumprido && <> · {r.tipo === 'DOCUMENTO' ? r.documentoNome : 'confirmado'}{r.cumpridoPor && <> por {r.cumpridoPor}</>}</>}
                        {!r.cumprido && <> · pendente</>}
                      </small>
                    </span>
                    {podeMexer && r.tipo === 'CONFIRMACAO' && (
                      <button type="button" onClick={() => acao(() => api.put(`/processos/${p.id}/requisitos/${r.id}`, { cumprido: !r.cumprido }))}>
                        {r.cumprido ? 'Desmarcar' : 'Confirmar'}
                      </button>
                    )}
                    {podeMexer && r.tipo === 'DOCUMENTO' && (
                      <button type="button" onClick={() => { requisitoAlvo.current = r.id; input.current?.click() }}>
                        {r.cumprido ? 'Anexar outro' : 'Anexar'}
                      </button>
                    )}
                  </li>
                ))}
              </ul>
              {pendentes > 0 && <small className="req-aviso">Faltam {pendentes} requisito(s): o processo só avança depois de cumpri-los.</small>}
            </div>
          )}

          {aberto && podeMexer && (
            <div className="acoes-processo">
              {d.proximasEtapas.map((e) => (
                <button key={e.id} className="primario"
                  disabled={bloqueia(e.ordem)}
                  title={bloqueia(e.ordem) ? 'Cumpra os requisitos da etapa para avançar' : undefined}
                  onClick={() => acao(() => api.post(`/processos/${p.id}/avancar`, { etapaId: e.id, comentario: comentario || null }).then(() => setComentario('')))}>
                  {e.finalEtapa ? `Concluir (${e.nome})` : `Avançar para ${e.nome}`}
                </button>
              ))}
              {podeGerenciar && (
                <button className="perigo" onClick={() => {
                  const motivo = prompt('Motivo do cancelamento:')
                  if (motivo) acao(() => api.post(`/processos/${p.id}/cancelar`, { motivo }))
                }}>Cancelar processo</button>
              )}
            </div>
          )}
        </section>

        <section className="cartao">
          <h3>Documentos</h3>
          <input ref={input} type="file" hidden onChange={(e) => {
            const f = e.target.files?.[0]
            if (!f) return
            const form = new FormData()
            form.append('arquivo', f)
            const alvo = requisitoAlvo.current
            requisitoAlvo.current = null
            acao(() => api.upload(`/processos/${p.id}/documentos${alvo ? `?requisitoId=${alvo}` : ''}`, form))
            e.target.value = ''
          }} />
          {podeMexer && (
            <button onClick={() => input.current?.click()}>
              <svg className="icone-btn" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <path d="M12 16V4M7 9l5-5 5 5M4 20h16" />
              </svg>
              Enviar arquivo
            </button>
          )}
          <ul className="docs">
            {docs.map((x) => (
              <li key={x.id}>
                <button className="link" onClick={() => api.download(`/documentos/${x.id}/download`, x.nome).catch((e) => setErro(e.message))}>{x.nome}</button>
                <small> {fmtTam(x.tamanho)}{x.requisito && <> · requisito: {x.requisito}</>}</small>
                {podeMexer && <button className="link perigo-txt" onClick={() => confirm('Remover documento?') && acao(() => api.del(`/documentos/${x.id}`))}>remover</button>}
              </li>
            ))}
            {docs.length === 0 && <li className="vazio">Nenhum documento.</li>}
          </ul>
        </section>
      </div>

      <section className="cartao">
        <h3>Histórico</h3>
        {podeMexer && <form className="linha" onSubmit={(e) => { e.preventDefault(); if (comentario.trim()) acao(() => api.post(`/processos/${p.id}/comentarios`, { texto: comentario }).then(() => setComentario(''))) }}>
          <input placeholder="Escreva um comentário (também vai junto ao avançar a etapa)…" value={comentario} onChange={(e) => setComentario(e.target.value)} maxLength={2000} />
          <button>Comentar</button>
        </form>}
        <ul className="historico">
          {d.historico.map((h) => (
            <li key={h.id}>
              <b>{ROTULO[h.acao] ?? h.acao}</b>
              {h.acao === 'MOVIDO' && <> {h.deEtapa} → {h.paraEtapa}</>}
              {h.acao === 'CRIADO' && <> em {h.paraEtapa}</>}
              {h.comentario && <div>{h.comentario}</div>}
              <small>{h.usuarioNome ?? 'usuário removido'} · {new Date(h.criadoEm).toLocaleString('pt-BR')}</small>
            </li>
          ))}
        </ul>
      </section>
    </>
  )
}
