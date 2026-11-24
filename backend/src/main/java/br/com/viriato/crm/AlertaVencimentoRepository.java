package br.com.viriato.crm;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertaVencimentoRepository extends JpaRepository<AlertaVencimento, Long> {
    boolean existsByTipoAndRefIdAndMarco(String tipo, Long refId, int marco);
    void deleteByTipoAndRefId(String tipo, Long refId);
}
