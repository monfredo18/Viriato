package br.com.viriato.ged;

import br.com.viriato.crm.ClienteApagado;
import br.com.viriato.documento.ArmazenamentoArquivos;
import br.com.viriato.documento.Documento;
import br.com.viriato.documento.DocumentoRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Ao apagar uma empresa atendida: some a pasta dela no GED (arquivos e registros); anexos de processos ficam, sem o vínculo com a empresa. */
@Component
public class LimpezaGed {
    private final DocumentoRepository documentos;
    private final ArmazenamentoArquivos storage;

    public LimpezaGed(DocumentoRepository documentos, ArmazenamentoArquivos storage) {
        this.documentos = documentos;
        this.storage = storage;
    }

    @EventListener
    public void aoApagar(ClienteApagado e) {
        for (Documento d : documentos.findByClienteIdAndTenantIdOrderByAnoDescMesDescIdDesc(e.clienteId(), e.tenantId())) {
            if (d.getProcessoId() == null) {
                storage.remover(d.getChaveS3());
                documentos.delete(d);
            } else {
                d.setClienteId(null);
                documentos.save(d);
            }
        }
    }
}
