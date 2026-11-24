package br.com.viriato.ged;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SefazConfig {
    @Bean
    SefazDfeConnector sefazDfeConnector(@Value("${viriato.sefaz.habilitado:false}") boolean habilitado,
                                        @Value("${viriato.sefaz.ambiente:producao}") String ambiente,
                                        @Value("${viriato.sefaz.url:}") String url,
                                        @Value("${viriato.sefaz.truststore:}") String truststore,
                                        @Value("${viriato.sefaz.truststore-senha:}") String senha,
                                        @Value("${viriato.sefaz.timeout-segundos:30}") long timeout) {
        if (!habilitado) return new NoOpSefazConnector();
        boolean hom = ambiente.equalsIgnoreCase("homologacao");
        String endereco = !url.isBlank() ? url : hom ? "https://hom1.nfe.fazenda.gov.br/NFeDistribuicaoDFe/NFeDistribuicaoDFe.asmx"
                : "https://www1.nfe.fazenda.gov.br/NFeDistribuicaoDFe/NFeDistribuicaoDFe.asmx";
        return new SefazDfeHttpConnector(endereco, hom, truststore, senha, Duration.ofSeconds(timeout));
    }
}
