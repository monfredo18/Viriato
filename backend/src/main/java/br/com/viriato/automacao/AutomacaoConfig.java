package br.com.viriato.automacao;

import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AutomacaoConfig {

    /** As regras rodam fora da requisição (o webhook pode demorar). Nos testes ({@code assincrona=false}) rodam na hora. */
    @Bean("automacaoTaskExecutor")
    Executor automacaoTaskExecutor(@Value("${viriato.automacao.assincrona:true}") boolean assincrona) {
        if (!assincrona) return Runnable::run;
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setThreadNamePrefix("automacao-");
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(4);
        ex.setQueueCapacity(200);
        ex.initialize();
        return ex;
    }
}
