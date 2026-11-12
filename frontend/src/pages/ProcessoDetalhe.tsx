import { Fragment, useCallback, useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, NIVEL_PAPEL, ROTULO_AUTOMACAO, type Detalhe, type Documento, type ExecucaoAutomacao, type Papel, type Usuario } from '../api'
import { useAuth } from '../auth'
import { Selo } from './Processos'

const ROTULO: Record<string, string> = {
  CRIADO: 'Criado', MOVIDO: 'Movimentado', COMENTARIO: 'Comentário', ATRIBUIDO: 'Atribuído', CANCELADO: 'Cancelado', REQUISITO: 'Requisito',
  APROVACAO_SOLICITADA: 'Aprovação solicitada', APROVACAO_RECUSADA: 'Aprovação recusada', APROVACAO_CANCELADA: 'Pedido retirado',
}
const fmtTam = (b: number) => (b < 1024 * 1024 ? `${Math.max(1, Math.round(b / 1024))} KB` : `${(b / 1024 / 1024).toFixed(1)} MB`)

export default function ProcessoDetalhe() {
  const { id } = useParams()
  const { podeGerenciar, usuario } = useAuth()
  const [d, setD] = useState<Detalhe | null>(null)
  const [docs, setDocs] = useState<Documento[]>([])
  const [execucoes, setExecucoes] = useState<ExecucaoAutomacao[]>([])
  const [usuarios, setUsuarios] = useState<Usuario[]>([])
  const [comentario, setComentario] = useState('')
  const [erro, setErro] = useState('')
  const input = useRef<HTMLInputElement>(null)
  const requisitoAlvo = useRef<number | null>(null)   // requisito que o arquivo escolhido vai cumprir (se houver)

  const carregar = useCallback(async () => {
    try {
      const [det, ds, ex] = await Promise.all([
        api.get<Detalhe>(`/processos/${id}`),
        api.get<Documento[]>(`/processos/${id}/documentos`),
        api.get<ExecucaoAutomacao[]>(`/processos/${id}/automacoes`),
      ])
      setD(det)
      setDocs(ds)
      setExecucoes(ex)
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
  const meuPapel = (usuario?.papel ?? 'USUARIO') as Papel
  const atende = (minimo: Papel) => NIVEL_PAPEL[meuPapel] >= NIVEL_PAPEL[minimo]
  const pedidoPendente = d.aprovacoes.find((a) => a.status === 'PENDENTE')
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

          {d.aprovacoes.length > 0 && (
            <div className="aprovacoes">
              <h4>Aprovações</h4>
              <ul>
                {d.aprovacoes.map((a) => (
                  <li key={a.id} className={`aprov-${a.status.toLowerCase()}`}>
                    <span className="req-texto">
                      <b>Para “{a.etapaDestino}” — {{ PENDENTE: 'aguardando decisão', APROVADA: 'aprovada', RECUSADA: 'recusada', CANCELADA: 'cancelada' }[a.status]}</b>
                      <small>
                        Pedido de {a.solicitadoPor ?? 'usuário removido'} em {new Date(a.solicitadoEm).toLocaleString('pt-BR')}
                        {a.comentario && <> · “{a.comentario}”</>}
                      </small>
                      {a.status !== 'PENDENTE' && a.status !== 'CANCELADA' && (
                        <small>{a.status === 'APROVADA' ? 'Aprovada' : 'Recusada'} por {a.decididoPor ?? 'usuário removido'}{a.motivo && <> · “{a.motivo}”</>}</small>
                      )}
                    </span>
                    {a.status === 'PENDENTE' && aberto && (
                      <span className="aprov-acoes">
                        {atende(a.papelMinimo) && (
                          <>
                            <button type="button" className="primario" onClick={() => {
                              const nota = prompt('Aprovar e mover o processo. Observação (opcional):')
                              if (nota !== null) acao(() => api.post(`/processos/${p.id}/aprovacoes/${a.id}/aprovar`, { comentario: nota || null }))
                            }}>Aprovar</button>
                            <button type="button" className="perigo" onClick={() => {
                              const motivo = prompt('Motivo da recusa:')
                              if (motivo && motivo.trim()) acao(() => api.post(`/processos/${p.id}/aprovacoes/${a.id}/recusar`, { motivo }))
                            }}>Recusar</button>
                          </>
                        )}
                        {(a.solicitadoPorId === usuario?.id || podeGerenciar) && (
                          <button type="button" className="link" onClick={() => confirm('Retirar este pedido de aprovação?') && acao(() => api.del(`/processos/${p.id}/aprovacoes/${a.id}`))}>retirar pedido</button>
                        )}
                      </span>
                    )}
                  </li>
                ))}
              </ul>
            </div>
          )}

          {execucoes.length > 0 && (
            <div className="automacoes-exec">
              <h4>Automações executadas</h4>
              <ul>
                {execucoes.map((x) => (
                  <li key={x.id} className={`exec-${x.status.toLowerCase()}`}>
                    <span className="req-marca" aria-hidden="true">{x.status === 'OK' ? '✓' : x.status === 'FALHA' ? '✕' : '–'}</span>
                    <span className="req-texto">
                      <b>{ROTULO_AUTOMACAO[x.tipo]}{x.evento === 'CANCELADO' ? ' (ao cancelar)' : x.etapa ? ` — ao entrar em ${x.etapa}` : ''}</b>
                      <small>{{ OK: 'Feito', FALHA: 'Falhou', IGNORADA: 'Não executada' }[x.status]}{x.mensagem && <> · {x.mensagem}</>} · {new Date(x.executadoEm).toLocaleString('pt-BR')}</small>
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          )}

          {aberto && podeMexer && (
            <div className="acoes-processo">
              {d.proximasEtapas.map((e) => !atende(e.papelMinimo) ? (
                // etapa que exige gestor/administrador: quem não é pede aprovação
                pedidoPendente?.etapaDestinoId === e.id ? (
                  <span key={e.id} className="selo aberto" title="Aguardando decisão">Aprovação solicitada: {e.nome}</span>
                ) : (
                  <button key={e.id}
                    disabled={!!pedidoPendente || bloqueia(e.ordem)}
                    title={pedidoPendente ? 'Já há um pedido de aprovação pendente' : bloqueia(e.ordem) ? 'Cumpra os requisitos da etapa para pedir' : `Só ${e.papelMinimo === 'ADMIN' ? 'o administrador' : 'gestor ou administrador'} move para ${e.nome}`}
                    onClick={() => {
                      const comentario = prompt(`Solicitar aprovação para “${e.nome}”.\nObservação para quem vai aprovar (opcional):`)
                      if (comentario !== null) acao(() => api.post(`/processos/${p.id}/aprovacoes`, { etapaId: e.id, comentario: comentario || null }))
                    }}>
                    Solicitar aprovação: {e.nome}
                  </button>
                )
              ) : (
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
                <small> {fmtTam(x.tamanho)}{x.requisito && <> · requisito: {x.requisito}</>}{x.automatico && <> · gerado automaticamente</>}</small>
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
