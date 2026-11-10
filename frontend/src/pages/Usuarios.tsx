import { useEffect, useState, type FormEvent } from 'react'
import { api, type Papel, type Usuario } from '../api'
import { useAuth } from '../auth'

export default function Usuarios() {
  const { usuario: eu } = useAuth()
  const [lista, setLista] = useState<Usuario[]>([])
  const [f, setF] = useState({ nome: '', email: '', senha: '', papel: 'USUARIO' as Papel })
  const [erro, setErro] = useState('')

  const carregar = () => api.get<Usuario[]>('/usuarios').then(setLista)
  useEffect(() => { carregar() }, [])

  const criar = async (e: FormEvent) => {
    e.preventDefault()
    setErro('')
    try {
      await api.post('/usuarios', f)
      setF({ nome: '', email: '', senha: '', papel: 'USUARIO' })
      carregar()
    } catch (err) {
      setErro((err as Error).message)
    }
  }

  const patch = (id: number, data: object) => api.patch(`/usuarios/${id}`, data).then(carregar).catch((e) => setErro(e.message))

  return (
    <>
      <h2>Usuários</h2>
      {erro && <p className="erro">{erro}</p>}
      <div className="grade">
        <section className="cartao">
          <table>
            <thead><tr><th>Nome</th><th>E-mail</th><th>Papel</th><th>Ativo</th></tr></thead>
            <tbody>
              {lista.map((u) => (
                <tr key={u.id}>
                  <td>{u.nome}</td>
                  <td>{u.email}</td>
                  <td>
                    <select value={u.papel} disabled={u.id === eu?.id} onChange={(e) => patch(u.id, { papel: e.target.value })}>
                      <option value="ADMIN">Admin</option><option value="GESTOR">Gestor</option><option value="USUARIO">Usuário</option>
                    </select>
                  </td>
                  <td><input type="checkbox" checked={u.ativo} disabled={u.id === eu?.id} onChange={(e) => patch(u.id, { ativo: e.target.checked })} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
        <form className="cartao" onSubmit={criar}>
          <h3>Novo usuário</h3>
          <label>Nome<input required value={f.nome} onChange={(e) => setF({ ...f, nome: e.target.value })} /></label>
          <label>E-mail<input type="email" required value={f.email} onChange={(e) => setF({ ...f, email: e.target.value })} /></label>
          <label>Senha inicial<input type="password" required minLength={8} value={f.senha} onChange={(e) => setF({ ...f, senha: e.target.value })} /></label>
          <label>Papel
            <select value={f.papel} onChange={(e) => setF({ ...f, papel: e.target.value as Papel })}>
              <option value="USUARIO">Usuário</option><option value="GESTOR">Gestor</option><option value="ADMIN">Admin</option>
            </select>
          </label>
          <button className="primario">Criar usuário</button>
        </form>
      </div>
    </>
  )
}
