package br.com.viriato.usuario;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {
    Optional<Usuario> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);
    Optional<Usuario> findByIdAndTenantId(Long id, Long tenantId);
    List<Usuario> findByTenantIdOrderByNome(Long tenantId);
    List<Usuario> findByTenantIdAndSetorId(Long tenantId, Long setorId);
    List<Usuario> findByTenantIdAndPapelAndSetorIdAndAtivoTrue(Long tenantId, Papel papel, Long setorId);
    List<Usuario> findByTenantIdAndPapelInAndAtivoTrue(Long tenantId, List<Papel> papeis);
}
