import { api, type Usuario } from '../api'
import { useAuth } from '../auth'
import BotaoTema from '../tema'
import Logo from '../Logo'
import { DoisFatores } from '../Perfil'

/**
 * Quando a empresa exige a verificação em duas etapas dos administradores (SEGURANCA_EXIGIR_2FA_ADMIN), o administrador
 * que ainda não ativou só vê esta tela. O servidor também bloqueia o resto da API até a ativação.
 */
export default function AtivarDoisFatores() {
  const { usuario, atualizarUsuario, sair } = useAuth()
  if (!usuario) return null
  return (
    <div className="login">
      <aside className="login-lado">
        <div className="login-marca"><Logo className="login-logo" /></div>
        <div className="login-texto">
          <h1>Verificação em duas etapas</h1>
          <p>Por segurança, contas de administrador precisam de um código do aplicativo autenticador para entrar no sistema.</p>
        </div>
        <p className="login-rodape">Acesso restrito a usuários autorizados.</p>
      </aside>
      <div className="login-form">
        <BotaoTema className="tema-flutuante" />
        <div className="cartao">
          <h2>Ative para continuar</h2>
          <p className="sub">Olá, {usuario.nome}. Configure o aplicativo autenticador e confirme o primeiro código.</p>
          <DoisFatores usuario={usuario} atualizar={(u: Usuario) => atualizarUsuario(u)} />
          <button type="button" className="link" onClick={async () => { try { await api.post('/me/sair') } catch { /* sem conexão */ } sair() }}>Sair</button>
        </div>
      </div>
    </div>
  )
}
