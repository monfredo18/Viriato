package br.com.viriato.notificacao;

import br.com.viriato.comum.Datas;
import br.com.viriato.processo.Processo;
import br.com.viriato.processo.ProcessoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Avisa o responsável quando o prazo da etapa está vencendo ou venceu. Um alerta por etapa. */
@Component
public class PrazoScheduler {
    private static final Logger log = LoggerFactory.getLogger(PrazoScheduler.class);

    private final ProcessoRepository processos;
    private final NotificacaoService notificacoes;
    private final int antecedenciaDias;

    public PrazoScheduler(ProcessoRepository processos, NotificacaoService notificacoes,
                          @Value("${viriato.prazos.antecedencia-dias}") int antecedenciaDias) {
        this.processos = processos;
        this.notificacoes = notificacoes;
        this.antecedenciaDias = antecedenciaDias;
    }

    @Scheduled(cron = "${viriato.prazos.cron}", zone = "America/Sao_Paulo")
    @Transactional
    public int verificar() {
        var hoje = Datas.hoje();
        int n = 0;
        for (Processo p : processos.pendentesDeAlerta(hoje.plusDays(antecedenciaDias))) {
            boolean vencido = p.getPrazoEtapa().isBefore(hoje);
            String titulo = vencido ? "Prazo vencido" : "Prazo próximo do vencimento";
            notificacoes.notificar(p.getTenantId(), p.getResponsavelId(), titulo,
                    p.getNumero() + " - " + p.getTitulo() + " (prazo: " + p.getPrazoEtapa() + ")", p.getId());
            p.setPrazoAlertado(true);
            n++;
        }
        if (n > 0) log.info("{} alerta(s) de prazo enviado(s)", n);
        return n;
    }
}
