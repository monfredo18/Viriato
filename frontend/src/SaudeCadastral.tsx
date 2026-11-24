import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, type Saude } from './api'
import { useAuth } from './auth'
import { fmtData } from './formato'

/** Painel "Saúde cadastral e certificados": vencimentos próximos e pendências de cadastro (administrador e gestor). */
export default function SaudeCadastral({ limite = 6 }: { limite?: number }) {
  const { podeGerenciar } = useAuth()
  const [s, setS] = useState<Saude | null>(null)
  useEffect(() => { if (podeGerenciar) api.get<Saude>('/cadastros/saude').then(setS).catch(() => {}) }, [podeGerenciar])
  if (!podeGerenciar || !s) return null
  const r = s.resumo
  const critico = r.certificadosVencidos + r.procuracoesVencidas
  const aVencer = r.certificadosAVencer + r.procuracoesAVencer
  const pendencias = r.empresasSemCnpj + r.empresasSemRegime + r.empresasSemCertificado
  const tudoCerto = critico + aVencer + pendencias === 0

  return (
    <section className="cartao saude">
      <h3>Saúde cadastral e certificados</h3>
      <div className="saude-numeros">
        <div className={critico ? 'ruim' : ''}><b>{critico}</b><span>vencidos</span></div>
        <div className={aVencer ? 'atencao' : ''}><b>{aVencer}</b><span>vencem em até 30 dias</span></div>
        <div className={r.empresasSemCertificado ? 'atencao' : ''}><b>{r.empresasSemCertificado}</b><span>empresas sem certificado válido</span></div>
        <div className={r.empresasSemCnpj + r.empresasSemRegime ? 'atencao' : ''}><b>{r.empresasSemCnpj + r.empresasSemRegime}</b><span>cadastros sem CNPJ ou regime</span></div>
      </div>
      {tudoCerto ? <p className="vazio">Tudo em dia: nenhum certificado ou procuração perto de vencer.</p> : (
        s.itens.length > 0 && (
          <ul className="saude-itens">
            {s.itens.slice(0, limite).map((i) => (
              <li key={`${i.tipo}${i.itemId}`}>
                <span className={`selo ${i.dias < 0 ? 'atrasado' : 'aberto'}`}>{i.dias < 0 ? 'Vencido' : i.dias === 0 ? 'Vence hoje' : `${i.dias} d`}</span>
                <Link to={`/cadastros/empresas/${i.empresaId}`}><b>{i.empresaNome}</b></Link>
                <span className="saude-desc">{i.descricao} · {fmtData(i.vencimento)}</span>
              </li>
            ))}
          </ul>
        )
      )}
      {r.onboardingEmAndamento + r.offboardingEmAndamento > 0 && (
        <p className="sub">{r.onboardingEmAndamento > 0 && `${r.onboardingEmAndamento} em onboarding`}{r.onboardingEmAndamento > 0 && r.offboardingEmAndamento > 0 && ' · '}{r.offboardingEmAndamento > 0 && `${r.offboardingEmAndamento} em offboarding`}</p>
      )}
    </section>
  )
}
