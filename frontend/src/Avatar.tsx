import { useEffect, useState } from 'react'
import { api } from './api'

const iniciais = (nome?: string) =>
  (nome ?? '?').split(' ').filter(Boolean).slice(0, 2).map((p) => p[0]).join('').toUpperCase()

/** Fotos já baixadas, por pessoa e versão: trocar a foto muda a versão e busca de novo; o resto vem da memória. */
const fotos = new Map<string, Promise<string>>()
function foto(id: number, versao: number): Promise<string> {
  const chave = `${id}:${versao}`
  let p = fotos.get(chave)
  if (!p) {
    p = api.imagem(`/usuarios/${id}/foto`)
    p.catch(() => fotos.delete(chave))
    fotos.set(chave, p)
  }
  return p
}

interface Props {
  id: number | undefined
  nome: string | undefined
  /** Versão da foto; vazio = a pessoa não tem foto e aparecem as iniciais. */
  versao: number | null | undefined
  tamanho?: number
  className?: string
}

/** Foto de perfil redonda; sem foto (ou enquanto carrega), as iniciais. */
export default function Avatar({ id, nome, versao, tamanho = 40, className = '' }: Props) {
  const [url, setUrl] = useState<string | null>(null)
  useEffect(() => {
    setUrl(null)
    if (!id || !versao) return
    let ativo = true
    foto(id, versao).then((u) => ativo && setUrl(u)).catch(() => {})
    return () => { ativo = false }
  }, [id, versao])

  const estilo = { width: tamanho, height: tamanho, fontSize: Math.max(11, Math.round(tamanho * 0.34)) }
  return url
    ? <img className={`avatar ${className}`} style={estilo} src={url} alt="" />
    : <span className={`avatar ${className}`} style={estilo} aria-hidden="true">{iniciais(nome)}</span>
}
