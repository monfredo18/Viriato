package br.com.viriato.fiscal;

import br.com.viriato.cadastro.ClienteRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class VinculoAutomatico {
    private final MatrizService matriz;
    private final ClienteRepository clientes;

    public VinculoAutomatico(MatrizService matriz, ClienteRepository clientes) {
        this.matriz = matriz;
        this.clientes = clientes;
    }

    @EventListener
    public void aoAtualizar(FiscalDoClienteAtualizado e) {
        clientes.findByIdAndTenantId(e.clienteId(), e.tenantId()).ifPresent(matriz::vincularCompativeis);
    }
}
