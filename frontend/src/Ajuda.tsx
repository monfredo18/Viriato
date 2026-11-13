import { useCallback, useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import { createPortal } from 'react-dom'
import { api, type ConversaAjuda, type ConversaAjudaAberta, type MensagemAjuda } from './api'
import { useAuth } from './auth'
import Icone from './Icones'

const MAX = 2000

/** Hoje: só a hora; outros dias: data e hora. */
function quando(iso: string) {
  const d = new Date(iso)
  const hora = d.toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' })
  return d.toDateString() === new Date().toDateString() ? hora : `${d.toLocaleDateString('pt-BR', { day: '2-digit', month: '2-digit' })} ${hora}`
}

interface ConversaProps {
  /** Muda quando é outra conversa: recarrega do zero. */
  chave: string
  carregar: () => Promise<MensagemAjuda[]>
  enviar: (texto: string) => Promise<unknown>
  /** Quem olha é administrador? Define de que lado ficam os balões. */
  admin: boolean
  vazio: string
  aoMudar: () => void
}

/** Troca de mensagens: atualiza sozinha a cada poucos segundos e rola até a mais recente. */
function Conversa({ chave, carregar, enviar, admin, vazio, aoMudar }: ConversaProps) {
  const [msgs, setMsgs] = useState<MensagemAjuda[] | null>(null)
  const [texto, setTexto] = useState('')
  const [erro, setErro] = useState('')
  const [enviando, setEnviando] = useState(false)
  const lista = useRef<HTMLDivElement>(null)
  const fns = useRef({ carregar, aoMudar })
  fns.current = { carregar, aoMudar }

  const atualizar = useCallback(async () => {
    try {
      const l = await fns.current.carregar()
      setMsgs((antes) => (antes && antes.length === l.length && antes[antes.length - 1]?.id === l[l.length - 1]?.id ? antes : l))
      setErro('')
      fns.current.aoMudar()
    } catch (e) {
      setErro((e as Error).message)
    }
  }, [])

  useEffect(() => {
    setMsgs(null)
    atualizar()
    const t = setInterval(atualizar, 4000)
    return () => clearInterval(t)
  }, [chave, atualizar])

  useEffect(() => {
    if (lista.current) lista.current.scrollTop = lista.current.scrollHeight
  }, [msgs?.length, chave])

  const mandar = async (e?: FormEvent) => {
    e?.preventDefault()
    const t = texto.trim()
    if (!t || enviando) return
    setEnviando(true)
    try {
      await enviar(t)
      setTexto('')
      setErro('')
      await atualizar()
    } catch (err) {
      setErro((err as Error).message)
    } finally {
      setEnviando(false)
    }
  }

  const teclas = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); mandar() }
  }

  return (
    <>
      <div className="chat-lista" ref={lista} aria-live="polite">
        {msgs === null && !erro && <p className="vazio">Carregando…</p>}
        {msgs !== null && msgs.length === 0 && <p className="chat-vazio">{vazio}</p>}
        {msgs?.map((m) => {
          const minha = admin ? m.deAdmin : !m.deAdmin
          return (
            <div key={m.id} className={`balao ${minha ? 'minha' : 'outra'} ${m.deAdmin ? 'da-equipe' : ''}`}>
              {!minha && <b>{m.deAdmin ? `${m.autorNome} · administração` : m.autorNome}</b>}
              <p>{m.texto}</p>
              <small>{quando(m.criadoEm)}</small>
            </div>
          )
        })}
      </div>
      {erro && <p className="erro chat-erro">{erro}</p>}
      <form className="chat-form" onSubmit={mandar}>
        <textarea
          rows={1} value={texto} maxLength={MAX} placeholder="Escreva sua mensagem…" aria-label="Mensagem"
          onChange={(e) => setTexto(e.target.value)} onKeyDown={teclas}
        />
        <button className="primario" disabled={!texto.trim() || enviando} aria-label="Enviar mensagem" title="Enviar (Enter)"><Icone nome="enviar" tamanho={18} /></button>
      </form>
    </>
  )
}

/** Administrador: lista de conversas e, ao escolher uma, a troca de mensagens com aquela pessoa. */
function Atendimento({ aoMudar }: { aoMudar: () => void }) {
  const [conversas, setConversas] = useState<ConversaAjuda[] | null>(null)
  const [aberta, setAberta] = useState<ConversaAjuda | null>(null)
  const [erro, setErro] = useState('')

  const carregar = useCallback(() => {
    api.get<ConversaAjuda[]>('/ajuda/conversas').then((l) => { setConversas(l); setErro('') }).catch((e) => setErro(e.message))
  }, [])

  useEffect(() => {
    if (aberta) return
    carregar()
    const t = setInterval(carregar, 5000)
    return () => clearInterval(t)
  }, [aberta, carregar])

  if (aberta) {
    return (
      <>
        <div className="chat-sub">
          <button className="link" onClick={() => { setAberta(null); aoMudar() }}><Icone nome="voltar" tamanho={16} />Conversas</button>
          <span><b>{aberta.pessoa}</b>{aberta.setor && <small> · {aberta.setor}</small>}</span>
        </div>
        <Conversa
          chave={`c${aberta.id}`} admin vazio="Sem mensagens."
          carregar={() => api.get<ConversaAjudaAberta>(`/ajuda/conversas/${aberta.id}`).then((d) => d.mensagens)}
          enviar={(texto) => api.post(`/ajuda/conversas/${aberta.id}/mensagens`, { texto })}
          aoMudar={aoMudar}
        />
      </>
    )
  }

  return (
    <div className="chat-conversas">
      {erro && <p className="erro chat-erro">{erro}</p>}
      {conversas === null && !erro && <p className="vazio">Carregando…</p>}
      {conversas?.length === 0 && <p className="chat-vazio">Nenhuma conversa ainda. Quando alguém pedir ajuda, aparece aqui.</p>}
      {conversas?.map((c) => (
        <button key={c.id} className={`conversa-item ${c.naoLidas > 0 ? 'nova' : ''}`} onClick={() => setAberta(c)}>
          <span className="avatar-mini" aria-hidden="true">{c.pessoa.slice(0, 1).toUpperCase()}</span>
          <span className="conversa-txt">
            <b>{c.pessoa}{c.setor && <small> · {c.setor}</small>}</b>
            <span>{c.ultimaDeAdmin ? 'Você: ' : ''}{c.ultimaTexto}</span>
          </span>
          <span className="conversa-meta">
            <small>{quando(c.atualizadoEm)}</small>
            {c.naoLidas > 0 && <i className="contador">{c.naoLidas}</i>}
          </span>
        </button>
      ))}
    </div>
  )
}

/** Botão de ajuda da barra superior: abre o chat com os administradores (e, para eles, o atendimento). */
export default function Ajuda() {
  const { ehAdmin } = useAuth()
  const [aberto, setAberto] = useState(false)
  const [naoLidas, setNaoLidas] = useState(0)
  const botao = useRef<HTMLDivElement>(null)
  const janela = useRef<HTMLElement>(null)

  const contar = useCallback(() => {
    api.get<{ total: number }>('/ajuda/nao-lidas').then((r) => setNaoLidas(r.total)).catch(() => {})
  }, [])

  useEffect(() => {
    contar()
    const t = setInterval(contar, 15000)
    return () => clearInterval(t)
  }, [contar])

  useEffect(() => {
    if (!aberto) return
    const dentro = (e: MouseEvent) => [botao.current, janela.current].some((el) => el?.contains(e.target as Node))
    const fora = (e: MouseEvent) => !dentro(e) && setAberto(false)
    const esc = (e: globalThis.KeyboardEvent) => e.key === 'Escape' && setAberto(false)
    document.addEventListener('mousedown', fora)
    document.addEventListener('keydown', esc)
    return () => { document.removeEventListener('mousedown', fora); document.removeEventListener('keydown', esc) }
  }, [aberto])

  const fechar = () => { setAberto(false); contar() }

  return (
    <div className="ajuda" ref={botao}>
      <button type="button" className="ajuda-btn" onClick={() => (aberto ? fechar() : setAberto(true))} aria-expanded={aberto} aria-haspopup="dialog" title="Ajuda: falar com os administradores">
        <Icone nome="ajuda" tamanho={19} /><span>Ajuda</span>
        {naoLidas > 0 && <b className="badge">{naoLidas > 99 ? '99+' : naoLidas}</b>}
      </button>
      {aberto && createPortal(
        <section className="chat" ref={janela} role="dialog" aria-label={ehAdmin ? 'Atendimento de ajuda' : 'Ajuda: conversa com os administradores'}>
          <header className="chat-topo">
            <span className="chat-icone"><Icone nome="ajuda" tamanho={20} /></span>
            <div>
              <strong>{ehAdmin ? 'Atendimento' : 'Ajuda'}</strong>
              <small>{ehAdmin ? 'Conversas com as pessoas da empresa' : 'Fale com os administradores'}</small>
            </div>
            <button className="chat-fechar" onClick={fechar} aria-label="Fechar"><Icone nome="fechar" tamanho={18} /></button>
          </header>
          {ehAdmin ? <Atendimento aoMudar={contar} /> : (
            <Conversa
              chave="minha" admin={false} aoMudar={contar}
              vazio="Descreva sua dúvida ou o problema. Os administradores respondem por aqui mesmo."
              carregar={() => api.get<MensagemAjuda[]>('/ajuda/minha')}
              enviar={(texto) => api.post('/ajuda/minha', { texto })}
            />
          )}
        </section>,
        document.body,
      )}
    </div>
  )
}
