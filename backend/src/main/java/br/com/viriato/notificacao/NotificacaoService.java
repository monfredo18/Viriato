package br.com.viriato.notificacao;

import br.com.viriato.cadastro.Cliente;
import br.com.viriato.cadastro.ClienteRepository;
import br.com.viriato.cadastro.Entrega;
import br.com.viriato.cadastro.EntregaRepository;
import br.com.viriato.processo.Processo;
import br.com.viriato.usuario.Usuario;
import br.com.viriato.usuario.UsuarioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificacaoService {
    private final NotificacaoRepository repo;
    private final UsuarioRepository usuarios;
    private final EmailService email;
    private final ClienteRepository clientes;
    private final EntregaRepository entregas;

    public NotificacaoService(NotificacaoRepository repo, UsuarioRepository usuarios, EmailService email,
                              ClienteRepository clientes, EntregaRepository entregas) {
        this.repo = repo;
        this.usuarios = usuarios;
        this.email = email;
        this.clientes = clientes;
        this.entregas = entregas;
    }

    /**
     * Avisa o destinatário sobre uma ação em um processo (in-app + e-mail assíncrono), informando o que foi feito,
     * a empresa, a entrega e quem fez. Regra de setor: quem pertence a um setor só recebe aviso de processos do
     * seu próprio setor (quem não tem setor, como o administrador, recebe os que lhe cabem).
     *
     * @param autorId quem fez a ação; {@code null} quando foi o sistema (ex.: alerta de prazo)
     */
    @Transactional
    public void notificar(Processo p, Long destinatarioId, String acao, String detalhe, Long autorId) {
        if (destinatarioId == null) return;
        Long tenantId = p.getTenantId();
        usuarios.findByIdAndTenantId(destinatarioId, tenantId).filter(Usuario::isAtivo).ifPresent(u -> {
            if (u.getSetorId() != null && p.getSetorId() != null && !u.getSetorId().equals(p.getSetorId())) return;   // outro setor: não notifica

            String empresa = p.getClienteId() == null ? null : clientes.findByIdAndTenantId(p.getClienteId(), tenantId).map(Cliente::getNome).orElse(null);
            String entrega = p.getEntregaId() == null ? null : entregas.findByIdAndTenantId(p.getEntregaId(), tenantId).map(Entrega::getNome).orElse(null);
            String autor = autorId == null ? "Sistema" : usuarios.findByIdAndTenantId(autorId, tenantId).map(Usuario::getNome).orElse("Usuário removido");

            Notificacao n = new Notificacao();
            n.setTenantId(tenantId);
            n.setUsuarioId(destinatarioId);
            n.setTitulo(acao);
            n.setMensagem(detalhe != null && detalhe.length() > 500 ? detalhe.substring(0, 500) : detalhe);
            n.setEmpresaNome(empresa);
            n.setEntregaNome(entrega);
            n.setAutorNome(autor);
            n.setProcessoId(p.getId());
            repo.save(n);

            StringBuilder txt = new StringBuilder(acao).append("\n\n");
            txt.append("Processo: ").append(p.getNumero()).append('\n');
            if (empresa != null) txt.append("Empresa: ").append(empresa).append('\n');
            if (entrega != null) txt.append("Entrega: ").append(entrega).append('\n');
            txt.append("Por: ").append(autor).append('\n');
            if (detalhe != null && !detalhe.isBlank()) txt.append('\n').append(detalhe).append('\n');
            email.enviar(u.getEmail(), "[Viriato] " + acao, txt.toString());
        });
    }
}
