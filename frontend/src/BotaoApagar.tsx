/** Botão "X" para apagar: pede confirmação e só então executa. O servidor recusa se o item estiver em uso. */
export default function BotaoApagar({ nome, aviso = '', apagar }: { nome: string; aviso?: string; apagar: () => void }) {
  const rotulo = `Apagar "${nome}"`
  return (
    <button
      type="button"
      className="btn-x"
      title={rotulo}
      aria-label={rotulo}
      onClick={() => confirm(`${rotulo}? Esta ação não pode ser desfeita.${aviso ? `\n\n${aviso}` : ''}`) && apagar()}
    >
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" aria-hidden="true">
        <path d="M6 6l12 12M18 6L6 18" />
      </svg>
    </button>
  )
}
