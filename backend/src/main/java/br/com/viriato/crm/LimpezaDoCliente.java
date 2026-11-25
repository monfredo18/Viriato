package br.com.viriato.crm;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class LimpezaDoCliente {
    private final CertificadoService certs;
    private final ProcuracaoService procs;

    public LimpezaDoCliente(CertificadoService certs, ProcuracaoService procs) {
        this.certs = certs;
        this.procs = procs;
    }

    @EventListener
    public void aoApagar(ClienteApagado e) {
        certs.desvincularDaEmpresa(e.tenantId(), e.clienteId());
        procs.removerArquivosDaEmpresa(e.tenantId(), e.clienteId());
    }
}
