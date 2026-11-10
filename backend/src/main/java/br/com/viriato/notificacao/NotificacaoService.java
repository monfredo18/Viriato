package br.com.viriato.notificacao;

import br.com.viriato.usuario.UsuarioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificacaoService {
    private final NotificacaoRepository repo;
    private final UsuarioRepository usuarios;
    private final EmailService email;

    public NotificacaoService(NotificacaoRepository repo, UsuarioRepository usuarios, EmailService email) {
        this.repo = repo;
        this.usuarios = usuarios;
        this.email = email;
    }

    /** Cria a notificação in-app e dispara e-mail assíncrono. */
    @Transactional
    public void notificar(Long tenantId, Long usuarioId, String titulo, String mensagem, Long processoId) {
        usuarios.findByIdAndTenantId(usuarioId, tenantId).filter(u -> u.isAtivo()).ifPresent(u -> {
            Notificacao n = new Notificacao();
            n.setTenantId(tenantId);
            n.setUsuarioId(usuarioId);
            n.setTitulo(titulo);
            n.setMensagem(mensagem != null && mensagem.length() > 500 ? mensagem.substring(0, 500) : mensagem);
            n.setProcessoId(processoId);
            repo.save(n);
            email.enviar(u.getEmail(), "[Viriato] " + titulo, mensagem == null ? titulo : mensagem);
        });
    }
}
