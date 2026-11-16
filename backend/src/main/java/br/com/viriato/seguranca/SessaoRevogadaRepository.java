package br.com.viriato.seguranca;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface SessaoRevogadaRepository extends JpaRepository<SessaoRevogada, String> {
    @Modifying
    @Transactional
    @Query("delete from SessaoRevogada s where s.expiraEm < :agora")
    int apagarVencidas(Instant agora);
}
