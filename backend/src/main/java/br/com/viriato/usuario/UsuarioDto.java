package br.com.viriato.usuario;

/** O acesso é por nome e senha; o e-mail do funcionário só aparece para administrador, gestor e para a própria pessoa. */
public record UsuarioDto(Long id, String nome, Papel papel, boolean ativo, Long setorId, String setorNome, boolean deveTrocarSenha, Long fotoVersao,
                         boolean bloqueado, boolean doisFatores, String email) {
    /** Para a própria pessoa (sessão, perfil) e para quem administra: já traz o e-mail. */
    public static UsuarioDto de(Usuario u) { return de(u, null).comEmail(u.getEmail()); }

    /** O e-mail só vai para quem pode vê-lo (administrador, gestor e a própria pessoa). */
    public UsuarioDto comEmail(String e) {
        return new UsuarioDto(id, nome, papel, ativo, setorId, setorNome, deveTrocarSenha, fotoVersao, bloqueado, doisFatores, e);
    }

    public static UsuarioDto de(Usuario u, String setorNome) {
        return new UsuarioDto(u.getId(), u.getNome(), u.getPapel(), u.isAtivo(), u.getSetorId(), setorNome, u.isSenhaProvisoria(), u.getFotoVersao(),
                u.isBloqueado(), u.isTotpAtivo(), null);
    }
}
