package br.com.viriato.usuario;

public record UsuarioDto(Long id, String nome, String email, Papel papel, boolean ativo) {
    public static UsuarioDto de(Usuario u) {
        return new UsuarioDto(u.getId(), u.getNome(), u.getEmail(), u.getPapel(), u.isAtivo());
    }
}
