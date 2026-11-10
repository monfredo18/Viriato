import { useCallback, useState } from 'react'

export type Tema = 'claro' | 'escuro'
const CHAVE = 'viriato.tema'

function atual(): Tema {
  return document.documentElement.dataset.tema === 'escuro' ? 'escuro' : 'claro'
}

export function useTema(): [Tema, () => void] {
  const [tema, setTema] = useState<Tema>(atual)
  const alternar = useCallback(() => {
    const novo: Tema = atual() === 'escuro' ? 'claro' : 'escuro'
    document.documentElement.dataset.tema = novo
    try {
      localStorage.setItem(CHAVE, novo)
    } catch {
      /* armazenamento indisponível: o tema vale só nesta sessão */
    }
    setTema(novo)
  }, [])
  return [tema, alternar]
}

const Lua = () => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z" />
  </svg>
)

const Sol = () => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <circle cx="12" cy="12" r="4" />
    <path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41" />
  </svg>
)

/** Lua no tema claro (clique → escuro); sol no tema escuro (clique → claro). */
export default function BotaoTema({ className = '' }: { className?: string }) {
  const [tema, alternar] = useTema()
  const rotulo = tema === 'claro' ? 'Mudar para o tema escuro' : 'Mudar para o tema claro'
  return (
    <button type="button" className={`tema-btn ${className}`} onClick={alternar} title={rotulo} aria-label={rotulo}>
      {tema === 'claro' ? <Lua /> : <Sol />}
    </button>
  )
}
