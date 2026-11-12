package br.com.viriato.notificacao;

import br.com.viriato.cadastro.Cliente;
import br.com.viriato.cadastro.ClienteRepository;
import br.com.viriato.cadastro.Entrega;
import br.com.viriato.cadastro.EntregaRepository;
import br.com.viriato.processo.Processo;
import br.com.viriato.usuario.Papel;
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
     * Avisa o GESTOR DO SETOR do processo sobre uma ação (in-app + e-mail assíncrono). Só os gestores ativos do
     * setor do processo recebem; funcionários, responsáveis, administradores e gestores de outros setores não.
     * Quem fez a ação não é avisado de si mesmo. O título já traz o nome de quem fez: "Processo movido para Revisão - Nome".
     *
     * @param autorId quem fez a ação; {@code null} quando foi o sistema (ex.: alerta de prazo)
     */
    @Transactional
    public void notificar(Processo p, String acao, String detalhe, Long autorId) {
        if (p.getSetorId() == null) return;
        Long tenantId = p.getTenantId();
        String empresa = p.getClienteId() == null ? null : clientes.findByIdAndTenantId(p.getClienteId(), tenantId).map(Cliente::getNome).orElse(null);
        String entrega = p.getEntregaId() == null ? null : entregas.findByIdAndTenantId(p.getEntregaId(), tenantId).map(Entrega::getNome).orElse(null);
        String autor = autorId == null ? null : usuarios.findByIdAndTenantId(autorId, tenantId).map(Usuario::getNome).orElse("Usuário removido");
        String titulo = autor == null ? acao : acao + " - " + autor;

        for (Usuario gestor : usuarios.findByTenantIdAndPapelAndSetorIdAndAtivoTrue(tenantId, Papel.GESTOR, p.getSetorId())) {
            if (gestor.getId().equals(autorId)) continue;

            Notificacao n = new Notificacao();
            n.setTenantId(tenantId);
            n.setUsuarioId(gestor.getId());
            n.setTitulo(titulo);
            n.setMensagem(detalhe != null && detalhe.length() > 500 ? detalhe.substring(0, 500) : detalhe);
            n.setEmpresaNome(empresa);
            n.setEntregaNome(entrega);
            n.setAutorNome(autor == null ? "Sistema" : autor);
            n.setProcessoId(p.getId());
            repo.save(n);

            StringBuilder txt = new StringBuilder(titulo).append("\n\n");
            txt.append("Processo: ").append(p.getNumero()).append('\n');
            if (empresa != null) txt.append("Empresa: ").append(empresa).append('\n');
            if (entrega != null) txt.append("Entrega: ").append(entrega).append('\n');
            if (detalhe != null && !detalhe.isBlank()) txt.append('\n').append(detalhe).append('\n');
            email.enviar(gestor.getEmail(), "[Viriato] " + titulo, txt.toString());
        }
    }
}
