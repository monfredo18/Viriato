package br.com.viriato.comum;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Antes de migrar, conserta o histórico do Flyway: remove o registro de uma migração que falhou na metade (no MySQL a
 * DDL não desfaz sozinha e o servidor ficaria sem subir, com erro 502 no nginx) e realinha somas de verificação.
 * Isso não apaga nem altera dados do sistema, só a tabela de controle {@code flyway_schema_history}.
 */
@Configuration
public class FlywayConfig {
    private static final Logger log = LoggerFactory.getLogger(FlywayConfig.class);

    @Bean
    FlywayMigrationStrategy repararEMigrar() {
        return flyway -> {
            try {
                flyway.repair();
            } catch (RuntimeException e) {
                log.warn("Não foi possível reparar o histórico do Flyway: {}", e.getMessage());
            }
            flyway.migrate();
        };
    }
}
