import { useCallback, useEffect, useState } from 'react'
import { api, type AgenteLocal, type Certificado, type Cliente, type CodigoAgente } from '../api'
import BotaoApagar from '../BotaoApagar'
import { useAuth } from '../auth'
import { fmtData } from '../formato'

const cn = (dn: string | null) => (dn ? dn.split(',')[0].replace(/^CN=/i, '') : '—')
const SELO: Record<string, string> = { VALIDO: 'concluido', VENCENDO: 'aberto', VENCIDO: 'atrasado', SEM_VALIDADE: 'cancelado' }
const ROTULO: Record<string, string> = { VALIDO: 'Válido', VENCENDO: 'Vencendo', VENCIDO: 'Vencido', SEM_VALIDADE: 'Sem validade' }

const quando = (iso: string | null) => (iso ? new Date(iso).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'medium' }) : 'nunca')

/**
 * Certificados lidos dos computadores pelo agente local. Não há envio de .pfx nem de senha: cada usuário instala o agente no
 * próprio computador, e o sistema só recebe os dados públicos dos certificados que o Windows (ou o token) mostra ali.
 */
export default function Certificados() {
  const { podeGerenciar } = useAuth()
  const [agentes, setAgentes] = useState<AgenteLocal[] | null>(null)
  const [certs, setCerts] = useState<Certificado[] | null>(null)
  const [empresas, setEmpresas] = useState<Cliente[]>([])
  const [codigo, setCodigo] = useState<CodigoAgente | null>(null)
  const [erro, setErro] = useState('')
  const [soSemEmpresa, setSoSemEmpresa] = useState(false)

  const carregar = useCallback(async () => {
    try {
      setAgentes(await api.get<AgenteLocal[]>('/agentes'))
      if (podeGerenciar) setCerts(await api.get<Certificado[]>('/certificados'))
      setErro('')
    } catch (e) { setErro((e as Error).message) }
  }, [podeGerenciar])

  useEffect(() => { carregar(); const t = setInterval(carregar, 10000); return () => clearInterval(t) }, [carregar])
  useEffect(() => { if (podeGerenciar) api.get<Cliente[]>('/clientes').then((l) => setEmpresas(l.filter((c) => c.ativo))).catch(() => {}) }, [podeGerenciar])

  const gerar = async () => { try { setCodigo(await api.post<CodigoAgente>('/agentes/codigo')); setErro('') } catch (e) { setErro((e as Error).message) } }
  const agir = async (fn: () => Promise<unknown>) => { try { await fn(); await carregar() } catch (e) { setErro((e as Error).message) } }
  const vencido = codigo ? new Date(codigo.expiraEm).getTime() < Date.now() : false
  const lista = (certs ?? []).filter((c) => !soSemEmpresa || c.clienteId === null)
  const origem = window.location.origin

  return (
    <>
      <div className="barra">
        <h2>Certificados e agente</h2>
        <span className="espaco" />
        <button type="button" className="primario" onClick={gerar}>Ligar este computador</button>
      </div>
      <p className="sub">
        O Viriato <b>não guarda arquivo de certificado nem senha</b>. Um programa pequeno, o <b>agente</b>, é instalado no computador de cada usuário,
        lê os certificados instalados ali (inclusive A3 em token) e faz no próprio computador o que exige a chave, como consultar notas na SEFAZ.
      </p>
      {erro && <p className="erro">{erro}</p>}

      {codigo && (
        <section className="cartao">
          <h3>Código para ligar um computador</h3>
          <p className="codigo-agente" aria-live="polite">{vencido ? 'Código expirado' : codigo.codigo}</p>
          <p className="sub">Vale por 10 minutos e só funciona uma vez. No computador que tem os certificados (com Java 21 ou mais novo), abra o PowerShell na pasta do agente e rode:</p>
          <pre className="comando">{`.\\instalar.ps1 -Servidor ${origem} -Codigo ${vencido ? '<gere outro código>' : codigo.codigo}`}</pre>
          <small>Detalhes e segurança em <code>agente/README.md</code>. O agente inicia sozinho quando a pessoa entra no Windows.</small>
        </section>
      )}

      <section className="cartao">
        <h3>Computadores com agente</h3>
        <div className="tabela-wrap"><table>
          <thead><tr><th>Computador</th><th>Usuário</th><th>Situação</th><th>Último sinal</th><th>Certificados</th><th></th></tr></thead>
          <tbody>
            {agentes?.map((a) => (
              <tr key={a.id}>
                <td>{a.nome}{a.versao ? <small> · v{a.versao}</small> : null}</td>
                <td>{a.usuario ?? '—'}</td>
                <td><span className={`selo ${a.conectado ? 'concluido' : 'atrasado'}`}>{a.conectado ? 'Conectado' : 'Desconectado'}</span></td>
                <td>{quando(a.ultimoContato)}</td>
                <td>{a.certificados}</td>
                <td className="acoes-linha"><BotaoApagar nome={`agente de ${a.nome}`} aviso="O computador deixa de informar certificados e de atender consultas." apagar={() => agir(() => api.del(`/agentes/${a.id}`))} /></td>
              </tr>
            ))}
            {agentes && agentes.length === 0 && <tr><td colSpan={6} className="vazio">Nenhum computador ligado ainda. Clique em “Ligar este computador”.</td></tr>}
          </tbody>
        </table></div>
      </section>

      {podeGerenciar && (
        <section className="cartao">
          <div className="barra">
            <h3>Certificados lidos dos computadores</h3>
            <span className="espaco" />
            <label className="chk"><input type="checkbox" checked={soSemEmpresa} onChange={(e) => setSoSemEmpresa(e.target.checked)} /> Só os sem empresa</label>
          </div>
          <p className="sub">A empresa é reconhecida pelo CNPJ do titular. Os que não têm CNPJ (e-CPF, por exemplo) ou que o sistema não reconheceu podem ser ligados à mão.</p>
          <div className="tabela-wrap"><table>
            <thead><tr><th>Titular</th><th>Empresa</th><th>Validade</th><th>Situação</th><th>Computador</th></tr></thead>
            <tbody>
              {lista.map((c) => (
                <tr key={c.id}>
                  <td className="corta" title={c.numeroSerie ? `${cn(c.titular)} · série ${c.numeroSerie} · ${cn(c.emissora)}` : cn(c.titular)}>{cn(c.titular)}</td>
                  <td>
                    <select aria-label={`Empresa do certificado ${cn(c.titular)}`} value={c.clienteId ?? ''}
                      onChange={(e) => agir(() => api.patch(`/certificados/${c.id}`, { clienteId: e.target.value ? Number(e.target.value) : null }))}>
                      <option value="">— sem empresa —</option>
                      {empresas.map((e) => <option key={e.id} value={e.id}>{e.numero} — {e.nome}</option>)}
                    </select>
                  </td>
                  <td>{fmtData(c.validoAte)}</td>
                  <td><span className={`selo ${SELO[c.situacao]}`}>{ROTULO[c.situacao]}{c.situacao === 'VENCENDO' && c.diasParaVencer !== null ? ` · ${c.diasParaVencer} d` : ''}</span></td>
                  <td className="corta" title={c.computadores.join(', ')}>
                    {c.presente ? c.computadores.join(', ') : <span className="selo cancelado">Fora do computador</span>}
                  </td>
                </tr>
              ))}
              {certs && lista.length === 0 && <tr><td colSpan={5} className="vazio">{soSemEmpresa ? 'Todos os certificados já têm empresa.' : 'Nenhum certificado lido ainda. Ligue um computador com o agente.'}</td></tr>}
            </tbody>
          </table></div>
        </section>
      )}
    </>
  )
}
