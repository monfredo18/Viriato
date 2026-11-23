package br.com.viriato.usuario;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {
    Optional<Usuario> findByEmailIgnoreCase(String email);
    List<Usuario> findByNomeIgnoreCase(String nome);
    boolean existsByTenantIdAndNomeIgnoreCase(Long tenantId, String nome);
    boolean existsByEmailIgnoreCase(String email);
    Optional<Usuario> findByIdAndTenantId(Long id, Long tenantId);
    /** Setor atual de quem está logado (lido do banco, não do token, para refletir mudanças). */
    default Long setorDe(br.com.viriato.seguranca.UsuarioLogado quem) {
        return findByIdAndTenantId(quem.id(), quem.tenantId()).map(Usuario::getSetorId).orElse(null);
    }
    List<Usuario> findByTenantIdOrderByNome(Long tenantId);
    List<Usuario> findByTenantIdAndSetorId(Long tenantId, Long setorId);
    List<Usuario> findByTenantIdAndPapelAndSetorIdAndAtivoTrue(Long tenantId, Papel papel, Long setorId);
    List<Usuario> findByTenantIdAndPapelInAndAtivoTrue(Long tenantId, List<Papel> papeis);

    /** Soma uma falha de senha em uma só instrução (sem perder tentativas simultâneas). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Usuario u set u.tentativasFalhas = u.tentativasFalhas + 1 where u.id = :id")
    int somarFalha(Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Usuario u set u.bloqueado = true where u.id = :id and u.bloqueado = false and u.tentativasFalhas > :limite")
    int bloquearSePassouDe(Long id, int limite);
}
