package br.com.viriato.notificacao;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;

public interface NotificacaoRepository extends JpaRepository<Notificacao, Long> {
    List<Notificacao> findByUsuarioIdAndTenantIdOrderByIdDesc(Long usuarioId, Long tenantId, Pageable page);
    long countByUsuarioIdAndTenantIdAndLidaFalse(Long usuarioId, Long tenantId);
    void deleteByUsuarioIdAndTenantId(Long usuarioId, Long tenantId);
    Optional<Notificacao> findByIdAndUsuarioIdAndTenantId(Long id, Long usuarioId, Long tenantId);

    @Modifying
    @Query("update Notificacao n set n.lida = true where n.usuarioId = :u and n.tenantId = :t and n.lida = false")
    int marcarTodasLidas(Long u, Long t);
}
