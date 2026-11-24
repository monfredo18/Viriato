package br.com.viriato.auditoria;

import br.com.viriato.usuario.Usuario;
import br.com.viriato.usuario.UsuarioRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Grava e lista a auditoria. Cada registro é gravado em transação própria: um login recusado, por exemplo, fica
 * registrado mesmo que a requisição tenha sido desfeita.
 */
@Service
public class AuditoriaService {
    public record Registro(Long id, String usuario, String acao, String detalhe, Instant criadoEm, String ip, String userAgent) {}

    static final int LIMITE = 300;
    private final AuditoriaRepository repo;
    private final UsuarioRepository usuarios;

    public AuditoriaService(AuditoriaRepository repo, UsuarioRepository usuarios) {
        this.repo = repo;
        this.usuarios = usuarios;
    }

    /** {@code autorId}: quem fez a ação (ou de quem é a conta, nos eventos de login). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registrar(Long tenantId, Long autorId, String acao, String detalhe) {
        registrar(tenantId, autorId, acao, detalhe, null, null);
    }

    /** Com a origem (endereço e navegador), usada nos eventos de acesso. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registrar(Long tenantId, Long autorId, String acao, String detalhe, String ip, String userAgent) {
        Auditoria a = new Auditoria();
        a.setTenantId(tenantId);
        a.setUsuarioId(autorId);
        a.setUsuarioNome(autorId == null ? null : usuarios.findById(autorId).map(Usuario::getNome).orElse(null));
        a.setAcao(acao);
        a.setDetalhe(detalhe != null && detalhe.length() > 500 ? detalhe.substring(0, 500) : detalhe);
        a.setIp(ip == null ? null : cortar(ip, 64));
        a.setUserAgent(userAgent == null ? null : cortar(userAgent, 300));
        repo.save(a);
    }

    @Transactional(readOnly = true)
    public List<Registro> recentes(Long tenantId) {
        return repo.findByTenantIdOrderByIdDesc(tenantId, PageRequest.of(0, LIMITE)).stream()
                .map(a -> new Registro(a.getId(), a.getUsuarioNome(), a.getAcao(), a.getDetalhe(), a.getCriadoEm(), a.getIp(), a.getUserAgent())).toList();
    }

    private static String cortar(String t, int max) { return t.length() > max ? t.substring(0, max) : t; }
}
