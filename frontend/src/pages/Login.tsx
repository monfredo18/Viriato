import { useState, type FormEvent } from 'react'
import { api, type Sessao } from '../api'
import { useAuth } from '../auth'
import BotaoTema from '../tema'
import Logo from '../Logo'

export default function Login() {
  const { entrar } = useAuth()
  const [f, setF] = useState({ nome: '', senha: '' })
  const [erro, setErro] = useState('')
  const [enviando, setEnviando] = useState(false)

  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF({ ...f, [k]: e.target.value })

  const enviar = async (e: FormEvent) => {
    e.preventDefault()
    setErro('')
    setEnviando(true)
    try {
      entrar(await api.post<Sessao>('/auth/login', f))
    } catch (err) {
      setErro((err as Error).message)
    } finally {
      setEnviando(false)
    }
  }

  return (
    <div className="login">
      <aside className="login-lado">
        <div className="login-marca"><Logo tamanho={40} /><span>Viriato</span></div>
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
          {erro && <p className="erro">{erro}</p>}
          <button className="primario" disabled={enviando}>Entrar</button>
        </form>
      </div>
    </div>
  )
}
