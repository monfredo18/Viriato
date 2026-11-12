import { Navigate, NavLink, Route, Routes } from 'react-router-dom'
import { useAuth } from './auth'
import Sino from './Sino'
import BotaoTema from './tema'
import Logo from './Logo'
import Login from './pages/Login'
import Processos from './pages/Processos'
import ProcessoDetalhe from './pages/ProcessoDetalhe'
import Cadastros from './pages/Cadastros'
import Fluxos from './pages/Fluxos'
import Usuarios from './pages/Usuarios'
import Relatorios from './pages/Relatorios'
import DefinirSenha from './pages/DefinirSenha'
import Painel from './pages/Painel'

const iniciais = (nome?: string) =>
  (nome ?? '?').split(' ').filter(Boolean).slice(0, 2).map((p) => p[0]).join('').toUpperCase()

function Layout() {
  const { usuario, empresa, sair } = useAuth()
  return (
    <>
      <header className="topo">
        <span className="marca"><Logo altura={42} /></span>
        <nav>
          <NavLink to="/processos">Processos</NavLink>
          <NavLink to="/painel">Painel</NavLink>
          <NavLink to="/cadastros">Cadastros</NavLink>
          <NavLink to="/fluxos">Fluxos</NavLink>
          <NavLink to="/usuarios">Usuários</NavLink>
          <NavLink to="/relatorios">Relatórios</NavLink>
        </nav>
        <span className="espaco" />
        <BotaoTema />
        <Sino />
        <div className="perfil">
          <span className="avatar" aria-hidden="true">{iniciais(usuario?.nome)}</span>
          <span className="perfil-txt">
            <b>{usuario?.nome}</b>
            <small>{empresa}</small>
          </span>
        </div>
        <button className="sair" onClick={sair}>Sair</button>
      </header>
      <main>
        <Routes>
          <Route path="/processos" element={<Processos />} />
          <Route path="/processos/:id" element={<ProcessoDetalhe />} />
          <Route path="/painel" element={<Painel />} />
          <Route path="/cadastros" element={<Cadastros />} />
          <Route path="/fluxos" element={<Fluxos />} />
          <Route path="/usuarios" element={<Usuarios />} />
          <Route path="/relatorios" element={<Relatorios />} />
          <Route path="*" element={<Navigate to="/processos" />} />
        </Routes>
      </main>
    </>
  )
}

export default function App() {
  const { usuario, carregando } = useAuth()
  if (carregando) return <p className="centro">Carregando…</p>
  if (!usuario) return <Login />
  // Senha inicial ou zerada: nada do sistema abre antes de a pessoa criar a própria senha.
  return usuario.deveTrocarSenha ? <DefinirSenha /> : <Layout />
}
