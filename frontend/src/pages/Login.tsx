import { useState, type FormEvent } from 'react'
import { api, ApiError, type Sessao } from '../api'
import { useAuth } from '../auth'
import BotaoTema from '../tema'
import Logo from '../Logo'

export default function Login() {
  const { entrar } = useAuth()
  const [f, setF] = useState({ nome: '', senha: '', codigo: '' })
  const [erro, setErro] = useState('')
  const [bloqueada, setBloqueada] = useState(false)
  const [pedeCodigo, setPedeCodigo] = useState(false)
  const [enviando, setEnviando] = useState(false)

  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF({ ...f, [k]: e.target.value })

  const enviar = async (e: FormEvent) => {
    e.preventDefault()
    setErro('')
    setBloqueada(false)
    setEnviando(true)
    try {
      entrar(await api.post<Sessao>('/auth/login', { nome: f.nome, senha: f.senha, ...(pedeCodigo ? { codigo: f.codigo } : {}) }))
    } catch (err) {
      const codigo = err instanceof ApiError ? err.codigo : undefined
      if (codigo === 'CODIGO_2FA') setPedeCodigo(true)
      else if (codigo === 'CONTA_BLOQUEADA') setBloqueada(true)
      else setErro((err as Error).message)
    } finally {
      setEnviando(false)
    }
  }

  return (
    <div className="login">
      <aside className="login-lado">
        <div className="login-marca"><Logo altura={92} /></div>
        <div className="login-texto">
          <h1>Controle de processos</h1>
          <p>Etapas, prazos, responsáveis e documentos de cada processo, com histórico completo.</p>
        </div>
        <p className="login-rodape">Acesso restrito a usuários autorizados.</p>
      </aside>
      <div className="login-form">
        <BotaoTema className="tema-flutuante" />
        <form className="cartao" onSubmit={enviar}>
          <h2>Acessar o sistema</h2>
          <p className="sub">Informe seu nome e sua senha para continuar.</p>
          <label>Nome<input required autoFocus autoComplete="username" value={f.nome} onChange={set('nome')} /></label>
          <label>Senha<input type="password" required autoComplete="current-password" value={f.senha} onChange={set('senha')} /></label>
          {pedeCodigo && (
            <label>Código do aplicativo autenticador
              <input required autoFocus inputMode="numeric" autoComplete="one-time-code" maxLength={7} placeholder="000000" value={f.codigo} onChange={set('codigo')} />
            </label>
          )}
          {bloqueada && (
            <div className="aviso-bloqueio" role="alert">
              <b>Conta bloqueada</b>
              <span>Houve muitas tentativas com dados incorretos. Entre em contato com o suporte para desbloquear o seu acesso.</span>
            </div>
          )}
          {erro && <p className="erro">{erro}</p>}
          <button className="primario" disabled={enviando}>Entrar</button>
        </form>
      </div>
    </div>
  )
}
