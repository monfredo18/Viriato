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
import EmpresaFicha from './pages/EmpresaFicha'
import Obrigacoes from './pages/Obrigacoes'
import Entregas from './pages/Entregas'
import Usuarios from './pages/Usuarios'
import Relatorios from './pages/Relatorios'
import DefinirSenha from './pages/DefinirSenha'
import AtivarDoisFatores from './pages/AtivarDoisFatores'
import Painel from './pages/Painel'
import Inicio from './pages/Inicio'
import Auditoria from './pages/Auditoria'

const ITENS: { para: string; rotulo: string; icone: NomeIcone; fim?: boolean; soAdmin?: boolean; soGestao?: boolean }[] = [
  { para: '/inicio', rotulo: 'Início', icone: 'inicio' },
  { para: '/processos', rotulo: 'Processos', icone: 'processos', fim: true },
  { para: '/painel', rotulo: 'Painel', icone: 'painel' },
  { para: '/cadastros', rotulo: 'Cadastros', icone: 'cadastros' },
  { para: '/obrigacoes', rotulo: 'Obrigações', icone: 'calendario', soGestao: true },
  { para: '/entregas', rotulo: 'Entregas', icone: 'fluxos' },
  { para: '/usuarios', rotulo: 'Usuários', icone: 'usuarios' },
  { para: '/relatorios', rotulo: 'Relatórios', icone: 'relatorios' },
  { para: '/auditoria', rotulo: 'Auditoria', icone: 'escudo', soAdmin: true },
]

/**
 * Indicador do rodapé do menu: confere o servidor a cada 30 s (a cada 10 s enquanto estiver fora). Verde só se a API
 * respondeu agora; vermelho quando falha, o que também acontece se a internet de quem usa cair.
 */
function EstadoDoSistema({ empresa }: { empresa: string }) {
  const [estado, setEstado] = useState<{ ok: boolean; em: Date } | null>(null)
  useEffect(() => {
    let vivo = true
    let timer: ReturnType<typeof setTimeout>
    const conferir = async () => {
      let ok = false
      const ctl = new AbortController()
      const limite = setTimeout(() => ctl.abort(), 5000)
      try { ok = (await fetch('/api/auth/saude', { signal: ctl.signal, cache: 'no-store' })).ok } catch { ok = false }
      clearTimeout(limite)
      if (!vivo) return
      setEstado({ ok, em: new Date() })
      timer = setTimeout(conferir, ok ? 30000 : 10000)
    }
    const aoMudarRede = () => { clearTimeout(timer); conferir() }
    window.addEventListener('online', aoMudarRede)
    window.addEventListener('offline', aoMudarRede)
    conferir()
    return () => { vivo = false; clearTimeout(timer); window.removeEventListener('online', aoMudarRede); window.removeEventListener('offline', aoMudarRede) }
  }, [])
  const ok = estado?.ok ?? true   // antes da primeira resposta não alarma
  return (
    <div className="lateral-rodape" role="status">
      <span className={`ponto-online ${ok ? '' : 'fora'}`} aria-hidden="true" />
      <span>
        <b>{empresa || 'Viriato'}</b>
        <small>{ok ? 'Sistema online' : 'Sem conexão com o servidor'}{estado ? ` · ${estado.em.toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' })}` : ''}</small>
      </span>
    </div>
  )
}

function Layout() {
  const { usuario, empresa, sair, ehAdmin } = useAuth()
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

  // O servidor invalida o token ao sair: quem copiou o token não consegue mais usá-lo.
  const sairDoSistema = async () => {
    try { await api.post('/me/sair') } catch { /* sem conexão: sai assim mesmo */ }
    sair()
  }

  const buscar = (e: FormEvent) => {
    e.preventDefault()
    const termo = busca.trim()
    nav(termo ? `/processos?busca=${encodeURIComponent(termo)}` : '/processos')
  }

  const aMinhas = local.pathname === '/processos' && local.search.includes('meus=1')

  return (
    <div className={`app ${menuAberto ? 'menu-aberto' : ''}`}>
      <aside className="lateral" aria-label="Menu principal">
        <div className="lateral-marca"><Logo altura={104} className="logo-branca" /></div>
        <nav>
          {ITENS.filter((i) => (!i.soAdmin || ehAdmin) && (!i.soGestao || ehAdmin || usuario?.papel === 'GESTOR')).map((i) => (
            <NavLink key={i.para} to={i.para} end={i.fim} className={({ isActive }) => (isActive && !(i.para === '/processos' && aMinhas) ? 'ativo' : undefined)}>
              <Icone nome={i.icone} /><span>{i.rotulo}</span>
            </NavLink>
          ))}
          <Link to="/processos?meus=1" className={aMinhas ? 'ativo' : undefined}>
            <Icone nome="tarefas" /><span>Minhas tarefas</span>
            {minhas > 0 && <b className="contador">{minhas > 99 ? '99+' : minhas}</b>}
          </Link>
        </nav>
        <EstadoDoSistema empresa={empresa} />
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
          <button className="sair" onClick={sairDoSistema} title="Sair do sistema"><Icone nome="sair" tamanho={16} /><span>Sair</span></button>
        </header>
        <main>
          <Routes>
            <Route path="/inicio" element={<Inicio />} />
            <Route path="/processos" element={<Processos />} />
            <Route path="/processos/:id" element={<ProcessoDetalhe />} />
            <Route path="/painel" element={<Painel />} />
            <Route path="/cadastros" element={<Cadastros />} />
            <Route path="/cadastros/empresas/:id" element={<EmpresaFicha />} />
            <Route path="/obrigacoes" element={<Obrigacoes />} />
            <Route path="/entregas" element={<Entregas />} />
            <Route path="/fluxos" element={<Navigate to="/entregas" replace />} />
            <Route path="/usuarios" element={<Usuarios />} />
            <Route path="/relatorios" element={<Relatorios />} />
            {ehAdmin && <Route path="/auditoria" element={<Auditoria />} />}
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
  if (usuario.deveTrocarSenha) return <DefinirSenha />
  return usuario.deveAtivarDoisFatores ? <AtivarDoisFatores /> : <Layout />
}
