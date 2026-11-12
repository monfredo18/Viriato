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

    public boolean habilitado() { return habilitado; }

    /**
     * Envio imediato, com anexo opcional, para quem precisa saber se deu certo (as automações).
     *
     * @return {@code null} se enviou; senão o motivo da falha
     */
    public String enviarAgora(String para, String assunto, String texto, String nomeAnexo, byte[] anexo) {
        try {
            var msg = sender.createMimeMessage();
            var h = new org.springframework.mail.javamail.MimeMessageHelper(msg, anexo != null, "UTF-8");
            h.setFrom(remetente);
            h.setTo(para);
            h.setSubject(assunto);
            h.setText(texto);
            if (anexo != null) h.addAttachment(nomeAnexo, new org.springframework.core.io.ByteArrayResource(anexo), "application/pdf");
            sender.send(msg);
            return null;
        } catch (Exception e) {
            log.warn("Falha ao enviar e-mail para {}: {}", para, e.getMessage());
            return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
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
