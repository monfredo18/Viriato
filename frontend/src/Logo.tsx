import logo from './assets/logo-login.png'

/**
 * Marca do Grupo Viriato (imagem sem fundo). Sem {@code altura}, o tamanho vem do CSS da classe recebida.
 * As letras são escuras: sobre fundo escuro, a classe {@code logo-branca} a deixa toda branca.
 */
export default function Logo({ altura, className = '' }: { altura?: number; className?: string }) {
  return (
    <img className={`logo ${className}`} src={logo} alt="Grupo Viriato" style={altura ? { height: altura, width: 'auto' } : undefined} />
  )
}
