import { useEffect, useMemo, useState } from 'react'
import { api, type Cliente, type Setor, type Usuario } from '../api'

type Abrangencia = 'TODOS_SETORES' | 'SEU_SETOR' | 'PROPRIOS'
interface Escopo { abrangencia: Abrangencia; setorId: number | null; setorNome: string | null; usuarioId: number | null }
interface Resumo { total: number; emAndamento: number; atrasados: number; concluidos: number; cancelados: number }
type Situacao = 'ABERTO' | 'CONCLUIDO' | 'CANCELADO'

const SITUACOES: { valor: Situacao; rotulo: string }[] = [
  { valor: 'ABERTO', rotulo: 'Em andamento' },
  { valor: 'CONCLUIDO', rotulo: 'Concluídos' },
  { valor: 'CANCELADO', rotulo: 'Cancelados' },
]

/**
 * Relatório de processos em PDF. A abrangência vem do servidor conforme o papel:
 * administrador (todos os setores), gestor (só o próprio setor), usuário (só os próprios processos).
 */
export default function Relatorios() {
  const [escopo, setEscopo] = useState<Escopo | null>(null)
  const [clientes, setClientes] = useState<Cliente[]>([])
  const [setores, setSetores] = useState<Setor[]>([])
  const [usuarios, setUsuarios] = useState<Usuario[]>([])

  const [usuarioId, setUsuarioId] = useState('')
  const [setorId, setSetorId] = useState('')
  const [clienteId, setClienteId] = useState('')
  const [de, setDe] = useState('')
  const [ate, setAte] = useState('')
  const [referencia, setReferencia] = useState<'ABERTURA' | 'ENCERRAMENTO'>('ABERTURA')
  const [situacoes, setSituacoes] = useState<Situacao[]>(['ABERTO', 'CONCLUIDO', 'CANCELADO'])

  const [resumo, setResumo] = useState<Resumo | null>(null)
  const [erro, setErro] = useState('')
  const [gerando, setGerando] = useState(false)

  useEffect(() => {
    api.get<Escopo>('/relatorios/escopo').then(setEscopo)
    api.get<Cliente[]>('/clientes').then(setClientes)
    api.get<Setor[]>('/setores').then(setSetores)
    api.get<Usuario[]>('/usuarios').then(setUsuarios)
  }, [])

  const semSetor = escopo?.abrangencia === 'SEU_SETOR' && escopo.setorId == null
  const dataInvertida = de !== '' && ate !== '' && de > ate
  const semSituacao = situacoes.length === 0

  // Responsável: o gestor só enxerga quem é do próprio setor; o usuário só a si mesmo.
  const responsaveis = useMemo(() => {
    if (escopo?.abrangencia === 'SEU_SETOR') return usuarios.filter((u) => u.setorId === escopo.setorId)
    if (escopo?.abrangencia === 'PROPRIOS') return usuarios.filter((u) => u.id === escopo.usuarioId)
    return usuarios
  }, [escopo, usuarios])

  const consulta = useMemo(() => {
    const q = new URLSearchParams()
    if (escopo?.abrangencia === 'PROPRIOS') q.set('usuarioId', String(escopo.usuarioId))
    else if (usuarioId) q.set('usuarioId', usuarioId)
    if (escopo?.abrangencia === 'SEU_SETOR' && escopo.setorId != null) q.set('setorId', String(escopo.setorId))
    else if (setorId) q.set('setorId', setorId)
    if (clienteId) q.set('clienteId', clienteId)
    if (de) q.set('de', de)
    if (ate) q.set('ate', ate)
    if (de || ate) q.set('referencia', referencia)
    if (situacoes.length < SITUACOES.length) q.set('situacoes', situacoes.join(','))
    return q.toString()
  }, [escopo, usuarioId, setorId, clienteId, de, ate, referencia, situacoes])

  // Prévia: quantos processos entrariam no relatório
  useEffect(() => {
    if (!escopo || semSetor || dataInvertida || semSituacao) { setResumo(null); return }
    const t = setTimeout(() => {
      setErro('')
      api.get<Resumo>(`/relatorios/processos/resumo?${consulta}`).then(setResumo).catch((e) => { setResumo(null); setErro(e.message) })
    }, 300)
    return () => clearTimeout(t)
  }, [escopo, consulta, semSetor, dataInvertida, semSituacao])

  const alternar = (s: Situacao) => setSituacoes((l) => (l.includes(s) ? l.filter((x) => x !== s) : [...l, s]))

  const limpar = () => { setUsuarioId(''); setSetorId(''); setClienteId(''); setDe(''); setAte(''); setReferencia('ABERTURA'); setSituacoes(['ABERTO', 'CONCLUIDO', 'CANCELADO']) }

  const gerar = async () => {
    setErro('')
    setGerando(true)
    try {
      const agora = new Date()
      const p = (n: number) => String(n).padStart(2, '0')
      const nome = `relatorio-processos-${agora.getFullYear()}${p(agora.getMonth() + 1)}${p(agora.getDate())}-${p(agora.getHours())}${p(agora.getMinutes())}.pdf`
      await api.download(`/relatorios/processos/pdf?${consulta}`, nome)
    } catch (e) {
      setErro((e as Error).message)
    } finally {
      setGerando(false)
    }
  }

  const abrangencia =
    escopo?.abrangencia === 'TODOS_SETORES' ? 'Você pode gerar relatórios de todos os setores.'
    : escopo?.abrangencia === 'SEU_SETOR' ? (semSetor ? '' : `Você pode gerar relatórios do setor ${escopo.setorNome ?? ''}.`)
    : escopo ? 'Você pode gerar relatórios dos seus próprios processos.' : ''

  const podeGerar = !!escopo && !semSetor && !dataInvertida && !semSituacao && (resumo?.total ?? 0) > 0 && !gerando

  return (
    <>
      <h2>Relatórios</h2>
      {abrangencia && <p className="sub">{abrangencia}</p>}
      {semSetor && <p className="erro">Seu usuário não tem setor definido. Peça ao administrador para definir o seu setor antes de gerar relatórios.</p>}

      <div className="grade">
        <section className="cartao">
          <h3>Filtros do relatório de processos</h3>
          <div className="filtros-relatorio">
            <label>Setor
              <select
                value={escopo?.abrangencia === 'SEU_SETOR' ? String(escopo.setorId ?? '') : setorId}
                disabled={escopo?.abrangencia === 'SEU_SETOR'}
                onChange={(e) => setSetorId(e.target.value)}
              >
                {escopo?.abrangencia === 'SEU_SETOR'
                  ? <option value={String(escopo.setorId ?? '')}>{escopo.setorNome ?? '—'}</option>
                  : <option value="">Todos os setores</option>}
                {escopo?.abrangencia !== 'SEU_SETOR' && setores.map((s) => <option key={s.id} value={s.id}>{s.nome}</option>)}
              </select>
            </label>

            <label>Responsável
              <select
                value={escopo?.abrangencia === 'PROPRIOS' ? String(escopo.usuarioId) : usuarioId}
                disabled={escopo?.abrangencia === 'PROPRIOS'}
                onChange={(e) => setUsuarioId(e.target.value)}
              >
                {escopo?.abrangencia !== 'PROPRIOS' && <option value="">Todos</option>}
                {responsaveis.map((u) => <option key={u.id} value={u.id}>{u.nome}</option>)}
              </select>
            </label>

            <label>Empresa
              <select value={clienteId} onChange={(e) => setClienteId(e.target.value)}>
                <option value="">Todas as empresas</option>
                {clientes.map((c) => <option key={c.id} value={c.id}>{c.numero} — {c.nome}</option>)}
              </select>
            </label>

            <label>Período por
              <select value={referencia} onChange={(e) => setReferencia(e.target.value as 'ABERTURA' | 'ENCERRAMENTO')}>
                <option value="ABERTURA">Data de abertura</option>
                <option value="ENCERRAMENTO">Data de encerramento</option>
              </select>
            </label>

            <label>De<input type="date" value={de} max={ate || undefined} onChange={(e) => setDe(e.target.value)} /></label>
            <label>Até<input type="date" value={ate} min={de || undefined} onChange={(e) => setAte(e.target.value)} /></label>
          </div>
          {referencia === 'ENCERRAMENTO' && (de || ate) && <small>Por data de encerramento, só entram os processos concluídos ou cancelados.</small>}
          {dataInvertida && <p className="erro">A data inicial não pode ser depois da data final.</p>}

          <fieldset className="caixas">
            <legend>Situação</legend>
            <div className="linha-caixas">
              {SITUACOES.map((s) => (
                <label key={s.valor} className="chk">
                  <input type="checkbox" checked={situacoes.includes(s.valor)} onChange={() => alternar(s.valor)} /> {s.rotulo}
                </label>
              ))}
            </div>
          </fieldset>
          {semSituacao && <p className="erro">Selecione ao menos uma situação.</p>}

          <div className="acoes esquerda">
            <button type="button" onClick={limpar}>Limpar filtros</button>
          </div>
        </section>

        <section className="cartao">
          <h3>Prévia do relatório</h3>
          {resumo ? (
            <div className="resumo-caixas">
              <div className="caixa-total"><b>{resumo.total}</b><span>Total</span></div>
              <div className="caixa-azul"><b>{resumo.emAndamento}</b><span>Em andamento</span></div>
              <div className="caixa-vermelha"><b>{resumo.atrasados}</b><span>Atrasados</span></div>
              <div className="caixa-ouro"><b>{resumo.concluidos}</b><span>Concluídos</span></div>
              <div className="caixa-cinza"><b>{resumo.cancelados}</b><span>Cancelados</span></div>
            </div>
          ) : (
            <p className="vazio">{semSetor || dataInvertida || semSituacao ? 'Ajuste os filtros para ver a prévia.' : 'Calculando…'}</p>
          )}
          {resumo && resumo.total === 0 && <p className="sub">Nenhum processo encontrado com esses filtros.</p>}
          {erro && <p className="erro">{erro}</p>}
          <div className="acoes esquerda">
            <button type="button" className="primario" disabled={!podeGerar} onClick={gerar}>
              {gerando ? 'Gerando…' : 'Gerar PDF'}
            </button>
          </div>
          <small>O PDF inclui os filtros usados, os totais e a lista de processos.</small>
        </section>
      </div>
    </>
  )
}
