import { useEffect, useState, type FormEvent } from 'react'
import { api, ROTULO_PAPEL, type Papel, type PendenciasUsuario, type Setor, type Usuario } from '../api'
import BotaoApagar from '../BotaoApagar'
import { useAuth } from '../auth'

/**
 * Todos veem a equipe (papel e setor). Administrador altera papel, situação e setor e adiciona
 * qualquer papel; gestor adiciona funcionários (só papel Usuário) e ativa/desativa funcionários
 * com papel Usuário, sem alterar papel nem setor. Administrador e gestor (este só os de papel Usuário)
 * podem apagar funcionários (o gestor, só do próprio setor). Se o funcionário tem processos, abre o aviso
 * e, para os em andamento, a escolha de herdeiros e do novo prazo.
 */
export default function Usuarios() {
  const { usuario: eu, ehAdmin, podeGerenciar } = useAuth()
  const [lista, setLista] = useState<Usuario[]>([])
  const [setores, setSetores] = useState<Setor[]>([])
  const [f, setF] = useState({ nome: '', senha: '', papel: 'USUARIO' as Papel, setorId: '' })
  const [erro, setErro] = useState('')

  const carregar = () => api.get<Usuario[]>('/usuarios').then(setLista)
  useEffect(() => {
    carregar()
    api.get<Setor[]>('/setores').then(setSetores)
  }, [])

  const criar = async (e: FormEvent) => {
    e.preventDefault()
    setErro('')
    try {
      // gestor não escolhe setor: o servidor usa o do gestor
      await api.post('/usuarios', { ...f, setorId: ehAdmin && f.setorId ? Number(f.setorId) : null })
      setF({ nome: '', senha: '', papel: 'USUARIO', setorId: '' })
      carregar()
    } catch (err) {
      setErro((err as Error).message)
    }
  }

  const patch = (id: number, data: object) => api.patch(`/usuarios/${id}`, data).then(carregar).catch((e) => setErro(e.message))
  const setoresAtivos = setores.filter((s) => s.ativo)

  const meuSetorId = lista.find((u) => u.id === eu?.id)?.setorId ?? null
  const [apagando, setApagando] = useState<{ u: Usuario; p: PendenciasUsuario } | null>(null)
  const [zerando, setZerando] = useState<Usuario | null>(null)
  const meuSetorNome = lista.find((u) => u.id === eu?.id)?.setorNome ?? null

  // Sem processos: confirmação simples. Com processos: aviso com "Apagar mesmo assim" / "Desistir de apagar".
  const apagar = async (u: Usuario) => {
    setErro('')
    try {
      const p = await api.get<PendenciasUsuario>(`/usuarios/${u.id}/pendencias`)
      if (p.abertos + p.concluidos + p.cancelados === 0) {
        if (!confirm(`Apagar "${u.nome}"? Esta ação não pode ser desfeita.`)) return
        await api.del(`/usuarios/${u.id}`)
        carregar()
      } else {
        setApagando({ u, p })
      }
    } catch (e) {
      setErro((e as Error).message)
    }
  }

  return (
    <>
      <h2>Usuários</h2>
      {!podeGerenciar && <p className="sub">Somente consulta. Quem pode alterar: administrador e gestor.</p>}
      {podeGerenciar && !ehAdmin && (
        <p className="sub">Você pode adicionar funcionários ao seu setor (informando nome, senha inicial e papel) e ativar, desativar ou apagar os de papel Usuário do seu setor. Papel, setor e senha zerada só o administrador altera.</p>
      )}
      {erro && <p className="erro">{erro}</p>}
      <div className="grade usuarios">
        <section className="cartao">
          <table>
            <thead><tr><th>Nome</th><th>Papel</th><th>Setor</th><th>Situação</th>{podeGerenciar && <th></th>}</tr></thead>
            <tbody>
              {lista.map((u) => (
                <tr key={u.id}>
                  <td>{u.nome}</td>
                  <td>
                    {ehAdmin ? (
                      <select value={u.papel} disabled={u.id === eu?.id} onChange={(e) => patch(u.id, { papel: e.target.value })}>
                        {(Object.keys(ROTULO_PAPEL) as Papel[]).map((p) => <option key={p} value={p}>{ROTULO_PAPEL[p]}</option>)}
                      </select>
                    ) : ROTULO_PAPEL[u.papel]}
                  </td>
                  <td>
                    {ehAdmin ? (
                      <select
                        value={u.setorId ?? ''}
                        onChange={(e) => patch(u.id, e.target.value ? { setorId: Number(e.target.value) } : { removerSetor: true })}
                      >
                        <option value="">— sem setor —</option>
                        {setores.filter((s) => s.ativo || s.id === u.setorId).map((s) => <option key={s.id} value={s.id}>{s.nome}</option>)}
                      </select>
                    ) : (u.setorNome ?? '—')}
                  </td>
                  <td>
                    {ehAdmin || (podeGerenciar && u.papel === 'USUARIO') ? (
                      <label className="chk">
                        <input type="checkbox" checked={u.ativo} disabled={u.id === eu?.id} onChange={(e) => patch(u.id, { ativo: e.target.checked })} />
                        {u.ativo ? 'Ativo' : 'Inativo'}
                      </label>
                    ) : <span className={`selo ${u.ativo ? 'aberto' : 'cancelado'}`}>{u.ativo ? 'Ativo' : 'Inativo'}</span>}
                  </td>
                  {podeGerenciar && (
                    <td className="acoes-linha">
                      {ehAdmin && u.id !== eu?.id && (
                        <button type="button" className="perigo-txt" onClick={() => setZerando(u)}>Zerar senha</button>
                      )}
                      {u.id !== eu?.id && (ehAdmin || (u.papel === 'USUARIO' && meuSetorId !== null && u.setorId === meuSetorId)) && (
                        <BotaoApagar nome={u.nome} semConfirmar apagar={() => apagar(u)} />
                      )}
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </section>

        {podeGerenciar && (
          <form className="cartao" onSubmit={criar}>
            <h3>Novo funcionário</h3>
            <label>Nome<input required value={f.nome} onChange={(e) => setF({ ...f, nome: e.target.value })} /></label>
            <label>Senha inicial<input type="password" required minLength={8} autoComplete="new-password" value={f.senha} onChange={(e) => setF({ ...f, senha: e.target.value })} /></label>
            <small>Mínimo de 8 caracteres. No primeiro acesso, a pessoa cria a própria senha.</small>
            <label>Papel
              <select value={f.papel} disabled={!ehAdmin} onChange={(e) => setF({ ...f, papel: e.target.value as Papel })}>
                {(ehAdmin ? (Object.keys(ROTULO_PAPEL) as Papel[]) : (['USUARIO'] as Papel[])).map((p) => <option key={p} value={p}>{ROTULO_PAPEL[p]}</option>)}
              </select>
            </label>
            {ehAdmin ? (
              <label>Setor
                <select value={f.setorId} onChange={(e) => setF({ ...f, setorId: e.target.value })}>
                  <option value="">— sem setor —</option>
                  {setoresAtivos.map((s) => <option key={s.id} value={s.id}>{s.nome}</option>)}
                </select>
              </label>
            ) : (
              <small>{meuSetorNome ? `O funcionário ficará no seu setor (${meuSetorNome}).` : 'Seu usuário não tem setor definido: peça ao administrador antes de adicionar funcionários.'}</small>
            )}
            {!ehAdmin && <small>Gestores adicionam funcionários com papel Usuário.</small>}
            <button className="primario">Adicionar funcionário</button>
          </form>
        )}
      </div>

      {zerando && <ZerarSenha alvo={zerando} fechar={() => setZerando(null)} />}

      {apagando && (
        <ApagarFuncionario
          alvo={apagando.u}
          pend={apagando.p}
          colegas={lista}
          fechar={() => setApagando(null)}
          apagado={() => { setApagando(null); carregar() }}
        />
      )}
    </>
  )
}

/** O administrador zera a senha de outra conta e já informa a nova (provisória): a pessoa a troca no próximo acesso. */
function ZerarSenha({ alvo, fechar }: { alvo: Usuario; fechar: () => void }) {
  const [senha, setSenha] = useState('')
  const [erro, setErro] = useState('')
  const [enviando, setEnviando] = useState(false)
  const [feito, setFeito] = useState(false)

  const enviar = async (e: FormEvent) => {
    e.preventDefault()
    setErro('')
    setEnviando(true)
    try {
      await api.put(`/usuarios/${alvo.id}/senha`, { senha })
      setFeito(true)
    } catch (err) {
      setErro((err as Error).message)
    } finally {
      setEnviando(false)
    }
  }

  return (
    <div className="fundo" onClick={fechar}>
      <form className="modal" onClick={(e) => e.stopPropagation()} onSubmit={enviar}>
        <h3>Zerar a senha de {alvo.nome}</h3>
        {feito ? (
          <>
            <p className="sub">Senha zerada. Informe a nova senha a {alvo.nome}: no próximo acesso será pedido que crie uma senha própria.</p>
            <div className="acoes"><button type="button" className="primario" onClick={fechar}>Concluir</button></div>
          </>
        ) : (
          <>
            <p className="sub">A senha atual deixa de valer e a sessão aberta é encerrada. Crie agora a nova senha; {alvo.nome} a trocará por uma própria ao entrar.</p>
            <label>Nova senha
              <input required minLength={8} maxLength={72} autoFocus autoComplete="off" value={senha} onChange={(e) => setSenha(e.target.value)} />
            </label>
            <small>Mínimo de 8 caracteres. Anote para informar à pessoa.</small>
            {erro && <p className="erro">{erro}</p>}
            <div className="acoes">
              <button type="button" onClick={fechar}>Cancelar</button>
              <button className="perigo" disabled={enviando || senha.length < 8}>Zerar senha</button>
            </div>
          </>
        )}
      </form>
    </div>
  )
}

const hojeISO = () => new Date().toLocaleDateString('sv-SE')   // aaaa-mm-dd no horário local

/**
 * Passo 1: aviso de que o funcionário tem processos. Passo 2 (só se há processos em andamento): escolher quem
 * herda e o novo prazo. Concluídos e cancelados ficam com o nome dele, visíveis só ao gestor do setor e ao administrador.
 */
function ApagarFuncionario({ alvo, pend, colegas, fechar, apagado }: {
  alvo: Usuario; pend: PendenciasUsuario; colegas: Usuario[]; fechar: () => void; apagado: () => void
}) {
  const [passo, setPasso] = useState<'aviso' | 'herdeiros'>('aviso')
  const [herdeiros, setHerdeiros] = useState<number[]>([])
  const [prazo, setPrazo] = useState('')
  const [erro, setErro] = useState('')
  const [enviando, setEnviando] = useState(false)
  const encerrados = pend.concluidos + pend.cancelados
  // Herdeiros possíveis: ativos, do setor dele (o administrador pode herdar de qualquer setor).
  const candidatos = colegas.filter((c) => c.id !== alvo.id && c.ativo && (c.papel === 'ADMIN' || (alvo.setorId !== null && c.setorId === alvo.setorId)))

  const apagarAgora = async (corpo?: object) => {
    setErro('')
    setEnviando(true)
    try {
      await api.post(`/usuarios/${alvo.id}/apagar`, corpo ?? {})
      apagado()
    } catch (e) {
      setErro((e as Error).message)
      setEnviando(false)
    }
  }
  const continuar = () => (pend.abertos > 0 ? setPasso('herdeiros') : apagarAgora())
  const alternar = (id: number) => setHerdeiros((h) => (h.includes(id) ? h.filter((x) => x !== id) : [...h, id]))
  const enviar = (e: FormEvent) => { e.preventDefault(); apagarAgora({ herdeiroIds: herdeiros, prazo }) }
  const plural = (n: number, um: string, varios: string) => `${n} ${n === 1 ? um : varios}`

  if (passo === 'aviso') {
    return (
      <div className="fundo" onClick={fechar}>
        <div className="modal" onClick={(e) => e.stopPropagation()} role="alertdialog" aria-label="Funcionário com processos">
          <h3>{alvo.nome} possui processos</h3>
          <ul className="lista-aviso">
            {pend.abertos > 0 && <li><strong>{plural(pend.abertos, 'processo em andamento', 'processos em andamento')}</strong>{pend.atrasados > 0 && <> ({plural(pend.atrasados, 'atrasado', 'atrasados')})</>}</li>}
            {pend.concluidos > 0 && <li>{plural(pend.concluidos, 'processo concluído', 'processos concluídos')}</li>}
            {pend.cancelados > 0 && <li>{plural(pend.cancelados, 'processo cancelado', 'processos cancelados')}</li>}
          </ul>
          {pend.abertos > 0 && <p className="sub">Os processos em andamento precisarão ser passados para outros funcionários, com um novo prazo.</p>}
          {encerrados > 0 && <p className="sub">Os processos encerrados continuam existindo com o nome dele, e só o gestor do setor e o administrador terão acesso a eles.</p>}
          {erro && <p className="erro">{erro}</p>}
          <div className="acoes">
            <button type="button" onClick={fechar}>Desistir de apagar</button>
            <button type="button" className="perigo" disabled={enviando} onClick={continuar}>Apagar mesmo assim</button>
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="fundo" onClick={fechar}>
      <form className="modal" onClick={(e) => e.stopPropagation()} onSubmit={enviar}>
        <h3>Quem herda os processos de {alvo.nome}?</h3>
        <p className="sub">
          {plural(pend.abertos, 'processo em andamento será passado', 'processos em andamento serão passados')}
          {pend.atrasados > 0 && <> (inclusive {plural(pend.atrasados, 'atrasado', 'atrasados')})</>}.
          Com mais de um escolhido, a divisão é igual.
        </p>
        <fieldset className="caixas">
          <legend>Funcionários que herdam</legend>
          {candidatos.length === 0 && <small className="aviso-campo">Não há outro funcionário ativo neste setor. Cadastre ou ative alguém antes de apagar.</small>}
          {candidatos.map((c) => (
            <label key={c.id} className="chk">
              <input type="checkbox" checked={herdeiros.includes(c.id)} onChange={() => alternar(c.id)} />
              {c.nome} <small>— {ROTULO_PAPEL[c.papel]}</small>
            </label>
          ))}
        </fieldset>
        <label>Novo prazo dos processos
          <input type="date" required min={hojeISO()} value={prazo} onChange={(e) => setPrazo(e.target.value)} />
        </label>
        {erro && <p className="erro">{erro}</p>}
        <div className="acoes">
          <button type="button" onClick={fechar}>Desistir de apagar</button>
          <button className="perigo" disabled={enviando || herdeiros.length === 0 || !prazo}>Apagar mesmo assim</button>
        </div>
      </form>
    </div>
  )
}
