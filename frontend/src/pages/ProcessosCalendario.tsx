import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, type Feriado, type Pagina, type Processo } from '../api'

const MESES = ['Janeiro', 'Fevereiro', 'Março', 'Abril', 'Maio', 'Junho', 'Julho', 'Agosto', 'Setembro', 'Outubro', 'Novembro', 'Dezembro']
const SEMANA = ['Dom', 'Seg', 'Ter', 'Qua', 'Qui', 'Sex', 'Sáb']
const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`

/** Domingo de Páscoa (Meeus/Jones/Butcher): a Sexta-feira Santa é feriado nacional. */
function pascoa(ano: number) {
  const a = ano % 19, b = Math.floor(ano / 100), c = ano % 100, d = Math.floor(b / 4), e = b % 4, f = Math.floor((b + 8) / 25), g = Math.floor((b - f + 1) / 3)
  const h = (19 * a + b - d - g + 15) % 30, i = Math.floor(c / 4), k = c % 4, l = (32 + 2 * e + 2 * i - h - k) % 7, m = Math.floor((a + 11 * h + 22 * l) / 451)
  const mes = Math.floor((h + l - 7 * m + 114) / 31), dia = ((h + l - 7 * m + 114) % 31) + 1
  return new Date(ano, mes - 1, dia)
}

/** Calendário fiscal: os vencimentos (prazos) dos processos em andamento no mês, com a cor da situação e os dias sem expediente. */
export default function ProcessosCalendario() {
  const hoje = new Date()
  const [ref, setRef] = useState(new Date(hoje.getFullYear(), hoje.getMonth(), 1))
  const [processos, setProcessos] = useState<Processo[]>([])
  const [total, setTotal] = useState(0)
  const [feriados, setFeriados] = useState<Feriado[]>([])
  const [erro, setErro] = useState('')

  useEffect(() => { api.get<Feriado[]>('/feriados').then(setFeriados).catch(() => {}) }, [])
  useEffect(() => {
    const ultimo = new Date(ref.getFullYear(), ref.getMonth() + 1, 0)
    const q = new URLSearchParams({ status: 'ABERTO', prazoDe: iso(ref), prazoAte: iso(ultimo), tamanho: '100' })
    api.get<Pagina<Processo>>(`/processos?${q}`).then((r) => { setProcessos(r.content); setTotal(r.totalElements); setErro('') }).catch((e) => setErro(e.message))
  }, [ref])

  const semExpediente = useMemo(() => {
    const m = new Map<string, string>()
    const ano = ref.getFullYear()
    const sexta = pascoa(ano); sexta.setDate(sexta.getDate() - 2)
    m.set(iso(sexta), 'Sexta-feira Santa')
    for (const f of feriados) {
      if (!f.ativo || f.escopo !== 'NACIONAL') continue   // estaduais e municipais dependem da empresa atendida: o prazo do processo já os considera
      if (f.data && f.data.startsWith(String(ano))) m.set(f.data, f.descricao)
      else if (f.dia && f.mes) m.set(iso(new Date(ano, f.mes - 1, f.dia)), f.descricao)
    }
    return m
  }, [feriados, ref])

  const porDia = useMemo(() => {
    const m = new Map<string, Processo[]>()
    for (const p of processos) if (p.prazoEtapa) m.set(p.prazoEtapa, [...(m.get(p.prazoEtapa) ?? []), p])
    return m
  }, [processos])

  const primeiro = ref.getDay()
  const dias = new Date(ref.getFullYear(), ref.getMonth() + 1, 0).getDate()
  const celulas: (number | null)[] = [...Array(primeiro).fill(null), ...Array.from({ length: dias }, (_, i) => i + 1)]
  while (celulas.length % 7) celulas.push(null)
  const limite = new Date(hoje.getFullYear(), hoje.getMonth(), hoje.getDate() + 3)

  const cor = (p: Processo) => (p.atrasado ? 'atrasado' : p.prazoEtapa && new Date(p.prazoEtapa + 'T00:00:00') <= limite ? 'proximo' : 'aberto')

  return (
    <>
      <div className="filtros">
        <button type="button" onClick={() => setRef(new Date(ref.getFullYear(), ref.getMonth() - 1, 1))}>‹</button>
        <b className="cal-titulo">{MESES[ref.getMonth()]} de {ref.getFullYear()}</b>
        <button type="button" onClick={() => setRef(new Date(ref.getFullYear(), ref.getMonth() + 1, 1))}>›</button>
        <button type="button" onClick={() => setRef(new Date(hoje.getFullYear(), hoje.getMonth(), 1))}>Hoje</button>
        <span className="legenda"><span><i className="seg-azul" /> no prazo</span><span><i className="seg-ouro" /> vence em até 3 dias</span><span><i className="seg-vermelho" /> atrasado</span></span>
      </div>
      {erro && <p className="erro">{erro}</p>}
      <div className="calendario" role="grid">
        {SEMANA.map((d) => <div key={d} className="cal-cab" role="columnheader">{d}</div>)}
        {celulas.map((dia, i) => {
          if (dia === null) return <div key={i} className="cal-dia vazio-cel" />
          const data = new Date(ref.getFullYear(), ref.getMonth(), dia)
          const chave = iso(data)
          const lista = porDia.get(chave) ?? []
          const fds = data.getDay() === 0 || data.getDay() === 6
          const feriado = semExpediente.get(chave)
          return (
            <div key={i} className={`cal-dia${fds || feriado ? ' sem-expediente' : ''}${chave === iso(hoje) ? ' hoje' : ''}`} role="gridcell" title={feriado}>
              <span className="cal-num">{dia}</span>
              {feriado && <small className="cal-feriado">{feriado}</small>}
              {lista.slice(0, 3).map((p) => <Link key={p.id} to={`/processos/${p.id}`} className={`cal-chip ${cor(p)}`} title={`${p.titulo} — ${p.clienteNome ?? ''}`}>{p.titulo}</Link>)}
              {lista.length > 3 && <small className="sub">+{lista.length - 3} mais</small>}
            </div>
          )
        })}
      </div>
      {total > processos.length && <p className="sub">Mostrando {processos.length} de {total} processos do mês: refine pela lista para ver os demais.</p>}
      <p className="sub">Aparecem os processos em andamento com prazo no mês. Ao concluir, o processo deixa de ter prazo e sai do calendário. Dias sombreados não têm expediente.</p>
    </>
  )
}
