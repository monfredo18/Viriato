import { Navigate, NavLink, Route, Routes } from 'react-router-dom'
import { useAuth } from './auth'
import Sino from './Sino'
import BotaoTema from './tema'
import Logo from './Logo'
import Login from './pages/Login'
import Processos from './pages/Processos'
import ProcessoDetalhe from './pages/ProcessoDetalhe'
import Fluxos from './pages/Fluxos'
import Usuarios from './pages/Usuarios'

const iniciais = (nome?: string) =>
  (nome ?? '?').split(' ').filter(Boolean).slice(0, 2).map((p) => p[0]).join('').toUpperCase()

function Layout() {
  const { usuario, empresa, sair, podeGerenciar, ehAdmin } = useAuth()
  return (
    <>
      <header className="topo">
        <span className="marca"><Logo /> Viriato</span>
        <nav>
          <NavLink to="/processos">Processos</NavLink>
          {podeGerenciar && <NavLink to="/fluxos">Fluxos</NavLink>}
          {ehAdmin && <NavLink to="/usuarios">Usuários</NavLink>}
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
          <Route path="/fluxos" element={podeGerenciar ? <Fluxos /> : <Navigate to="/processos" />} />
          <Route path="/usuarios" element={ehAdmin ? <Usuarios /> : <Navigate to="/processos" />} />
          <Route path="*" element={<Navigate to="/processos" />} />
        </Routes>
      </main>
    </>
  )
}

export default function App() {
  const { usuario, carregando } = useAuth()
  if (carregando) return <p className="centro">Carregando…</p>
  return usuario ? <Layout /> : <Login />
}
