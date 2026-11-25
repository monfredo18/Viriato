package br.com.viriato.agente;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CertificadoAgenteRepository extends JpaRepository<CertificadoAgente, Long> {
    List<CertificadoAgente> findByAgenteId(Long agenteId);
    List<CertificadoAgente> findByCertificadoIdIn(Collection<Long> certificadoIds);
    List<CertificadoAgente> findByCertificadoId(Long certificadoId);

    /** Há algum agente conectado (contato recente, não revogado) com este certificado? */
    @Query("select count(a) from CertificadoAgente ca, Agente a where ca.certificadoId = :cert and a.id = ca.agenteId and a.revogado = false and a.ultimoContato > :desde")
    long agentesConectados(Long cert, Instant desde);
}
