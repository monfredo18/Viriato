/** Marca do Viriato: três etapas conectadas (fluxo de processo). */
export default function Logo({ tamanho = 28 }: { tamanho?: number }) {
  return (
    <svg width={tamanho} height={tamanho} viewBox="0 0 32 32" role="img" aria-label="Viriato">
      <rect width="32" height="32" rx="8" fill="var(--cor)" />
      <path d="M9 21l7-10 7 10" fill="none" stroke="#fff" strokeOpacity=".55" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
      <circle cx="9" cy="21" r="3" fill="#fff" />
      <circle cx="16" cy="11" r="3" fill="#fff" />
      <circle cx="23" cy="21" r="3" fill="#fff" />
    </svg>
  )
}
