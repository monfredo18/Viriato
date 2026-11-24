package br.com.viriato.documento;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AntivirusConfig {
    @Bean
    AntivirusScanner antivirusScanner(@Value("${viriato.antivirus.clamav.habilitado:false}") boolean habilitado,
                                      @Value("${viriato.antivirus.clamav.host:localhost}") String host,
                                      @Value("${viriato.antivirus.clamav.porta:3310}") int porta,
                                      @Value("${viriato.antivirus.clamav.timeout-ms:15000}") int timeoutMs) {
        return habilitado ? new ClamAvScanner(host, porta, timeoutMs) : new NoOpScanner();
    }
}
