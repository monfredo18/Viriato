package br.com.viriato.ajuda;

import br.com.viriato.comum.NaoEncontradoException;
import br.com.viriato.comum.NegocioException;
import br.com.viriato.cadastro.Setor;
import br.com.viriato.cadastro.SetorRepository;
import br.com.viriato.seguranca.UsuarioLogado;
import br.com.viriato.usuario.Papel;
import br.com.viriato.usuario.Usuario;
import br.com.viriato.usuario.UsuarioRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chat de ajuda entre as pessoas da empresa e os administradores. Cada pessoa tem UMA conversa, que todos os
 * administradores da empresa enxergam e respondem; a pessoa só enxerga a própria. Administradores não abrem conversa:
 * eles atendem. Tudo é restrito à empresa (tenant) de quem pede.
 */
@Service
public class AjudaService {
    static final int MAX_TEXTO = 2000;
    static final int MAX_MENSAGENS = 200;

    public record MensagemDto(Long id, String autorNome, boolean deAdmin, String texto, Instant criadoEm) {}
    public record ConversaResumo(Long id, String pessoa, String setor, String ultimaTexto, boolean ultimaDeAdmin, Instant atualizadoEm, int naoLidas) {}
    public record ConversaDetalhe(Long id, String pessoa, String setor, List<MensagemDto> mensagens) {}

    private final AjudaConversaRepository conversas;
    private final AjudaMensagemRepository mensagens;
    private final UsuarioRepository usuarios;
    private final SetorRepository setores;

    public AjudaService(AjudaConversaRepository conversas, AjudaMensagemRepository mensagens,
                        UsuarioRepository usuarios, SetorRepository setores) {
        this.conversas = conversas;
        this.mensagens = mensagens;
        this.usuarios = usuarios;
        this.setores = setores;
    }

    private static void exigirAdmin(UsuarioLogado quem) {
        if (quem.papel() != Papel.ADMIN) throw new AccessDeniedException("Somente administradores atendem as conversas de ajuda");
    }

    private static void exigirPessoa(UsuarioLogado quem) {
        if (quem.papel() == Papel.ADMIN) throw new AccessDeniedException("Administradores respondem pela lista de conversas");
    }

    private static String limpar(String texto) {
        String t = texto == null ? "" : texto.trim();
        if (t.isEmpty()) throw new NegocioException("Escreva a mensagem");
        if (t.length() > MAX_TEXTO) throw new NegocioException("A mensagem pode ter no máximo " + MAX_TEXTO + " caracteres");
        return t;
    }

    private static String resumo(String t) {
        String linha = t.replaceAll("\\s+", " ");
        return linha.length() > 200 ? linha.substring(0, 199) + "…" : linha;
    }

    private List<MensagemDto> listar(Long conversaId, Long tenantId) {
        List<MensagemDto> lista = new ArrayList<>(mensagens.findByConversaIdAndTenantIdOrderByIdDesc(conversaId, tenantId, PageRequest.of(0, MAX_MENSAGENS))
                .stream().map(m -> new MensagemDto(m.getId(), m.getAutorNome(), m.isDeAdmin(), m.getTexto(), m.getCriadoEm())).toList());
        Collections.reverse(lista);
        return lista;
    }

    private String nomeSetor(Usuario u) {
        return u.getSetorId() == null ? null : setores.findByIdAndTenantId(u.getSetorId(), u.getTenantId()).map(Setor::getNome).orElse(null);
    }

    private AjudaMensagem gravar(AjudaConversa c, UsuarioLogado quem, String autorNome, boolean deAdmin, String texto) {
        AjudaMensagem m = new AjudaMensagem();
        m.setTenantId(quem.tenantId());
        m.setConversaId(c.getId());
        m.setAutorId(quem.id());
        m.setAutorNome(autorNome);
        m.setDeAdmin(deAdmin);
        m.setTexto(texto);
        return mensagens.save(m);
    }

    /** Quantidade de mensagens que ainda não foram vistas: as respostas para a pessoa; a soma de tudo para o administrador. */
    @Transactional(readOnly = true)
    public long naoLidas(UsuarioLogado quem) {
        if (quem.papel() == Papel.ADMIN) return conversas.totalNaoLidasAdmin(quem.tenantId());
        return conversas.findByUsuarioIdAndTenantId(quem.id(), quem.tenantId()).map(AjudaConversa::getNaoLidasUsuario).orElse(0);
    }

    // ---------- pessoa ----------

    /** Abre a própria conversa (vazia se ainda não escreveu) e marca as respostas como vistas. */
    @Transactional
    public List<MensagemDto> minhaConversa(UsuarioLogado quem) {
        exigirPessoa(quem);
        var c = conversas.findByUsuarioIdAndTenantId(quem.id(), quem.tenantId());
        if (c.isEmpty()) return List.of();
        if (c.get().getNaoLidasUsuario() > 0) conversas.zerarDaPessoa(c.get().getId());
        return listar(c.get().getId(), quem.tenantId());
    }

    @Transactional
    public MensagemDto pedirAjuda(UsuarioLogado quem, String texto) {
        exigirPessoa(quem);
        String t = limpar(texto);
        Usuario eu = usuarios.findByIdAndTenantId(quem.id(), quem.tenantId()).orElseThrow(() -> new NaoEncontradoException("Usuário"));
        AjudaConversa c = conversas.findByUsuarioIdAndTenantId(quem.id(), quem.tenantId()).orElseGet(() -> {
            AjudaConversa nova = new AjudaConversa();
            nova.setTenantId(quem.tenantId());
            nova.setUsuarioId(quem.id());
            return conversas.save(nova);
        });
        AjudaMensagem m = gravar(c, quem, eu.getNome(), false, t);
        conversas.pessoaEscreveu(c.getId(), resumo(t), m.getCriadoEm());
        return new MensagemDto(m.getId(), m.getAutorNome(), false, m.getTexto(), m.getCriadoEm());
    }

    // ---------- administradores ----------

    @Transactional(readOnly = true)
    public List<ConversaResumo> conversasDosAdmins(UsuarioLogado quem) {
        exigirAdmin(quem);
        Map<Long, Usuario> pessoas = usuarios.findByTenantIdOrderByNome(quem.tenantId()).stream().collect(Collectors.toMap(Usuario::getId, Function.identity()));
        Map<Long, String> nomesSetor = setores.findByTenantIdOrderByNome(quem.tenantId()).stream().collect(Collectors.toMap(Setor::getId, Setor::getNome));
        return conversas.findByTenantIdOrderByAtualizadoEmDesc(quem.tenantId()).stream().map(c -> {
            Usuario u = pessoas.get(c.getUsuarioId());
            return new ConversaResumo(c.getId(), u == null ? "Usuário removido" : u.getNome(),
                    u == null || u.getSetorId() == null ? null : nomesSetor.get(u.getSetorId()),
                    c.getUltimaTexto(), c.isUltimaDeAdmin(), c.getAtualizadoEm(), c.getNaoLidasAdmin());
        }).toList();
    }

    private AjudaConversa daEmpresa(UsuarioLogado quem, Long id) {
        return conversas.findByIdAndTenantId(id, quem.tenantId()).orElseThrow(() -> new NaoEncontradoException("Conversa"));
    }

    @Transactional
    public ConversaDetalhe abrir(UsuarioLogado quem, Long id) {
        exigirAdmin(quem);
        AjudaConversa c = daEmpresa(quem, id);
        if (c.getNaoLidasAdmin() > 0) conversas.zerarDosAdmins(c.getId());
        Usuario u = usuarios.findByIdAndTenantId(c.getUsuarioId(), quem.tenantId()).orElse(null);
        return new ConversaDetalhe(c.getId(), u == null ? "Usuário removido" : u.getNome(), u == null ? null : nomeSetor(u), listar(c.getId(), quem.tenantId()));
    }

    @Transactional
    public MensagemDto responder(UsuarioLogado quem, Long id, String texto) {
        exigirAdmin(quem);
        AjudaConversa c = daEmpresa(quem, id);
        String t = limpar(texto);
        Usuario eu = usuarios.findByIdAndTenantId(quem.id(), quem.tenantId()).orElseThrow(() -> new NaoEncontradoException("Usuário"));
        AjudaMensagem m = gravar(c, quem, eu.getNome(), true, t);
        conversas.adminRespondeu(c.getId(), resumo(t), m.getCriadoEm());
        return new MensagemDto(m.getId(), m.getAutorNome(), true, m.getTexto(), m.getCriadoEm());
    }

    /** Ao apagar uma pessoa, a conversa dela some junto. */
    @Transactional
    public void apagarDe(Long usuarioId, Long tenantId) {
        conversas.findByUsuarioIdAndTenantId(usuarioId, tenantId).ifPresent(c -> {
            mensagens.deleteByConversaId(c.getId());
            conversas.delete(c);
        });
    }
}
