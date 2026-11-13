package br.com.viriato.notificacao;

import br.com.viriato.cadastro.Cliente;
import br.com.viriato.cadastro.ClienteRepository;
import br.com.viriato.cadastro.Entrega;
import br.com.viriato.cadastro.EntregaRepository;
import br.com.viriato.processo.Processo;
import br.com.viriato.usuario.Papel;
import br.com.viriato.usuario.Usuario;
import br.com.viriato.usuario.UsuarioRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificacaoService {
    private final NotificacaoRepository repo;
    private final UsuarioRepository usuarios;
    private final ClienteRepository clientes;
    private final EntregaRepository entregas;

    public NotificacaoService(NotificacaoRepository repo, UsuarioRepository usuarios,
                              ClienteRepository clientes, EntregaRepository entregas) {
        this.repo = repo;
        this.usuarios = usuarios;
        this.clientes = clientes;
        this.entregas = entregas;
    }

    /**
     * Avisa o GESTOR DO SETOR do processo sobre uma ação (no sistema, pelo sininho). Só os gestores ativos do
     * setor do processo recebem; funcionários, responsáveis, administradores e gestores de outros setores não.
     * Quem fez a ação não é avisado de si mesmo. O título já traz o nome de quem fez: "Processo movido para Revisão - Nome".
     *
     * @param autorId quem fez a ação; {@code null} quando foi o sistema (ex.: alerta de prazo)
     */
    @Transactional
    public void notificar(Processo p, String acao, String detalhe, Long autorId) {
        if (p.getSetorId() == null) return;
        enviar(p, usuarios.findByTenantIdAndPapelAndSetorIdAndAtivoTrue(p.getTenantId(), Papel.GESTOR, p.getSetorId()), acao, detalhe, autorId);
    }

    /**
     * Pedidos de aprovação de etapas que só o ADMINISTRADOR pode aprovar: avisa os administradores ativos da empresa
     * (o gestor do setor não consegue aprovar, então não é o destinatário). Mesmas regras de conteúdo de {@link #notificar}.
     */
    @Transactional
    public void notificarAdministradores(Processo p, String acao, String detalhe, Long autorId) {
        enviar(p, usuarios.findByTenantIdAndPapelInAndAtivoTrue(p.getTenantId(), List.of(Papel.ADMIN)), acao, detalhe, autorId);
    }

    private void enviar(Processo p, List<Usuario> destinatarios, String acao, String detalhe, Long autorId) {
        Long tenantId = p.getTenantId();
        String empresa = p.getClienteId() == null ? null : clientes.findByIdAndTenantId(p.getClienteId(), tenantId).map(Cliente::getNome).orElse(null);
        String entrega = p.getEntregaId() == null ? null : entregas.findByIdAndTenantId(p.getEntregaId(), tenantId).map(Entrega::getNome).orElse(null);
        String autor = autorId == null ? null : usuarios.findByIdAndTenantId(autorId, tenantId).map(Usuario::getNome).orElse("Usuário removido");
        String titulo = autor == null ? acao : acao + " - " + autor;

        for (Usuario gestor : destinatarios) {
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

        }
    }
}
