package br.com.viriato.crm;

import br.com.viriato.cadastro.Cliente;
import br.com.viriato.cadastro.ClienteRepository;
import br.com.viriato.comum.NegocioException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Valida e aplica os dados fiscais e societários na empresa atendida. */
@Service
public class FichaFiscalService {
    static final Set<String> UFS = Set.of("AC", "AL", "AP", "AM", "BA", "CE", "DF", "ES", "GO", "MA", "MT", "MS", "MG", "PA", "PB", "PR", "PE",
            "PI", "RJ", "RN", "RS", "RO", "RR", "SC", "SP", "SE", "TO");

    private final ClienteRepository clientes;
    private final ObjectMapper json;

    public FichaFiscalService(ClienteRepository clientes, ObjectMapper json) {
        this.clientes = clientes;
        this.json = json;
    }

    /** Não grava: o chamador salva o cliente depois. */
    public void aplicar(Cliente c, DadosFiscais d) {
        if (d == null) return;
        Long tid = c.getTenantId();
        if (d.cnpj() != null) {
            String n = Documentos.digitos(d.cnpj());
            if (n.isEmpty()) c.setCnpj(null);
            else {
                if (!Documentos.cnpjValido(n)) throw new NegocioException("CNPJ inválido (confira os dígitos verificadores)");
                clientes.findByTenantIdAndCnpj(tid, n).filter(o -> !o.getId().equals(c.getId())).ifPresent(o -> {
                    throw new NegocioException("O CNPJ " + Documentos.formatarCnpj(n) + " já está cadastrado na empresa \"" + o.getNome() + "\"");
                });
                c.setCnpj(n);
            }
        }
        if (d.razaoSocial() != null) c.setRazaoSocial(texto(d.razaoSocial(), 200, "Razão social"));
        if (d.nomeFantasia() != null) c.setNomeFantasia(texto(d.nomeFantasia(), 200, "Nome fantasia"));
        if (d.inscricaoEstadual() != null) c.setInscricaoEstadual(texto(d.inscricaoEstadual(), 30, "Inscrição estadual"));
        if (d.inscricaoMunicipal() != null) c.setInscricaoMunicipal(texto(d.inscricaoMunicipal(), 30, "Inscrição municipal"));
        if (d.cnaePrincipal() != null) c.setCnaePrincipal(cnae(d.cnaePrincipal()));
        if (d.cnaePrincipalDescricao() != null) c.setCnaePrincipalDescricao(texto(d.cnaePrincipalDescricao(), 255, "Descrição do CNAE"));
        if (d.cnaesSecundarios() != null) {
            List<DadosFiscais.Cnae> lista = d.cnaesSecundarios().stream()
                    .map(x -> new DadosFiscais.Cnae(cnae(x.codigo()), x.descricao() == null ? null : x.descricao().trim())).toList();
            if (lista.stream().anyMatch(x -> x.codigo() == null)) throw new NegocioException("Informe o código de cada CNAE secundário");
            if (lista.size() > 50) throw new NegocioException("No máximo 50 CNAEs secundários");
            try { c.setCnaesSecundarios(lista.isEmpty() ? null : json.writeValueAsString(lista)); } catch (Exception e) { throw new IllegalStateException(e); }
        }
        if (d.regimeTributario() != null) c.setRegimeTributario(d.regimeTributario());
        if (d.uf() != null) {
            String uf = d.uf().trim().toUpperCase();
            if (!uf.isEmpty() && !UFS.contains(uf)) throw new NegocioException("UF inválida: " + d.uf());
            c.setUf(uf.isEmpty() ? null : uf);
        }
        if (d.codigoMunicipioIbge() != null) {
            String m = Documentos.digitos(d.codigoMunicipioIbge());
            if (!m.isEmpty() && m.length() != 7) throw new NegocioException("O código do município (IBGE) tem 7 dígitos");
            c.setCodigoMunicipioIbge(m.isEmpty() ? null : m);
        }
        if (d.dataAberturaEmpresa() != null) c.setDataAberturaEmpresa(d.dataAberturaEmpresa());
        if (d.statusContratual() != null) {
            c.setStatusContratual(d.statusContratual());
            if (d.statusContratual() == Enums.StatusContratual.ENCERRADO) c.setAtivo(false);
        }
    }

    public List<DadosFiscais.Cnae> cnaesSecundarios(Cliente c) {
        if (c.getCnaesSecundarios() == null) return List.of();
        try { return json.readValue(c.getCnaesSecundarios(), new TypeReference<List<DadosFiscais.Cnae>>() {}); } catch (Exception e) { return List.of(); }
    }

    private static String texto(String t, int max, String campo) {
        String s = t.trim();
        if (s.length() > max) throw new NegocioException(campo + " deve ter até " + max + " caracteres");
        return s.isEmpty() ? null : s;
    }

    /** CNAE: 7 dígitos (aceita 6201-5/01). */
    private static String cnae(String bruto) {
        if (bruto == null) return null;
        String d = Documentos.digitos(bruto);
        if (d.isEmpty()) return null;
        if (d.length() != 7) throw new NegocioException("O CNAE tem 7 dígitos (ex.: 6201-5/01)");
        return d;
    }
}
