import { useState, type FormEvent } from 'react'
import { api, type Sessao } from '../api'
import { useAuth } from '../auth'
import BotaoTema from '../tema'
import Logo from '../Logo'

export default function Login() {
  const { entrar } = useAuth()
  const [registro, setRegistro] = useState(false)
  const [f, setF] = useState({ empresa: '', nome: '', email: '', senha: '' })
  const [erro, setErro] = useState('')
  const [enviando, setEnviando] = useState(false)

  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF({ ...f, [k]: e.target.value })

  const enviar = async (e: FormEvent) => {
    e.preventDefault()
    setErro('')
    setEnviando(true)
    try {
      const s = registro
        ? await api.post<Sessao>('/auth/registrar-empresa', f)
        : await api.post<Sessao>('/auth/login', { email: f.email, senha: f.senha })
      entrar(s)
    } catch (err) {
      setErro((err as Error).message)
    } finally {
      setEnviando(false)
    }
  }

  return (
    <div className="login">
      <BotaoTema className="tema-flutuante" />
      <form className="cartao" onSubmit={enviar}>
        <div className="login-marca"><Logo tamanho={40} /><h1>Viriato</h1></div>
        <p className="sub">{registro ? 'Cadastre sua empresa para começar' : 'Entre para acompanhar seus processos'}</p>
        {registro && (
          <>
            <label>Empresa<input required value={f.empresa} onChange={set('empresa')} /></label>
            <label>Seu nome<input required value={f.nome} onChange={set('nome')} /></label>
          </>
        )}
        <label>E-mail<input type="email" required value={f.email} onChange={set('email')} /></label>
        <label>Senha<input type="password" required minLength={registro ? 8 : 1} value={f.senha} onChange={set('senha')} /></label>
        {registro && <small>Mínimo de 8 caracteres.</small>}
        {erro && <p className="erro">{erro}</p>}
        <button className="primario" disabled={enviando}>{registro ? 'Criar empresa' : 'Entrar'}</button>
        <button type="button" className="link" onClick={() => { setRegistro(!registro); setErro('') }}>
          {registro ? 'Já tenho conta' : 'Cadastrar minha empresa'}
        </button>
      </form>
    </div>
  )
}
