import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react'
import { api, setOnUnauthorized, tokenStore, type Sessao, type Usuario } from './api'

interface AuthCtx {
  usuario: Usuario | null
  empresa: string
  carregando: boolean
  entrar: (s: Sessao) => void
  atualizarUsuario: (u: Usuario) => void
  sair: () => void
  podeGerenciar: boolean
  ehAdmin: boolean
}

const Ctx = createContext<AuthCtx>(null!)
export const useAuth = () => useContext(Ctx)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [usuario, setUsuario] = useState<Usuario | null>(null)
  const [empresa, setEmpresa] = useState(() => localStorage.getItem('viriato.empresa') ?? '')
  const [carregando, setCarregando] = useState(!!tokenStore.get())

  const sair = useCallback(() => {
    tokenStore.clear()
    setUsuario(null)
  }, [])

  const entrar = useCallback((s: Sessao) => {
    tokenStore.set(s.token)
    localStorage.setItem('viriato.empresa', s.empresa)
    setEmpresa(s.empresa)
    setUsuario(s.usuario)
  }, [])

  useEffect(() => {
    setOnUnauthorized(sair)
    if (!tokenStore.get()) return
    api.get<Usuario>('/me').then(setUsuario).catch(sair).finally(() => setCarregando(false))
  }, [sair])

  const papel = usuario?.papel
  return (
    <Ctx.Provider value={{ usuario, empresa, carregando, entrar, atualizarUsuario: setUsuario, sair, podeGerenciar: papel === 'ADMIN' || papel === 'GESTOR', ehAdmin: papel === 'ADMIN' }}>
      {children}
    </Ctx.Provider>
  )
}
