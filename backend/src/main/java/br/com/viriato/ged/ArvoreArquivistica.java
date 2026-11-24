package br.com.viriato.ged;

import br.com.viriato.cadastro.Cliente;
import br.com.viriato.cadastro.ClienteRepository;
import br.com.viriato.cadastro.SetorRepository;
import java.text.Normalizer;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Árvore arquivística no armazenamento (guarda legal de 5 anos ou mais), por empresa, ano, mês e setor:
 * {@code tenant_{id}/cliente_{numero}_{cnpj}/{ano}/{mm}/{setor}/{processo|geral}/{uuid}_{nome}}.
 */
@Component
public class ArvoreArquivistica {
    private final ClienteRepository clientes;
    private final SetorRepository setores;

    public ArvoreArquivistica(ClienteRepository clientes, SetorRepository setores) {
        this.clientes = clientes;
        this.setores = setores;
    }

    public String chave(Long tenantId, Long clienteId, Integer ano, Integer mes, Long setorId, Long processoId, String nome) {
        Cliente c = clienteId == null ? null : clientes.findByIdAndTenantId(clienteId, tenantId).orElse(null);
        String empresa = c == null ? "cliente_sem-empresa" : "cliente_" + limpar(c.getNumero()) + "_" + (c.getCnpj() == null ? "sem-cnpj" : c.getCnpj());
        String setor = setorId == null ? "sem-setor" : setores.findByIdAndTenantId(setorId, tenantId).map(s -> slug(s.getNome())).orElse("sem-setor");
        return "tenant_%d/%s/%s/%s/%s/%s/%s_%s".formatted(tenantId, empresa, ano == null ? "sem-ano" : String.valueOf(ano),
                mes == null ? "00" : "%02d".formatted(mes), setor, processoId == null ? "geral" : String.valueOf(processoId), UUID.randomUUID(), nome);
    }

    static String slug(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return n.isEmpty() ? "setor" : n;
    }

    private static String limpar(String s) { return s == null ? "0" : s.replaceAll("[^A-Za-z0-9]", ""); }
}
