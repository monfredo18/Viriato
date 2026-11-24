import { useRef, useState, type ChangeEvent, type FormEvent } from 'react'
import { api, ROTULO_PAPEL, type ConfigDoisFatores, type Sessao, type Usuario } from './api'
import { useAuth } from './auth'
import Avatar from './Avatar'

const LADO = 320

/** Recorta o centro da imagem em quadrado e reduz: a foto enviada pesa poucos KB, qualquer que seja a original. */
async function prepararFoto(arquivo: File): Promise<Blob> {
  const bmp = await createImageBitmap(arquivo).catch(() => { throw new Error('Não foi possível ler essa imagem. Use JPEG, PNG ou WebP.') })
  const lado = Math.min(bmp.width, bmp.height)
  const tela = document.createElement('canvas')
  tela.width = tela.height = LADO
  const ctx = tela.getContext('2d')!
  ctx.fillStyle = '#fff'
  ctx.fillRect(0, 0, LADO, LADO)
  ctx.drawImage(bmp, (bmp.width - lado) / 2, (bmp.height - lado) / 2, lado, lado, 0, 0, LADO, LADO)
  bmp.close()
  return new Promise((ok, falha) => tela.toBlob((b) => (b ? ok(b) : falha(new Error('Falha ao preparar a imagem'))), 'image/jpeg', 0.88))
}

/** Troca da própria senha: pede a atual; o servidor devolve uma sessão nova e encerra as outras. */
function TrocarSenha() {
  const { entrar } = useAuth()
  const [f, setF] = useState({ senhaAtual: '', novaSenha: '', confirmar: '' })
  const [msg, setMsg] = useState<{ erro: boolean; texto: string } | null>(null)
  const [ocupado, setOcupado] = useState(false)
  const enviar = async (e: FormEvent) => {
    e.preventDefault()
    if (f.novaSenha !== f.confirmar) return setMsg({ erro: true, texto: 'A confirmação não é igual à nova senha' })
    setOcupado(true)
    try {
      entrar(await api.post<Sessao>('/me/alterar-senha', { senhaAtual: f.senhaAtual, novaSenha: f.novaSenha }))
      setF({ senhaAtual: '', novaSenha: '', confirmar: '' })
      setMsg({ erro: false, texto: 'Senha alterada. Os outros acessos abertos foram encerrados.' })
    } catch (err) {
      setMsg({ erro: true, texto: (err as Error).message })
    } finally {
      setOcupado(false)
    }
  }
  return (
    <form className="perfil-secao" onSubmit={enviar}>
      <h4>Alterar senha</h4>
      <label>Senha atual<input type="password" required autoComplete="current-password" value={f.senhaAtual} onChange={(e) => setF({ ...f, senhaAtual: e.target.value })} /></label>
      <label>Nova senha (mínimo 8 caracteres)<input type="password" required minLength={8} maxLength={72} autoComplete="new-password" value={f.novaSenha} onChange={(e) => setF({ ...f, novaSenha: e.target.value })} /></label>
      <label>Repita a nova senha<input type="password" required minLength={8} maxLength={72} autoComplete="new-password" value={f.confirmar} onChange={(e) => setF({ ...f, confirmar: e.target.value })} /></label>
      {msg && <p className={msg.erro ? 'erro' : 'dica-campo'}>{msg.texto}</p>}
      <button disabled={ocupado}>Alterar senha</button>
    </form>
  )
}

/** Verificação em duas etapas: o segredo vai para um aplicativo autenticador e, depois, o login pede o código de 6 dígitos. */
export function DoisFatores({ usuario, atualizar }: { usuario: Usuario; atualizar: (u: Usuario) => void }) {
  const [config, setConfig] = useState<ConfigDoisFatores | null>(null)
  const [codigo, setCodigo] = useState('')
  const [senha, setSenha] = useState('')
  const [erro, setErro] = useState('')
  const [ocupado, setOcupado] = useState(false)
  const rodar = async (fn: () => Promise<void>) => {
    setErro('')
    setOcupado(true)
    try { await fn() } catch (err) { setErro((err as Error).message) } finally { setOcupado(false) }
  }

  if (usuario.doisFatores) {
    return (
      <div className="perfil-secao">
        <h4>Verificação em duas etapas: ativa</h4>
        <p className="dica-campo">Ao entrar, o sistema pede também o código do aplicativo autenticador. Perdeu o celular? Peça a um administrador para desligar.</p>
        <label>Para desligar, confirme sua senha<input type="password" autoComplete="current-password" value={senha} onChange={(e) => setSenha(e.target.value)} /></label>
        {erro && <p className="erro">{erro}</p>}
        <button className="perigo" disabled={ocupado || !senha} onClick={() => rodar(async () => { atualizar(await api.post<Usuario>('/me/2fa/desativar', { senha })); setSenha('') })}>Desligar verificação</button>
      </div>
    )
  }
  if (!config) {
    return (
      <div className="perfil-secao">
        <h4>Verificação em duas etapas</h4>
        <p className="dica-campo">Proteja a conta com um código que muda a cada 30 segundos, gerado por um aplicativo autenticador no celular.</p>
        {erro && <p className="erro">{erro}</p>}
        <button disabled={ocupado} onClick={() => rodar(async () => setConfig(await api.post<ConfigDoisFatores>('/me/2fa/iniciar')))}>Configurar</button>
      </div>
    )
  }
  return (
    <div className="perfil-secao">
      <h4>Configurar a verificação</h4>
      <p className="dica-campo">No aplicativo autenticador (Google Authenticator, Microsoft Authenticator, Authy…), escolha adicionar conta por chave e digite esta chave:</p>
      <code className="chave-2fa">{config.segredo}</code>
      <label>Depois, digite aqui o código de 6 dígitos que o aplicativo mostrar
        <input inputMode="numeric" maxLength={7} placeholder="000000" value={codigo} onChange={(e) => setCodigo(e.target.value)} />
      </label>
      {erro && <p className="erro">{erro}</p>}
      <div className="perfil-acoes">
        <button className="primario" disabled={ocupado || codigo.trim().length < 6} onClick={() => rodar(async () => { atualizar(await api.post<Usuario>('/me/2fa/ativar', { codigo })); setConfig(null); setCodigo('') })}>Confirmar e ativar</button>
        <button disabled={ocupado} onClick={() => { setConfig(null); setCodigo(''); setErro('') }}>Cancelar</button>
      </div>
    </div>
  )
}

/** Janela "Meu perfil": ver os dados, trocar ou tirar a foto, alterar a senha e configurar a verificação em duas etapas. */
export default function Perfil({ fechar }: { fechar: () => void }) {
  const { usuario, atualizarUsuario } = useAuth()
  const entrada = useRef<HTMLInputElement>(null)
  const [ocupado, setOcupado] = useState(false)
  const [erro, setErro] = useState('')
  if (!usuario) return null

  const escolher = async (e: ChangeEvent<HTMLInputElement>) => {
    const arquivo = e.target.files?.[0]
    e.target.value = ''
    if (!arquivo) return
    setErro('')
    setOcupado(true)
    try {
      if (!arquivo.type.startsWith('image/')) throw new Error('Escolha um arquivo de imagem')
      const form = new FormData()
      form.append('arquivo', await prepararFoto(arquivo), 'perfil.jpg')
      atualizarUsuario(await api.upload('/me/foto', form))
    } catch (err) {
      setErro((err as Error).message)
    } finally {
      setOcupado(false)
    }
  }

  const tirar = async () => {
    setErro('')
    setOcupado(true)
    try {
      await api.del('/me/foto')
      atualizarUsuario({ ...usuario, fotoVersao: null })
    } catch (err) {
      setErro((err as Error).message)
    } finally {
      setOcupado(false)
    }
  }

  return (
    <div className="fundo" onClick={fechar}>
      <div className="modal perfil-modal" role="dialog" aria-label="Meu perfil" onClick={(e) => e.stopPropagation()}>
        <h3>Meu perfil</h3>
        <div className="perfil-foto">
          <Avatar id={usuario.id} nome={usuario.nome} versao={usuario.fotoVersao} tamanho={112} />
          <div>
            <b>{usuario.nome}</b>
            <span>{ROTULO_PAPEL[usuario.papel]}{usuario.setorNome ? ` · ${usuario.setorNome}` : ''}</span>
          </div>
        </div>
        <input ref={entrada} type="file" accept="image/jpeg,image/png,image/webp" hidden onChange={escolher} />
        <div className="perfil-acoes">
          <button className="primario" disabled={ocupado} onClick={() => entrada.current?.click()}>{usuario.fotoVersao ? 'Trocar foto' : 'Escolher foto'}</button>
          {usuario.fotoVersao && <button disabled={ocupado} onClick={tirar}>Remover foto</button>}
        </div>
        <small className="dica-campo">JPEG, PNG ou WebP. A imagem é recortada em quadrado e reduzida automaticamente.</small>
        {erro && <p className="erro">{erro}</p>}
        {usuario.papel !== 'USUARIO' && <TrocarSenha />}
        <DoisFatores usuario={usuario} atualizar={atualizarUsuario} />
        <div className="acoes"><button onClick={fechar}>Fechar</button></div>
      </div>
    </div>
  )
}
