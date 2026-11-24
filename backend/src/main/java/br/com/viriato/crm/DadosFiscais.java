package br.com.viriato.crm;

import br.com.viriato.crm.Enums.RegimeTributario;
import br.com.viriato.crm.Enums.StatusContratual;
import java.time.LocalDate;
import java.util.List;

/**
 * Dados fiscais e societários da empresa atendida, todos opcionais. Na alteração, campo ausente (null) não muda;
 * texto em branco apaga; lista vazia apaga os CNAEs secundários.
 */
public record DadosFiscais(String cnpj, String razaoSocial, String nomeFantasia, String inscricaoEstadual, String inscricaoMunicipal,
                           String cnaePrincipal, String cnaePrincipalDescricao, List<Cnae> cnaesSecundarios,
                           RegimeTributario regimeTributario, String uf, String codigoMunicipioIbge, LocalDate dataAberturaEmpresa,
                           StatusContratual statusContratual) {
    public record Cnae(String codigo, String descricao) {}
}
