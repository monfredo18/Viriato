package br.com.viriato.crm;

import br.com.viriato.cadastro.ClienteRepository;
import br.com.viriato.comum.NaoEncontradoException;
import br.com.viriato.comum.NegocioException;
import br.com.viriato.crm.Enums.AreaContato;
import br.com.viriato.seguranca.Cifra;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sócios (CPF cifrado, só a máscara sai pela API) e contatos operacionais por área da empresa atendida. */
@Service
public class SocioContatoService {
    public record SocioDto(Long id, String nome, String cpf, BigDecimal percentual, boolean responsavelLegal, String email, String telefone) {}
    public record ContatoDto(Long id, String nome, AreaContato area, String email, String telefone, boolean recebeGuias, boolean recebeCobrancas) {}

    /** {@code cpf} em texto puro só entra; nunca volta. Na alteração, campo nulo não muda. */
    public record SocioReq(String nome, String cpf, BigDecimal percentual, Boolean responsavelLegal, String email, String telefone) {}
    public record ContatoReq(String nome, AreaContato area, String email, String telefone, Boolean recebeGuias, Boolean recebeCobrancas) {}

    private final ClienteSocioRepository socios;
    private final ClienteContatoRepository contatos;
    private final ClienteRepository clientes;
    private final Cifra cifra;

    public SocioContatoService(ClienteSocioRepository socios, ClienteContatoRepository contatos, ClienteRepository clientes, Cifra cifra) {
        this.socios = socios;
        this.contatos = contatos;
        this.clientes = clientes;
        this.cifra = cifra;
    }

    private void empresa(Long t, Long id) { clientes.findByIdAndTenantId(id, t).orElseThrow(() -> new NaoEncontradoException("Empresa")); }

    // ---------- sócios ----------
    @Transactional(readOnly = true)
    public List<SocioDto> socios(Long t, Long clienteId) {
        return socios.findByClienteIdAndTenantIdOrderByNome(clienteId, t).stream().map(SocioContatoService::dto).toList();
    }

    @Transactional
    public SocioDto criarSocio(Long t, Long clienteId, SocioReq r) {
        empresa(t, clienteId);
        if (r.nome() == null || r.nome().isBlank()) throw new NegocioException("Informe o nome do sócio");
        ClienteSocio s = new ClienteSocio();
        s.setTenantId(t);
        s.setClienteId(clienteId);
        aplicar(s, r, true);
        return dto(socios.save(s));
    }

    @Transactional
    public SocioDto alterarSocio(Long t, Long clienteId, Long id, SocioReq r) {
        ClienteSocio s = socios.findByIdAndClienteIdAndTenantId(id, clienteId, t).orElseThrow(() -> new NaoEncontradoException("Sócio"));
        aplicar(s, r, false);
        return dto(socios.save(s));
    }

    @Transactional
    public void apagarSocio(Long t, Long clienteId, Long id) {
        socios.delete(socios.findByIdAndClienteIdAndTenantId(id, clienteId, t).orElseThrow(() -> new NaoEncontradoException("Sócio")));
    }

    private void aplicar(ClienteSocio s, SocioReq r, boolean novo) {
        if (r.nome() != null) {
            if (r.nome().isBlank() || r.nome().length() > 150) throw new NegocioException("O nome do sócio deve ter de 1 a 150 caracteres");
            s.setNome(r.nome().trim());
        }
        if (r.cpf() != null) {
            String d = Documentos.digitos(r.cpf());
            if (d.isEmpty()) { s.setCpfCifrado(null); s.setCpfMascara(null); }
            else {
                if (!Documentos.cpfValido(d)) throw new NegocioException("CPF inválido (confira os dígitos verificadores)");
                s.setCpfCifrado(cifra.cifrar(d));
                s.setCpfMascara(Documentos.mascararCpf(d));
            }
        }
        if (r.percentual() != null) {
            if (r.percentual().signum() <= 0 || r.percentual().compareTo(new BigDecimal("100")) > 0) throw new NegocioException("A participação deve ficar entre 0,01% e 100%");
            BigDecimal outros = socios.findByClienteIdAndTenantIdOrderByNome(s.getClienteId(), s.getTenantId()).stream()
                    .filter(o -> !o.getId().equals(s.getId())).map(o -> o.getPercentual() == null ? BigDecimal.ZERO : o.getPercentual())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (outros.add(r.percentual()).compareTo(new BigDecimal("100")) > 0) throw new NegocioException("A soma da participação dos sócios passaria de 100%");
            s.setPercentual(r.percentual());
        }
        if (r.responsavelLegal() != null) s.setResponsavelLegal(r.responsavelLegal());
        if (r.email() != null) s.setEmail(contato(r.email(), 150, "E-mail"));
        if (r.telefone() != null) s.setTelefone(contato(r.telefone(), 30, "Telefone"));
    }

    // ---------- contatos ----------
    @Transactional(readOnly = true)
    public List<ContatoDto> contatos(Long t, Long clienteId) {
        return contatos.findByClienteIdAndTenantIdOrderByNome(clienteId, t).stream().map(SocioContatoService::dto).toList();
    }

    @Transactional
    public ContatoDto criarContato(Long t, Long clienteId, ContatoReq r) {
        empresa(t, clienteId);
        if (r.nome() == null || r.nome().isBlank()) throw new NegocioException("Informe o nome do contato");
        if (r.area() == null) throw new NegocioException("Escolha a área do contato (Fiscal, Contábil, DP ou Financeiro)");
        ClienteContato c = new ClienteContato();
        c.setTenantId(t);
        c.setClienteId(clienteId);
        aplicar(c, r);
        return dto(contatos.save(c));
    }

    @Transactional
    public ContatoDto alterarContato(Long t, Long clienteId, Long id, ContatoReq r) {
        ClienteContato c = contatos.findByIdAndClienteIdAndTenantId(id, clienteId, t).orElseThrow(() -> new NaoEncontradoException("Contato"));
        aplicar(c, r);
        return dto(contatos.save(c));
    }

    @Transactional
    public void apagarContato(Long t, Long clienteId, Long id) {
        contatos.delete(contatos.findByIdAndClienteIdAndTenantId(id, clienteId, t).orElseThrow(() -> new NaoEncontradoException("Contato")));
    }

    private void aplicar(ClienteContato c, ContatoReq r) {
        if (r.nome() != null) {
            if (r.nome().isBlank() || r.nome().length() > 150) throw new NegocioException("O nome do contato deve ter de 1 a 150 caracteres");
            c.setNome(r.nome().trim());
        }
        if (r.area() != null) c.setArea(r.area());
        if (r.email() != null) c.setEmail(contato(r.email(), 150, "E-mail"));
        if (r.telefone() != null) c.setTelefone(contato(r.telefone(), 30, "Telefone"));
        if (r.recebeGuias() != null) c.setRecebeGuias(r.recebeGuias());
        if (r.recebeCobrancas() != null) c.setRecebeCobrancas(r.recebeCobrancas());
    }

    private static String contato(String v, int max, String campo) {
        String s = v.trim();
        if (s.length() > max) throw new NegocioException(campo + " deve ter até " + max + " caracteres");
        if (campo.equals("E-mail") && !s.isEmpty() && !s.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) throw new NegocioException("E-mail inválido");
        return s.isEmpty() ? null : s;
    }

    static SocioDto dto(ClienteSocio s) { return new SocioDto(s.getId(), s.getNome(), s.getCpfMascara(), s.getPercentual(), s.isResponsavelLegal(), s.getEmail(), s.getTelefone()); }
    static ContatoDto dto(ClienteContato c) { return new ContatoDto(c.getId(), c.getNome(), c.getArea(), c.getEmail(), c.getTelefone(), c.isRecebeGuias(), c.isRecebeCobrancas()); }
}
