import { useState, type FormEvent } from 'react'
import { api, type Usuario } from '../api'
import { useAuth } from '../auth'
import BotaoTema from '../tema'
import Logo from '../Logo'

/**
 * Primeiro acesso (ou senha zerada pelo administrador): a pessoa troca a senha inicial por uma própria
 * antes de usar o sistema. O servidor também bloqueia o resto da API enquanto isso não for feito.
 */
export default function DefinirSenha() {
  const { usuario, atualizarUsuario, sair } = useAuth()
  const [nova, setNova] = useState('')
  const [confirma, setConfirma] = useState('')
  const [erro, setErro] = useState('')
  const [enviando, setEnviando] = useState(false)

  const enviar = async (e: FormEvent) => {
    e.preventDefault()
    setErro('')
    if (nova !== confirma) return setErro('As duas senhas não são iguais')
    setEnviando(true)
    try {
      atualizarUsuario(await api.put<Usuario>('/me/senha', { novaSenha: nova }))
    } catch (err) {
      setErro((err as Error).message)
      setEnviando(false)
    }
  }

  return (
    <div className="login">
      <aside className="login-lado">
        <div className="login-marca"><Logo altura={92} /></div>
        <div className="login-texto">
          <h1>Primeiro acesso</h1>
          <p>Por segurança, a senha inicial é provisória. Crie a sua senha para começar a usar o sistema.</p>
        </div>
        <p className="login-rodape">Acesso restrito a usuários autorizados.</p>
      </aside>
      <div className="login-form">
        <BotaoTema className="tema-flutuante" />
        <form className="cartao" onSubmit={enviar}>
          <h2>Crie a sua senha</h2>
          <p className="sub">Olá, {usuario?.nome}. Esta senha substituirá a senha inicial.</p>
          <label>Nova senha<input type="password" required minLength={8} maxLength={72} autoFocus autoComplete="new-password" value={nova} onChange={(e) => setNova(e.target.value)} /></label>
          <label>Repita a nova senha<input type="password" required minLength={8} maxLength={72} autoComplete="new-password" value={confirma} onChange={(e) => setConfirma(e.target.value)} /></label>
          <small>Mínimo de 8 caracteres e diferente da senha inicial.</small>
          {erro && <p className="erro">{erro}</p>}
          <button className="primario" disabled={enviando}>Salvar senha e entrar</button>
          <button type="button" className="link" onClick={sair}>Sair</button>
        </form>
      </div>
    </div>
  )
}
