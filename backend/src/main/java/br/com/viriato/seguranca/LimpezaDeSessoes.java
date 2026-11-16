package br.com.viriato.seguranca;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;

/** Apaga, uma vez por dia, as revogações de tokens que já venceram sozinhos. */
@Component
public class LimpezaDeSessoes {
    private final SessaoRevogadaRepository repo;

    public LimpezaDeSessoes(SessaoRevogadaRepository repo) { this.repo = repo; }

    @Scheduled(cron = "0 30 3 * * *", zone = "America/Sao_Paulo")
    public void limpar() { repo.apagarVencidas(Instant.now()); }
}
