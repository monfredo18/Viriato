package br.com.viriato;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/** Migração que falhou na metade (comum no MySQL) não pode deixar o servidor sem subir: o reparo limpa o registro e migra. */
class FlywayReparoTest {

    @Test
    void registroDeMigracaoComFalhaEReparadoAntesDeMigrar() throws Exception {
        String url = "jdbc:h2:mem:reparo;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway ate16 = Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").target("16").load();
        ate16.migrate();
        // simula a migração 17 que falhou no meio: ficou o registro com success = false
        try (var c = DriverManager.getConnection(url, "sa", ""); var st = c.createStatement()) {
            st.execute("INSERT INTO \"flyway_schema_history\" (\"installed_rank\", \"version\", \"description\", \"type\", \"script\", \"checksum\", \"installed_by\", \"installed_on\", \"execution_time\", \"success\") "
                    + "VALUES (99, '17', 'falhou', 'SQL', 'V17__falhou.sql', 1, 'sa', CURRENT_TIMESTAMP, 1, FALSE)");
        }
        Flyway completo = Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load();
        assertThatThrownBy(completo::migrate).hasMessageContaining("failed");   // sem o reparo, não sobe
        completo.repair();
        assertThat(completo.migrate().success).isTrue();                          // com o reparo, termina de migrar
        assertThat(completo.info().current().getVersion().getVersion()).isNotEqualTo("16");
    }
}
