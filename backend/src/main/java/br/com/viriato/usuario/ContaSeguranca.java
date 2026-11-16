package br.com.viriato.usuario;

import br.com.viriato.auditoria.AuditoriaService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contagem de senhas erradas. Roda em transação própria: a falha continua registrada mesmo quando a requisição de
 * login é desfeita ao responder "credenciais inválidas".
 */
@Service
public class ContaSeguranca {
    /** Erros permitidos: a conta bloqueia no erro seguinte (o 6º), com o aviso de conta bloqueada. */
    public static final int ERROS_PERMITIDOS = 5;

    private final UsuarioRepository usuarios;
    private final AuditoriaService auditoria;

    public ContaSeguranca(UsuarioRepository usuarios, AuditoriaService auditoria) {
        this.usuarios = usuarios;
        this.auditoria = auditoria;
    }

    /** Soma o erro e bloqueia ao passar do limite. Devolve {@code true} se a conta está (agora ou já estava) bloqueada. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean registrarFalha(Long usuarioId) {
        usuarios.somarFalha(usuarioId);
        boolean acabouDeBloquear = usuarios.bloquearSePassouDe(usuarioId, ERROS_PERMITIDOS) > 0;
        Usuario u = usuarios.findById(usuarioId).orElseThrow();
        if (acabouDeBloquear) {
            auditoria.registrar(u.getTenantId(), u.getId(), "CONTA_BLOQUEADA", "Mais de " + ERROS_PERMITIDOS + " tentativas com senha ou código incorretos");
        }
        return u.isBloqueado();
    }
}
