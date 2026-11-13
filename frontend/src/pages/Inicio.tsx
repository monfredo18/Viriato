import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api, type Pagina, type Painel as PainelDados, type Processo } from '../api'
import { useAuth } from '../auth'
import Icone, { type NomeIcone } from '../Icones'
import { BarraCarga } from './Painel'
import { NovoProcesso, Selo } from './Processos'

const dec = (v: number) => v.toLocaleString('pt-BR', { minimumFractionDigits: 1, maximumFractionDigits: 1 })
const fmtData = (d: string | null) => (d ? new Date(d + 'T00:00:00').toLocaleDateString('pt-BR') : '—')

/** Diferença em dias entre o prazo (data sem hora) e hoje: negativa = atrasado. */
function diasAte(prazo: string): number {
  const hoje = new Date(); hoje.setHours(0, 0, 0, 0)
  return Math.round((new Date(prazo + 'T00:00:00').getTime() - hoje.getTime()) / 86400000)
}
const quando = (d: number) => (d < 0 ? `atrasado há ${-d} ${d === -1 ? 'dia' : 'dias'}` : d === 0 ? 'vence hoje' : d === 1 ? 'vence amanhã' : `em ${d} dias`)

function Indicador({ icone, cor, valor, rotulo, nota }: { icone: NomeIcone; cor: 'azul' | 'vermelho' | 'ouro' | 'verde'; valor: string; rotulo: string; nota?: string }) {
  return (
    <div className={`indicador ind-${cor}`}>
      <span className="indicador-icone"><Icone nome={icone} tamanho={24} /></span>
      <div><b>{valor}</b><span>{rotulo}</span>{nota && <small>{nota}</small>}</div>
    </div>
  )
}

/** Tela inicial: resumo do que importa agora, processos recentes, prazos mais próximos e atalhos. */
export default function Inicio() {
  const { usuario, ehAdmin } = useAuth()
  const nav = useNavigate()
  const [painel, setPainel] = useState<PainelDados | null>(null)
  const [abertos, setAbertos] = useState<Processo[] | null>(null)
  const [novo, setNovo] = useState(false)
  const [erro, setErro] = useState('')

  const carregar = () => {
    api.get<PainelDados>('/indicadores?dias=30').then(setPainel).catch((e) => setErro(e.message))
    api.get<Pagina<Processo>>('/processos?status=ABERTO&tamanho=50').then((r) => setAbertos(r.content)).catch((e) => setErro(e.message))
  }
  useEffect(carregar, [])

  const primeiroNome = (usuario?.nome ?? '').split(' ')[0]
  const hoje = new Date().toLocaleDateString('pt-BR', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })
  const r = painel?.resumo
  const recentes = (abertos ?? []).slice(0, 6)
  const prazos = (abertos ?? []).filter((p) => p.prazoEtapa).sort((a, b) => a.prazoEtapa!.localeCompare(b.prazoEtapa!)).slice(0, 5)

  const carga = painel
    ? (painel.todosOsSetores
        ? painel.setores.map((s) => ({ chave: `s${s.setorId}`, nome: s.setor, abertos: s.abertos, atrasados: s.atrasados }))
        : painel.funcionarios.map((f) => ({ chave: `f${f.usuarioId}`, nome: f.nome, abertos: f.abertos, atrasados: f.atrasados })))
        .filter((l) => l.abertos > 0).sort((a, b) => b.abertos - a.abertos).slice(0, 6)
    : []
  const maximo = Math.max(0, ...carga.map((l) => l.abertos))

  return (
    <>
      <div className="boas-vindas">
        <div>
          <h2>Bem-vindo, <em>{primeiroNome}</em>!</h2>
          <p>Veja como está o andamento dos processos{painel ? ` — ${painel.escopo.toLowerCase()}` : ''}.</p>
        </div>
        <span className="data-hoje"><Icone nome="calendario" tamanho={17} />{hoje}</span>
      </div>
      {erro && <p className="erro">{erro}</p>}

      <div className="indicadores">
        <Indicador icone="processos" cor="azul" valor={r ? String(r.abertos) : '—'} rotulo="Em andamento" />
        <Indicador icone="alerta" cor="vermelho" valor={r ? String(r.atrasados) : '—'} rotulo="Atrasados"
          nota={r && r.abertos > 0 ? `${Math.round((r.atrasados / r.abertos) * 100)}% dos em andamento` : undefined} />
        <Indicador icone="check" cor="verde" valor={r ? String(r.concluidos) : '—'} rotulo="Concluídos" nota="nos últimos 30 dias" />
        <Indicador icone="ampulheta" cor="ouro" valor={r ? (r.tempoMedioConclusaoDias === null ? '—' : `${dec(r.tempoMedioConclusaoDias)} d`) : '—'} rotulo="Tempo médio" nota="até concluir, nos últimos 30 dias" />
      </div>

      <div className="grade-inicio">
        <section className="cartao">
          <h3>{painel?.todosOsSetores ? 'Carga por setor' : 'Carga por funcionário'}</h3>
          {carga.length === 0 ? <p className="vazio">Nenhum processo em andamento.</p> : (
            <>
              <div className="legenda"><span><i className="seg-azul" /> no prazo</span><span><i className="seg-vermelho" /> atrasados</span></div>
              <ul className="carga-lista">
                {carga.map((l) => (
                  <li key={l.chave}>
                    <span className="carga-nome">{l.nome}</span>
                    <BarraCarga abertos={l.abertos} atrasados={l.atrasados} maximo={maximo} rotulo={`${l.nome}: ${l.abertos} em andamento, ${l.atrasados} atrasados`} />
                    <span className="carga-num">{l.abertos}{l.atrasados > 0 && <small className="texto-atraso"> · {l.atrasados} atras.</small>}</span>
                  </li>
                ))}
              </ul>
            </>
          )}
          <p className="cartao-rodape"><Link to="/painel">Ver painel completo →</Link></p>
        </section>

        <section className="cartao atalhos">
          <button className="primario grande" onClick={() => setNovo(true)}><Icone nome="mais" tamanho={18} />Novo processo</button>
          <Link to="/processos"><Icone nome="busca" />Consultar processos</Link>
          <Link to="/processos?meus=1"><Icone nome="tarefas" />Minhas tarefas</Link>
          <Link to="/relatorios"><Icone nome="relatorios" />Gerar relatório</Link>
          {ehAdmin && <Link to="/cadastros"><Icone nome="cadastros" />Cadastros</Link>}
        </section>
      </div>

      <div className="grade-inicio">
        <section className="cartao">
          <h3>Processos recentes</h3>
          <div className="tabela-wrap"><table>
            <thead><tr><th>Número</th><th>Empresa</th><th>Responsável</th><th>Prazo</th><th>Situação</th></tr></thead>
            <tbody>
              {recentes.map((p) => (
                <tr key={p.id} className={p.atrasado ? 'linha-atrasada' : undefined}>
                  <td><Link to={`/processos/${p.id}`}>{p.numero}</Link></td>
                  <td>{p.clienteNome ?? '—'}</td>
                  <td>{p.responsavelNome ?? '—'}</td>
                  <td>{fmtData(p.prazoEtapa)}</td>
                  <td><Selo p={p} /></td>
                </tr>
              ))}
              {abertos && recentes.length === 0 && <tr><td colSpan={5} className="vazio">Nenhum processo em andamento.</td></tr>}
            </tbody>
          </table></div>
          <p className="cartao-rodape"><Link to="/processos">Ver todos →</Link></p>
        </section>

        <section className="cartao">
          <h3>Prazos próximos</h3>
          {abertos && prazos.length === 0 && <p className="vazio">Nenhum prazo em aberto.</p>}
          <ul className="prazos">
            {prazos.map((p) => {
              const d = diasAte(p.prazoEtapa!)
              return (
                <li key={p.id} className={d < 0 ? 'p-atrasado' : d <= 2 ? 'p-perto' : 'p-folga'}>
                  <Link to={`/processos/${p.id}`}>
                    <b>{p.clienteNome ?? p.titulo}</b>
                    <span>{p.entregaNome ?? p.titulo}{p.etapaAtual ? ` · ${p.etapaAtual}` : ''}</span>
                    <small>{fmtData(p.prazoEtapa)} — {quando(d)}</small>
                  </Link>
                </li>
              )
            })}
          </ul>
          <p className="cartao-rodape"><Link to="/processos?atrasados=1">Ver atrasados →</Link></p>
        </section>
      </div>

      {novo && <NovoProcesso fechar={() => setNovo(false)} criado={() => { setNovo(false); nav('/processos') }} />}
    </>
  )
}
