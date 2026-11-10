package br.com.viriato.seguranca;

import br.com.viriato.usuario.Papel;

/** Identidade extraída do JWT. O tenantId vem SEMPRE daqui, nunca do corpo da requisição. */
public record UsuarioLogado(Long id, Long tenantId, Papel papel) {
}
