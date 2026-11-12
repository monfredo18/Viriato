/** Botão "X" para apagar: pede confirmação e só então executa (ou, com semConfirmar, entrega a decisão a quem chama). */
export default function BotaoApagar({ nome, aviso = '', apagar, semConfirmar = false }: { nome: string; aviso?: string; apagar: () => void; semConfirmar?: boolean }) {
  const rotulo = `Apagar "${nome}"`
  return (
    <button
      type="button"
      className="btn-x"
      title={rotulo}
      aria-label={rotulo}
      onClick={() => (semConfirmar || confirm(`${rotulo}? Esta ação não pode ser desfeita.${aviso ? `\n\n${aviso}` : ''}`)) && apagar()}
    >
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" aria-hidden="true">
        <path d="M6 6l12 12M18 6L6 18" />
      </svg>
    </button>
  )
}
