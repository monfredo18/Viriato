import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, type Notificacao } from './api'

export default function Sino() {
  const [total, setTotal] = useState(0)
  const [aberto, setAberto] = useState(false)
  const [itens, setItens] = useState<Notificacao[]>([])
  const nav = useNavigate()
  const ref = useRef<HTMLDivElement>(null)

  const contar = useCallback(() => {
    api.get<{ total: number }>('/notificacoes/nao-lidas').then((r) => setTotal(r.total)).catch(() => {})
  }, [])

  useEffect(() => {
    contar()
    const t = setInterval(contar, 30000)
    return () => clearInterval(t)
  }, [contar])

  useEffect(() => {
    const fora = (e: MouseEvent) => ref.current && !ref.current.contains(e.target as Node) && setAberto(false)
    document.addEventListener('mousedown', fora)
    return () => document.removeEventListener('mousedown', fora)
  }, [])

  const abrir = async () => {
    const novo = !aberto
    setAberto(novo)
    if (novo) setItens(await api.get<Notificacao[]>('/notificacoes'))
  }

  const clicar = async (n: Notificacao) => {
    if (!n.lida) await api.post(`/notificacoes/${n.id}/ler`)
    setAberto(false)
    contar()
    if (n.processoId) nav(`/processos/${n.processoId}`)
  }

  const lerTodas = async () => {
    await api.post('/notificacoes/ler-todas')
    setItens((l) => l.map((n) => ({ ...n, lida: true })))
    setTotal(0)
  }

  return (
    <div className="sino" ref={ref}>
      <button className="link" onClick={abrir} aria-label="Notificações">
        🔔{total > 0 && <span className="badge">{total}</span>}
      </button>
      {aberto && (
        <div className="menu-notif">
          <div className="menu-notif-topo">
            <strong>Notificações</strong>
            <button className="link" onClick={lerTodas}>Marcar todas como lidas</button>
          </div>
          {itens.length === 0 && <p className="vazio">Nada por aqui.</p>}
          {itens.map((n) => (
            <div key={n.id} className={`notif ${n.lida ? '' : 'nao-lida'}`} onClick={() => clicar(n)}>
              <b>{n.titulo}</b>
              {n.mensagem && <div>{n.mensagem}</div>}
              <small>{new Date(n.criadoEm).toLocaleString('pt-BR')}</small>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
