package br.com.viriato.notificacao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** Envio best-effort: falha de e-mail nunca deve derrubar a operação de negócio. */
@Service
public class EmailService {
    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender sender;
    private final boolean habilitado;
    private final String remetente;

    public EmailService(JavaMailSender sender,
                        @Value("${viriato.mail.habilitado}") boolean habilitado,
                        @Value("${viriato.mail.remetente}") String remetente) {
        this.sender = sender;
        this.habilitado = habilitado;
        this.remetente = remetente;
    }

    @Async
    public void enviar(String para, String assunto, String texto) {
        if (!habilitado) return;
        try {
            SimpleMailMessage m = new SimpleMailMessage();
            m.setFrom(remetente);
            m.setTo(para);
            m.setSubject(assunto);
            m.setText(texto);
            sender.send(m);
        } catch (Exception e) {
            log.warn("Falha ao enviar e-mail para {}: {}", para, e.getMessage());
        }
    }
}
