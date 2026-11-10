package br.com.viriato.seguranca;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class Contexto {
    private Contexto() {}

    public static UsuarioLogado atual() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UsuarioLogado u)) {
            throw new IllegalStateException("Sem usuário autenticado");
        }
        return u;
    }

    public static Long tenantId() { return atual().tenantId(); }
}
