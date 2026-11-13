package br.com.viriato.usuario;

/** O e-mail (só de contas antigas) não é exposto: o acesso é por nome e senha. */
public record UsuarioDto(Long id, String nome, Papel papel, boolean ativo, Long setorId, String setorNome, boolean deveTrocarSenha, Long fotoVersao) {
    public static UsuarioDto de(Usuario u) { return de(u, null); }

    public static UsuarioDto de(Usuario u, String setorNome) {
        return new UsuarioDto(u.getId(), u.getNome(), u.getPapel(), u.isAtivo(), u.getSetorId(), setorNome, u.isSenhaProvisoria(), u.getFotoVersao());
    }
}
