/** Marca do Viriato: "V" marinho sobre o azul principal, com a barra dourada da identidade. */
export default function Logo({ tamanho = 28 }: { tamanho?: number }) {
  return (
    <svg width={tamanho} height={tamanho} viewBox="0 0 32 32" role="img" aria-label="Viriato">
      <rect width="32" height="32" rx="3" fill="#6b97ff" />
      <path d="M7.5 8.5L16 24l8.5-15.5" fill="none" stroke="#0b1430" strokeWidth="4" strokeLinejoin="miter" />
      <path d="M0 27.5h32V29a3 3 0 0 1-3 3H3a3 3 0 0 1-3-3z" fill="#d4af37" />
    </svg>
  )
}
