package br.com.viriato.ajuda;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AjudaConversaRepository extends JpaRepository<AjudaConversa, Long> {
    Optional<AjudaConversa> findByUsuarioIdAndTenantId(Long usuarioId, Long tenantId);
    Optional<AjudaConversa> findByIdAndTenantId(Long id, Long tenantId);
    List<AjudaConversa> findByTenantIdOrderByAtualizadoEmDesc(Long tenantId);

    @Query("select coalesce(sum(c.naoLidasAdmin), 0) from AjudaConversa c where c.tenantId = :t")
    long totalNaoLidasAdmin(Long t);

    /** Contadores e resumo mudam em uma só instrução, sem perder mensagens que chegam ao mesmo tempo. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AjudaConversa c set c.naoLidasAdmin = c.naoLidasAdmin + 1, c.ultimaTexto = :texto, c.ultimaDeAdmin = false, c.atualizadoEm = :agora where c.id = :id")
    int pessoaEscreveu(Long id, String texto, Instant agora);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AjudaConversa c set c.naoLidasUsuario = c.naoLidasUsuario + 1, c.naoLidasAdmin = 0, c.ultimaTexto = :texto, c.ultimaDeAdmin = true, c.atualizadoEm = :agora where c.id = :id")
    int adminRespondeu(Long id, String texto, Instant agora);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AjudaConversa c set c.naoLidasUsuario = 0 where c.id = :id")
    int zerarDaPessoa(Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AjudaConversa c set c.naoLidasAdmin = 0 where c.id = :id")
    int zerarDosAdmins(Long id);
}
