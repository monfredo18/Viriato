import logo from './assets/logo-viriato.png'

/**
 * Marca do Grupo Viriato. A imagem tem fundo transparente e letras escuras, então fica sobre um
 * fundo branco para ser legível também na barra marinho e no tema escuro.
 */
export default function Logo({ altura = 40 }: { altura?: number }) {
  return (
    <span className="logo-selo">
      <img src={logo} alt="Grupo Viriato" height={altura} style={{ height: altura, width: 'auto' }} />
    </span>
  )
}
