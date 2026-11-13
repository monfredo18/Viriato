import { useRef, useState, type ChangeEvent } from 'react'
import { api, ROTULO_PAPEL } from './api'
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

/** Janela "Meu perfil": ver os dados e trocar ou tirar a foto. */
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
        <div className="acoes"><button onClick={fechar}>Fechar</button></div>
      </div>
    </div>
  )
}
