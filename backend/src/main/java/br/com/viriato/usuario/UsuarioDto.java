package br.com.viriato.usuario;

public record UsuarioDto(Long id, String nome, String email, Papel papel, boolean ativo, Long setorId, String setorNome) {
    public static UsuarioDto de(Usuario u) { return de(u, null); }

    public static UsuarioDto de(Usuario u, String setorNome) {
        return new UsuarioDto(u.getId(), u.getNome(), u.getEmail(), u.getPapel(), u.isAtivo(), u.getSetorId(), setorNome);
    }
}
