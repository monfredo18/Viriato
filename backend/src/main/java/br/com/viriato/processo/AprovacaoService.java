package br.com.viriato.processo;

import br.com.viriato.comum.NaoEncontradoException;
import br.com.viriato.comum.NegocioException;
import br.com.viriato.fluxo.Etapa;
import br.com.viriato.fluxo.EtapaRepository;
import br.com.viriato.fluxo.RequisitoService;
import br.com.viriato.notificacao.NotificacaoService;
import br.com.viriato.seguranca.UsuarioLogado;
import br.com.viriato.usuario.Papel;
import br.com.viriato.usuario.Usuario;
import br.com.viriato.usuario.UsuarioRepository;
import java.time.Instant;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aprovação para mover processos a etapas que exigem gestor (ou administrador). Quem não tem esse papel solicita;
 * quem tem, e enxerga o processo, aprova (o processo avança, com os requisitos conferidos) ou recusa com motivo.
 * Há no máximo um pedido pendente por processo, e ele é cancelado se o processo andar por outro caminho.
 */
@Service
public class AprovacaoService {
    private final ProcessoService processos;
    private final ProcessoAprovacaoRepository repo;
    private final EtapaRepository etapas;
    private final RequisitoService requisitos;
    private final NotificacaoService notificacoes;
    private final UsuarioRepository usuarios;

    public AprovacaoService(ProcessoService processos, ProcessoAprovacaoRepository repo, EtapaRepository etapas,
                            RequisitoService requisitos, NotificacaoService notificacoes, UsuarioRepository usuarios) {
        this.processos = processos;
        this.repo = repo;
        this.etapas = etapas;
        this.requisitos = requisitos;
        this.notificacoes = notificacoes;
        this.usuarios = usuarios;
    }

    private String nome(Long usuarioId, Long tenantId) {
        return usuarioId == null ? "usuário removido" : usuarios.findByIdAndTenantId(usuarioId, tenantId).map(Usuario::getNome).orElse("usuário removido");
    }

    @Transactional
    public void solicitar(UsuarioLogado quem, Long processoId, Long etapaDestinoId, String comentario) {
        Processo p = processos.buscar(quem, processoId);
        processos.exigirAberto(p);
        processos.exigirPodeMexer(quem, p);
        Etapa destino = etapas.findByIdAndTenantId(etapaDestinoId, quem.tenantId()).orElseThrow(() -> new NaoEncontradoException("Etapa"));
        if (quem.papel().atende(destino.getPapelMinimo())) {
            throw new NegocioException("Você pode mover direto para \"" + destino.getNome() + "\"; não precisa de aprovação.");
        }
        processos.exigirTransicaoPermitida(p, destino);
        Etapa origem = etapas.findById(p.getEtapaAtualId()).orElseThrow();
        if (destino.getOrdem() > origem.getOrdem()) requisitos.exigirCumpridos(p, origem);   // o aprovador recebe o pedido já em condições
        if (repo.existsByProcessoIdAndStatus(p.getId(), StatusAprovacao.PENDENTE)) {
            throw new NegocioException("Já existe um pedido de aprovação pendente para este processo");
        }

        ProcessoAprovacao a = new ProcessoAprovacao();
        a.setTenantId(p.getTenantId());
        a.setProcessoId(p.getId());
        a.setEtapaOrigemId(origem.getId());
        a.setEtapaDestinoId(destino.getId());
        a.setSolicitadoPor(quem.id());
        a.setComentario(comentario == null || comentario.isBlank() ? null : comentario.trim());
        repo.save(a);

        String texto = "Aprovação solicitada para \"" + destino.getNome() + "\"" + (a.getComentario() == null ? "" : ": " + a.getComentario());
        processos.registrarEvento(p, quem.id(), "APROVACAO_SOLICITADA", texto);
        String titulo = "Aprovação solicitada para " + destino.getNome();
        if (destino.getPapelMinimo() == Papel.ADMIN) notificacoes.notificarAdministradores(p, titulo, a.getComentario(), quem.id());
        else notificacoes.notificar(p, titulo, a.getComentario(), quem.id());
    }

    private ProcessoAprovacao pendente(Processo p, Long aprovacaoId, Long tenantId) {
        ProcessoAprovacao a = repo.findByIdAndProcessoIdAndTenantId(aprovacaoId, p.getId(), tenantId)
                .orElseThrow(() -> new NaoEncontradoException("Pedido de aprovação"));
        if (a.getStatus() != StatusAprovacao.PENDENTE) throw new NegocioException("Este pedido já foi decidido ou cancelado");
        return a;
    }

    private Etapa destinoDecidivel(UsuarioLogado quem, ProcessoAprovacao a) {
        Etapa destino = etapas.findById(a.getEtapaDestinoId()).orElseThrow(() -> new NaoEncontradoException("Etapa"));
        if (!quem.papel().atende(destino.getPapelMinimo())) {
            throw new AccessDeniedException("Só " + ProcessoService.rotuloPapelMinimo(destino.getPapelMinimo()) + " pode decidir este pedido");
        }
        return destino;
    }

    /** Aprova e já move o processo para a etapa pedida, em nome do aprovador. */
    @Transactional
    public ProcessoDto aprovar(UsuarioLogado quem, Long processoId, Long aprovacaoId, String comentario) {
        Processo p = processos.buscar(quem, processoId);
        ProcessoAprovacao a = pendente(p, aprovacaoId, quem.tenantId());
        Etapa destino = destinoDecidivel(quem, a);
        processos.exigirAberto(p);
        if (!a.getEtapaOrigemId().equals(p.getEtapaAtualId())) throw new NegocioException("O processo mudou de etapa desde o pedido. Peça novamente.");

        a.setStatus(StatusAprovacao.APROVADA);
        a.setDecididoPor(quem.id());
        a.setDecididoEm(Instant.now());
        a.setMotivo(comentario == null || comentario.isBlank() ? null : comentario.trim());
        repo.saveAndFlush(a);

        String nota = "Aprovado por " + nome(quem.id(), quem.tenantId()) + " (solicitado por " + nome(a.getSolicitadoPor(), quem.tenantId()) + ")"
                + (a.getMotivo() == null ? "" : ". " + a.getMotivo());
        return processos.movimentar(quem, p, destino, nota);
    }

    @Transactional
    public void recusar(UsuarioLogado quem, Long processoId, Long aprovacaoId, String motivo) {
        Processo p = processos.buscar(quem, processoId);
        ProcessoAprovacao a = pendente(p, aprovacaoId, quem.tenantId());
        Etapa destino = destinoDecidivel(quem, a);
        if (motivo == null || motivo.isBlank()) throw new NegocioException("Informe o motivo da recusa");
        a.setStatus(StatusAprovacao.RECUSADA);
        a.setDecididoPor(quem.id());
        a.setDecididoEm(Instant.now());
        a.setMotivo(motivo.trim());
        repo.save(a);
        processos.registrarEvento(p, quem.id(), "APROVACAO_RECUSADA", "Aprovação para \"" + destino.getNome() + "\" recusada: " + a.getMotivo());
    }

    /** Quem pediu retira o pedido; gestor e administrador também podem retirá-lo. */
    @Transactional
    public void cancelar(UsuarioLogado quem, Long processoId, Long aprovacaoId) {
        Processo p = processos.buscar(quem, processoId);
        ProcessoAprovacao a = pendente(p, aprovacaoId, quem.tenantId());
        boolean dono = quem.id().equals(a.getSolicitadoPor());
        if (!dono && !quem.papel().atende(Papel.GESTOR)) throw new AccessDeniedException("Só quem pediu, um gestor ou o administrador retira o pedido");
        a.setStatus(StatusAprovacao.CANCELADA);
        a.setDecididoEm(Instant.now());
        repo.save(a);
        Etapa destino = etapas.findById(a.getEtapaDestinoId()).orElse(null);
        processos.registrarEvento(p, quem.id(), "APROVACAO_CANCELADA", "Pedido de aprovação para \"" + (destino == null ? "?" : destino.getNome()) + "\" retirado");
    }
}
