import { Navigate, NavLink, Route, Routes } from 'react-router-dom'
import { useAuth } from './auth'
import Sino from './Sino'
import Login from './pages/Login'
import Processos from './pages/Processos'
import ProcessoDetalhe from './pages/ProcessoDetalhe'
import Fluxos from './pages/Fluxos'
import Usuarios from './pages/Usuarios'

function Layout() {
  const { usuario, empresa, sair, podeGerenciar, ehAdmin } = useAuth()
  return (
    <>
      <header className="topo">
        <strong className="marca">Viriato</strong>
        <nav>
          <NavLink to="/processos">Processos</NavLink>
          {podeGerenciar && <NavLink to="/fluxos">Fluxos</NavLink>}
          {ehAdmin && <NavLink to="/usuarios">Usuários</NavLink>}
        </nav>
        <span className="espaco" />
        <Sino />
        <span className="quem">
          {usuario?.nome} · {empresa}
        </span>
        <button className="link" onClick={sair}>Sair</button>
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
