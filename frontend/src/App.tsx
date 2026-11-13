import { useEffect, useState, type FormEvent } from 'react'
import { Link, Navigate, NavLink, Route, Routes, useLocation, useNavigate } from 'react-router-dom'
import { api, ROTULO_PAPEL, type Pagina, type Processo } from './api'
import Icone, { type NomeIcone } from './Icones'
import { useAuth } from './auth'
import Sino from './Sino'
import Ajuda from './Ajuda'
import Avatar from './Avatar'
import Perfil from './Perfil'
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
import Inicio from './pages/Inicio'

const ITENS: { para: string; rotulo: string; icone: NomeIcone; fim?: boolean }[] = [
  { para: '/inicio', rotulo: 'Início', icone: 'inicio' },
  { para: '/processos', rotulo: 'Processos', icone: 'processos', fim: true },
  { para: '/painel', rotulo: 'Painel', icone: 'painel' },
  { para: '/cadastros', rotulo: 'Cadastros', icone: 'cadastros' },
  { para: '/fluxos', rotulo: 'Fluxos', icone: 'fluxos' },
  { para: '/usuarios', rotulo: 'Usuários', icone: 'usuarios' },
  { para: '/relatorios', rotulo: 'Relatórios', icone: 'relatorios' },
]

function Layout() {
  const { usuario, empresa, sair } = useAuth()
  const nav = useNavigate()
  const local = useLocation()
  const [menuAberto, setMenuAberto] = useState(false)
  const [busca, setBusca] = useState('')
  const [minhas, setMinhas] = useState(0)
  const [perfilAberto, setPerfilAberto] = useState(false)

  // O menu lateral fecha ao trocar de tela (no celular ele abre sobre o conteúdo).
  useEffect(() => { setMenuAberto(false) }, [local.pathname, local.search])

  // Quantidade de processos em aberto sob responsabilidade de quem está logado ("Minhas tarefas").
  useEffect(() => {
    api.get<Pagina<Processo>>('/processos?meus=true&status=ABERTO&tamanho=1').then((r) => setMinhas(r.totalElements)).catch(() => {})
  }, [local.pathname])

  const buscar = (e: FormEvent) => {
    e.preventDefault()
    const termo = busca.trim()
    nav(termo ? `/processos?busca=${encodeURIComponent(termo)}` : '/processos')
  }

  const aMinhas = local.pathname === '/processos' && local.search.includes('meus=1')

  return (
    <div className={`app ${menuAberto ? 'menu-aberto' : ''}`}>
      <aside className="lateral" aria-label="Menu principal">
        <div className="lateral-marca"><Logo altura={48} /></div>
        <nav>
          {ITENS.map((i) => (
            <NavLink key={i.para} to={i.para} end={i.fim} className={({ isActive }) => (isActive && !(i.para === '/processos' && aMinhas) ? 'ativo' : undefined)}>
              <Icone nome={i.icone} /><span>{i.rotulo}</span>
            </NavLink>
          ))}
          <Link to="/processos?meus=1" className={aMinhas ? 'ativo' : undefined}>
            <Icone nome="tarefas" /><span>Minhas tarefas</span>
            {minhas > 0 && <b className="contador">{minhas > 99 ? '99+' : minhas}</b>}
          </Link>
        </nav>
        <div className="lateral-rodape">
          <span className="ponto-online" aria-hidden="true" />
          <span><b>{empresa || 'Viriato'}</b><small>Sistema online</small></span>
        </div>
      </aside>
      <button type="button" className="menu-fundo" aria-label="Fechar menu" onClick={() => setMenuAberto(false)} />

      <div className="conteudo">
        <header className="topo">
          <button type="button" className="menu-btn" aria-label="Abrir menu" onClick={() => setMenuAberto(true)}><Icone nome="menu" /></button>
          <form className="busca-global" role="search" onSubmit={buscar}>
            <Icone nome="busca" tamanho={18} />
            <input value={busca} onChange={(e) => setBusca(e.target.value)} placeholder="Pesquisar processos, empresas…" aria-label="Pesquisar processos" />
          </form>
          <span className="espaco" />
          <BotaoTema />
          <Ajuda />
          <Sino />
          <button type="button" className="perfil" onClick={() => setPerfilAberto(true)} title="Meu perfil e foto">
            <Avatar id={usuario?.id} nome={usuario?.nome} versao={usuario?.fotoVersao} tamanho={40} />
            <span className="perfil-txt">
              <b>{usuario?.nome}</b>
              <small>{usuario ? ROTULO_PAPEL[usuario.papel] : ''}{usuario?.setorNome ? ` · ${usuario.setorNome}` : ''}</small>
            </span>
          </button>
          <button className="sair" onClick={sair} title="Sair do sistema"><Icone nome="sair" tamanho={16} /><span>Sair</span></button>
        </header>
        <main>
          <Routes>
            <Route path="/inicio" element={<Inicio />} />
            <Route path="/processos" element={<Processos />} />
            <Route path="/processos/:id" element={<ProcessoDetalhe />} />
            <Route path="/painel" element={<Painel />} />
            <Route path="/cadastros" element={<Cadastros />} />
            <Route path="/fluxos" element={<Fluxos />} />
            <Route path="/usuarios" element={<Usuarios />} />
            <Route path="/relatorios" element={<Relatorios />} />
            <Route path="*" element={<Navigate to="/inicio" />} />
          </Routes>
        </main>
      </div>
      {perfilAberto && <Perfil fechar={() => setPerfilAberto(false)} />}
    </div>
  )
}

export default function App() {
  const { usuario, carregando } = useAuth()
  if (carregando) return <p className="centro">Carregando…</p>
  if (!usuario) return <Login />
  // Senha inicial ou zerada: nada do sistema abre antes de a pessoa criar a própria senha.
  return usuario.deveTrocarSenha ? <DefinirSenha /> : <Layout />
}
