import { useEffect, useState, type FormEvent } from 'react'
import { api, ROTULO_PAPEL, type Papel, type Setor, type Usuario } from '../api'
import { useAuth } from '../auth'

/**
 * Todos veem a equipe (papel e setor). Administrador altera papel, situação e setor e adiciona
 * qualquer papel; gestor adiciona funcionários (só papel Usuário) e ativa/desativa funcionários
 * com papel Usuário, sem alterar papel nem setor. Administrador e gestor (este só os de papel Usuário)
 * podem apagar funcionários.
 */
export default function Usuarios() {
  const { usuario: eu, ehAdmin, podeGerenciar } = useAuth()
  const [lista, setLista] = useState<Usuario[]>([])
  const [setores, setSetores] = useState<Setor[]>([])
  const [f, setF] = useState({ nome: '', email: '', senha: '', papel: 'USUARIO' as Papel, setorId: '' })
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
      await api.post('/usuarios', { ...f, setorId: f.setorId ? Number(f.setorId) : null })
      setF({ nome: '', email: '', senha: '', papel: 'USUARIO', setorId: '' })
      carregar()
    } catch (err) {
      setErro((err as Error).message)
    }
  }

  const patch = (id: number, data: object) => api.patch(`/usuarios/${id}`, data).then(carregar).catch((e) => setErro(e.message))
  const setoresAtivos = setores.filter((s) => s.ativo)

  const apagar = (u: Usuario) => {
    if (!confirm(`Apagar o funcionário "${u.nome}"? Esta ação não pode ser desfeita.\n\nO histórico dos processos em que ele atuou é mantido.`)) return
    setErro('')
    api.del(`/usuarios/${u.id}`).then(carregar).catch((e) => setErro(e.message))
  }

  return (
    <>
      <h2>Usuários</h2>
      {!podeGerenciar && <p className="sub">Somente consulta. Quem pode alterar: administrador e gestor.</p>}
      {podeGerenciar && !ehAdmin && (
        <p className="sub">Você pode adicionar funcionários e ativar, desativar ou apagar os de papel Usuário. Papel e setor só o administrador altera.</p>
      )}
      {erro && <p className="erro">{erro}</p>}
      <div className="grade">
        <section className="cartao">
          <table>
            <thead><tr><th>Nome</th><th>Papel</th><th>Setor</th><th>Situação</th>{podeGerenciar && <th></th>}</tr></thead>
            <tbody>
              {lista.map((u) => (
                <tr key={u.id}>
                  <td>{u.nome}<small className="bloco">{u.email}</small></td>
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
                    <td>
                      {u.id !== eu?.id && (ehAdmin || u.papel === 'USUARIO') && (
                        <button className="link perigo-txt" onClick={() => apagar(u)}>apagar</button>
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
            <label>E-mail<input type="email" required value={f.email} onChange={(e) => setF({ ...f, email: e.target.value })} /></label>
            <label>Senha inicial<input type="password" required minLength={8} value={f.senha} onChange={(e) => setF({ ...f, senha: e.target.value })} /></label>
            <label>Papel
              <select value={f.papel} disabled={!ehAdmin} onChange={(e) => setF({ ...f, papel: e.target.value as Papel })}>
                {(ehAdmin ? (Object.keys(ROTULO_PAPEL) as Papel[]) : (['USUARIO'] as Papel[])).map((p) => <option key={p} value={p}>{ROTULO_PAPEL[p]}</option>)}
              </select>
            </label>
            <label>Setor
              <select value={f.setorId} onChange={(e) => setF({ ...f, setorId: e.target.value })}>
                <option value="">— sem setor —</option>
                {setoresAtivos.map((s) => <option key={s.id} value={s.id}>{s.nome}</option>)}
              </select>
            </label>
            {!ehAdmin && <small>Gestores adicionam funcionários com papel Usuário.</small>}
            <button className="primario">Adicionar funcionário</button>
          </form>
        )}
      </div>
    </>
  )
}
