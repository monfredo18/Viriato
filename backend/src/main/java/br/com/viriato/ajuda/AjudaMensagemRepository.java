package br.com.viriato.ajuda;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AjudaMensagemRepository extends JpaRepository<AjudaMensagem, Long> {
    /** As mais recentes primeiro (o serviço inverte para mostrar em ordem de conversa). */
    List<AjudaMensagem> findByConversaIdAndTenantIdOrderByIdDesc(Long conversaId, Long tenantId, Pageable page);
    void deleteByConversaId(Long conversaId);
}
