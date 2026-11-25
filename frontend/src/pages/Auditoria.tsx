import { useEffect, useState } from 'react'
import { api, type RegistroAuditoria } from '../api'

const ROTULO: Record<string, string> = {
  LOGIN: 'Entrou no sistema',
  LOGIN_FALHA: 'Tentativa de entrada recusada',
  LOGIN_RECUSADO: 'Entrada recusada (conta bloqueada)',
  CONTA_BLOQUEADA: 'Conta bloqueada',
  CONTA_DESBLOQUEADA: 'Conta desbloqueada',
  SENHA_ZERADA: 'Senha zerada',
  SENHA_ALTERADA: 'Senha alterada',
  USUARIO_CRIADO: 'Usuário criado',
  USUARIO_ALTERADO: 'Usuário alterado',
  USUARIO_APAGADO: 'Usuário apagado',
  '2FA_ATIVADA': 'Verificação em duas etapas ativada',
  '2FA_DESATIVADA': 'Verificação em duas etapas desativada',
  '2FA_REMOVIDA_PELO_ADMIN': 'Duas etapas removida pelo administrador',
  FOTO_REMOVIDA_PELO_ADMIN: 'Foto removida pelo administrador',
  AGENTE_PAREADO: 'Agente de certificados instalado',
  AGENTE_REVOGADO: 'Agente de certificados desligado',
  CERTIFICADOS_DETECTADOS: 'Certificados lidos de um computador',
  CERTIFICADO_VINCULADO: 'Certificado ligado a uma empresa',
  CERTIFICADO_USADO: 'Certificado usado pelo sistema',
}
const ALERTA = new Set(['AGENTE_REVOGADO', 'LOGIN_FALHA', 'LOGIN_RECUSADO', 'CONTA_BLOQUEADA', 'SENHA_ZERADA', 'USUARIO_APAGADO', '2FA_REMOVIDA_PELO_ADMIN'])

/** Registro de segurança e de administração da empresa (só o administrador vê; os 300 mais recentes). */
export default function Auditoria() {
  const [lista, setLista] = useState<RegistroAuditoria[] | null>(null)
  const [erro, setErro] = useState('')
  const carregar = () => api.get<RegistroAuditoria[]>('/auditoria').then((l) => { setLista(l); setErro('') }).catch((e) => setErro(e.message))
  useEffect(() => { carregar() }, [])

  return (
    <>
      <div className="barra">
        <h2>Auditoria</h2>
        <span className="espaco" />
        <button type="button" onClick={carregar}>Atualizar</button>
      </div>
      <p className="sub">Entradas no sistema, bloqueios, trocas de senha e mudanças de usuários. Mostra os 300 registros mais recentes.</p>
      {erro && <p className="erro">{erro}</p>}
      <div className="tabela-wrap"><table>
        <thead><tr><th>Quando</th><th>Quem</th><th>O que aconteceu</th><th>Detalhe</th><th>Origem</th></tr></thead>
        <tbody>
          {lista?.map((r) => (
            <tr key={r.id}>
              <td>{new Date(r.criadoEm).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' })}</td>
              <td>{r.usuario ?? '—'}</td>
              <td><span className={`selo ${ALERTA.has(r.acao) ? 'atrasado' : 'aberto'}`}>{ROTULO[r.acao] ?? r.acao}</span></td>
              <td className="detalhe-auditoria">{r.detalhe ?? ''}</td>
              <td className="detalhe-auditoria" title={r.userAgent ?? undefined}>{r.ip ?? '—'}</td>
            </tr>
          ))}
          {lista && lista.length === 0 && <tr><td colSpan={5} className="vazio">Nenhum registro ainda.</td></tr>}
        </tbody>
      </table></div>
    </>
  )
}
