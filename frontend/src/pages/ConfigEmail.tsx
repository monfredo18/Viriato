import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { api, type ConfigEmail as Config, type SegurancaEmail } from '../api'

interface Provedor { nome: string; host: string; porta: number; seguranca: SegurancaEmail; dica: string }
const PROVEDORES: Provedor[] = [
  { nome: 'Gmail', host: 'smtp.gmail.com', porta: 587, seguranca: 'STARTTLS',
    dica: 'Usuário: o seu e-mail completo. Senha: uma senha de aplicativo (Conta Google → Segurança → Verificação em duas etapas → Senhas de app), não a senha da conta.' },
  { nome: 'Microsoft 365 / Outlook', host: 'smtp.office365.com', porta: 587, seguranca: 'STARTTLS',
    dica: 'Usuário: o seu e-mail completo. O administrador do Microsoft 365 precisa permitir o envio autenticado (SMTP AUTH); com verificação em duas etapas, use uma senha de aplicativo.' },
  { nome: 'Brevo (Sendinblue)', host: 'smtp-relay.brevo.com', porta: 587, seguranca: 'STARTTLS',
    dica: 'Usuário e senha: os da aba SMTP & API da sua conta Brevo. O remetente precisa ser um e-mail verificado lá.' },
]

/**
 * Servidor de e-mail da empresa (só administrador). Sem configuração, o sistema usa o servidor padrão — que no Docker
 * local é o Mailpit, uma caixa de captura que nunca entrega em caixas de entrada reais.
 */
export default function ConfigEmail() {
  const [cfg, setCfg] = useState<Config | null>(null)
  const [f, setF] = useState({ host: '', porta: '587', seguranca: 'STARTTLS' as SegurancaEmail, usuario: '', senha: '', remetente: '' })
  const [dica, setDica] = useState('')
  const [msg, setMsg] = useState<{ ok: boolean; texto: string } | null>(null)
  const [para, setPara] = useState('')
  const [ocupado, setOcupado] = useState(false)

  const aplicar = useCallback((c: Config) => {
    setCfg(c)
    setF({ host: c.host ?? '', porta: String(c.porta ?? 587), seguranca: c.seguranca, usuario: c.usuario ?? '', senha: '', remetente: c.remetente ?? '' })
  }, [])
  useEffect(() => { api.get<Config>('/configuracao/email').then(aplicar).catch((e) => setMsg({ ok: false, texto: e.message })) }, [aplicar])

  const escolher = (nome: string) => {
    const p = PROVEDORES.find((x) => x.nome === nome)
    setDica(p?.dica ?? '')
    if (p) setF((a) => ({ ...a, host: p.host, porta: String(p.porta), seguranca: p.seguranca }))
  }

  const salvar = async (e: FormEvent) => {
    e.preventDefault()
    setMsg(null)
    setOcupado(true)
    try {
      aplicar(await api.put<Config>('/configuracao/email', {
        host: f.host, porta: Number(f.porta), seguranca: f.seguranca, usuario: f.usuario || null, remetente: f.remetente,
        ...(f.senha ? { senha: f.senha } : {}),   // vazio mantém a senha já guardada
      }))
      setMsg({ ok: true, texto: 'Configuração salva. Use o teste abaixo para conferir se o e-mail chega.' })
    } catch (err) {
      setMsg({ ok: false, texto: (err as Error).message })
    } finally {
      setOcupado(false)
    }
  }

  const testar = async (e: FormEvent) => {
    e.preventDefault()
    setMsg(null)
    setOcupado(true)
    try {
      const r = await api.post<{ ok: boolean; mensagem: string }>('/configuracao/email/teste', { para })
      setMsg({ ok: r.ok, texto: r.mensagem })
    } catch (err) {
      setMsg({ ok: false, texto: (err as Error).message })
    } finally {
      setOcupado(false)
    }
  }

  const voltarAoPadrao = async () => {
    if (!confirm('Voltar a usar o servidor padrão do sistema? A configuração e a senha guardadas serão apagadas.')) return
    setMsg(null)
    try {
      await api.del('/configuracao/email')
      aplicar(await api.get<Config>('/configuracao/email'))
    } catch (err) {
      setMsg({ ok: false, texto: (err as Error).message })
    }
  }

  if (!cfg) return msg ? <p className="erro">{msg.texto}</p> : <p>Carregando…</p>

  return (
    <div className="grade">
      <section className="cartao">
        <h3>Servidor de e-mail</h3>
        {cfg.configurado ? (
          <p className="estado-email ok-email">Enviando por <b>{cfg.host}:{cfg.porta}</b>, com remetente <b>{cfg.remetente}</b>.</p>
        ) : cfg.padraoSoCaptura ? (
          <p className="estado-email alerta-email">
            Hoje o sistema envia pelo servidor padrão (<b>{cfg.padrao}</b>), que <b>apenas captura</b> e-mails de teste: nada chega a caixas de entrada reais.
            Configure o seu servidor ao lado para receber de verdade.
          </p>
        ) : (
          <p className="estado-email">Usando o servidor padrão do sistema (<b>{cfg.padrao}</b>). Você pode configurar um servidor próprio ao lado.</p>
        )}

        <form className="auto-form" onSubmit={testar}>
          <label>Enviar e-mail de teste para
            <input type="email" required value={para} onChange={(e) => setPara(e.target.value)} placeholder="seu@email.com" />
          </label>
          <button className="primario" disabled={ocupado}>Enviar e-mail de teste</button>
          <small>O teste usa a configuração salva. Se não chegar, veja também a pasta de spam.</small>
        </form>
        {msg && <p className={msg.ok ? 'resultado-ok' : 'erro'} role="status">{msg.texto}</p>}
      </section>

      <form className="cartao" onSubmit={salvar}>
        <h3>{cfg.configurado ? 'Alterar o servidor' : 'Configurar o seu servidor'}</h3>
        <label>Provedor
          <select defaultValue="" onChange={(e) => escolher(e.target.value)}>
            <option value="">Escolha para preencher, ou digite abaixo…</option>
            {PROVEDORES.map((p) => <option key={p.nome} value={p.nome}>{p.nome}</option>)}
          </select>
        </label>
        {dica && <small className="aviso-campo">{dica}</small>}
        <label>Servidor (SMTP)<input required maxLength={255} placeholder="smtp.seudominio.com" value={f.host} onChange={(e) => setF({ ...f, host: e.target.value })} /></label>
        <div className="duas-colunas">
          <label>Porta<input required type="number" min={1} max={65535} value={f.porta} onChange={(e) => setF({ ...f, porta: e.target.value })} /></label>
          <label>Segurança
            <select value={f.seguranca} onChange={(e) => setF({ ...f, seguranca: e.target.value as SegurancaEmail })}>
              <option value="STARTTLS">STARTTLS (porta 587)</option>
              <option value="SSL">SSL (porta 465)</option>
              <option value="NENHUMA">Nenhuma (não recomendado)</option>
            </select>
          </label>
        </div>
        <label>Usuário<input maxLength={255} autoComplete="off" placeholder="geralmente o e-mail completo" value={f.usuario} onChange={(e) => setF({ ...f, usuario: e.target.value })} /></label>
        <label>Senha<input type="password" maxLength={300} autoComplete="new-password" placeholder={cfg.temSenha ? 'Guardada. Deixe em branco para manter.' : ''} value={f.senha} onChange={(e) => setF({ ...f, senha: e.target.value })} /></label>
        <label>E-mail do remetente<input required type="email" maxLength={190} placeholder="avisos@seudominio.com" value={f.remetente} onChange={(e) => setF({ ...f, remetente: e.target.value })} /></label>
        <small>Muitos provedores só aceitam como remetente o próprio e-mail da conta ou do domínio. A senha é guardada criptografada e nunca é exibida.</small>
        <div className="acoes esquerda">
          <button className="primario" disabled={ocupado}>Salvar</button>
          {cfg.configurado && <button type="button" className="perigo" onClick={voltarAoPadrao}>Voltar ao servidor padrão</button>}
        </div>
      </form>
    </div>
  )
}
