package br.com.viriato.relatorio;

import br.com.viriato.cadastro.Cliente;
import br.com.viriato.cadastro.ClienteRepository;
import br.com.viriato.cadastro.Entrega;
import br.com.viriato.cadastro.EntregaRepository;
import br.com.viriato.cadastro.Setor;
import br.com.viriato.cadastro.SetorRepository;
import br.com.viriato.comum.Datas;
import br.com.viriato.comum.NegocioException;
import br.com.viriato.processo.Processo;
import br.com.viriato.processo.ProcessoRepository;
import br.com.viriato.processo.StatusProcesso;
import br.com.viriato.seguranca.UsuarioLogado;
import br.com.viriato.tenant.TenantRepository;
import br.com.viriato.usuario.Usuario;
import br.com.viriato.usuario.UsuarioRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Relatórios de processos. A abrangência vem SEMPRE do papel de quem pede (nunca do que a tela envia):
 * administrador vê todos os setores; gestor, só o próprio setor; usuário, só os processos em que é responsável.
 */
@Service
public class RelatorioService {
    public static final int LIMITE = 10_000;
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public enum Abrangencia { TODOS_SETORES, SEU_SETOR, PROPRIOS }

    public record Escopo(Abrangencia abrangencia, Long setorId, String setorNome, Long usuarioId) {}

    public record Filtro(Long usuarioId, Long setorId, Long clienteId, LocalDate de, LocalDate ate,
                         Referencia referencia, Set<StatusProcesso> situacoes) {}

    public record Resumo(int total, int emAndamento, int atrasados, int concluidos, int cancelados) {}

    public record Linha(String numero, String empresa, String setor, String entrega, String responsavel,
                        LocalDate abertura, LocalDate encerramento, String situacao, boolean atrasado) {}

    public record Dados(String nomeDoSistema, String geradoPor, Instant geradoEm, String abrangencia,
                        List<String> filtros, Resumo resumo, List<Linha> linhas) {}

    private final ProcessoRepository processos;
    private final ClienteRepository clientes;
    private final SetorRepository setores;
    private final EntregaRepository entregas;
    private final UsuarioRepository usuarios;
    private final TenantRepository tenants;

    public RelatorioService(ProcessoRepository processos, ClienteRepository clientes, SetorRepository setores,
                            EntregaRepository entregas, UsuarioRepository usuarios, TenantRepository tenants) {
        this.processos = processos;
        this.clientes = clientes;
        this.setores = setores;
        this.entregas = entregas;
        this.usuarios = usuarios;
        this.tenants = tenants;
    }

    /** O que o usuário logado pode abranger em um relatório. */
    @Transactional(readOnly = true)
    public Escopo escopo(UsuarioLogado quem) {
        return switch (quem.papel()) {
            case ADMIN -> new Escopo(Abrangencia.TODOS_SETORES, null, null, null);
            case GESTOR -> {
                Usuario u = usuarios.findByIdAndTenantId(quem.id(), quem.tenantId()).orElseThrow();
                Setor s = u.getSetorId() == null ? null : setores.findByIdAndTenantId(u.getSetorId(), quem.tenantId()).orElse(null);
                yield new Escopo(Abrangencia.SEU_SETOR, s == null ? null : s.getId(), s == null ? null : s.getNome(), null);
            }
            case USUARIO -> new Escopo(Abrangencia.PROPRIOS, null, null, quem.id());
        };
    }

    @Transactional(readOnly = true)
    public Dados montar(UsuarioLogado quem, Filtro f) {
        Long tid = quem.tenantId();
        Escopo escopo = escopo(quem);
        validar(escopo, f);

        List<Processo> ps = processos.findAll(especificacao(tid, escopo, f), PageRequest.of(0, LIMITE + 1, Sort.by(Sort.Direction.DESC, "id"))).getContent();
        if (ps.size() > LIMITE) {
            throw new NegocioException("O relatório teria mais de " + LIMITE + " processos. Refine os filtros (período, setor, empresa) e tente de novo.");
        }

        Map<Long, Cliente> cl = porId(clientes.findAllById(ids(ps, Processo::getClienteId)), Cliente::getId);
        Map<Long, Setor> st = porId(setores.findAllById(ids(ps, Processo::getSetorId)), Setor::getId);
        Map<Long, Entrega> en = porId(entregas.findAllById(ids(ps, Processo::getEntregaId)), Entrega::getId);
        Map<Long, Usuario> us = porId(usuarios.findAllById(ids(ps, Processo::getResponsavelId)), Usuario::getId);

        LocalDate hoje = Datas.hoje();
        List<Linha> linhas = new ArrayList<>();
        int abertos = 0, atrasados = 0, concluidos = 0, cancelados = 0;
        for (Processo p : ps) {
            boolean atrasado = p.getStatus() == StatusProcesso.ABERTO && p.getPrazoEtapa() != null && p.getPrazoEtapa().isBefore(hoje);
            switch (p.getStatus()) {
                case ABERTO -> { abertos++; if (atrasado) atrasados++; }
                case CONCLUIDO -> concluidos++;
                case CANCELADO -> cancelados++;
            }
            boolean encerrado = p.getStatus() != StatusProcesso.ABERTO;
            linhas.add(new Linha(
                    p.getNumero(),
                    nome(cl.get(p.getClienteId()), Cliente::getNome),
                    nome(st.get(p.getSetorId()), Setor::getNome),
                    nome(en.get(p.getEntregaId()), Entrega::getNome),
                    p.getResponsavelId() == null && p.getResponsavelRemovidoNome() != null ? p.getResponsavelRemovidoNome() + " (removido)"
                            : nome(us.get(p.getResponsavelId()), Usuario::getNome),
                    p.getCriadoEm().atZone(Datas.ZONA).toLocalDate(),
                    encerrado ? p.getAtualizadoEm().atZone(Datas.ZONA).toLocalDate() : null,
                    rotulo(p.getStatus(), atrasado), atrasado));
        }

        Usuario quemE = usuarios.findByIdAndTenantId(quem.id(), tid).orElseThrow();
        String sistema = tenants.findById(tid).map(t -> t.getNome()).orElse("");
        return new Dados(sistema, quemE.getNome(), Instant.now(), descreverAbrangencia(escopo, quemE),
                descreverFiltros(tid, f), new Resumo(ps.size(), abertos, atrasados, concluidos, cancelados), linhas);
    }

    // ---------------------------------------------------------------- regras

    private void validar(Escopo e, Filtro f) {
        if (f.de() != null && f.ate() != null && f.de().isAfter(f.ate())) {
            throw new NegocioException("A data inicial não pode ser depois da data final.");
        }
        if (e.abrangencia() == Abrangencia.SEU_SETOR) {
            if (e.setorId() == null) {
                throw new NegocioException("Seu usuário não tem setor definido. Peça ao administrador para definir o seu setor antes de gerar relatórios.");
            }
            if (f.setorId() != null && !f.setorId().equals(e.setorId())) {
                throw new AccessDeniedException("Gestor só pode gerar relatórios do próprio setor");
            }
        }
        if (e.abrangencia() == Abrangencia.PROPRIOS && f.usuarioId() != null && !f.usuarioId().equals(e.usuarioId())) {
            throw new AccessDeniedException("Você só pode gerar relatórios dos seus próprios processos");
        }
    }

    private Specification<Processo> especificacao(Long tid, Escopo e, Filtro f) {
        return (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("tenantId"), tid));
            // restrição do papel (não depende do que a tela enviou)
            if (e.abrangencia() == Abrangencia.SEU_SETOR) ps.add(cb.equal(root.get("setorId"), e.setorId()));
            if (e.abrangencia() == Abrangencia.PROPRIOS) ps.add(cb.equal(root.get("responsavelId"), e.usuarioId()));
            // filtros pedidos
            if (f.usuarioId() != null) ps.add(cb.equal(root.get("responsavelId"), f.usuarioId()));
            if (f.setorId() != null) ps.add(cb.equal(root.get("setorId"), f.setorId()));
            if (f.clienteId() != null) ps.add(cb.equal(root.get("clienteId"), f.clienteId()));
            if (f.situacoes() != null && !f.situacoes().isEmpty()) ps.add(root.get("status").in(f.situacoes()));
            if (f.de() != null || f.ate() != null) {
                boolean porEncerramento = f.referencia() == Referencia.ENCERRAMENTO;
                String campo = porEncerramento ? "atualizadoEm" : "criadoEm";
                if (porEncerramento) ps.add(cb.notEqual(root.get("status"), StatusProcesso.ABERTO));
                if (f.de() != null) ps.add(cb.greaterThanOrEqualTo(root.<Instant>get(campo), f.de().atStartOfDay(Datas.ZONA).toInstant()));
                if (f.ate() != null) ps.add(cb.lessThan(root.<Instant>get(campo), f.ate().plusDays(1).atStartOfDay(Datas.ZONA).toInstant()));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    // ---------------------------------------------------------------- textos

    private String descreverAbrangencia(Escopo e, Usuario quem) {
        return switch (e.abrangencia()) {
            case TODOS_SETORES -> "Todos os setores";
            case SEU_SETOR -> "Setor " + e.setorNome();
            case PROPRIOS -> "Processos de " + quem.getNome();
        };
    }

    private List<String> descreverFiltros(Long tid, Filtro f) {
        List<String> l = new ArrayList<>();
        if (f.setorId() != null) l.add("Setor: " + setores.findByIdAndTenantId(f.setorId(), tid).map(Setor::getNome).orElse("(inexistente)"));
        if (f.clienteId() != null) {
            l.add("Empresa: " + clientes.findByIdAndTenantId(f.clienteId(), tid).map(c -> c.getNumero() + " — " + c.getNome()).orElse("(inexistente)"));
        }
        if (f.usuarioId() != null) l.add("Responsável: " + usuarios.findByIdAndTenantId(f.usuarioId(), tid).map(Usuario::getNome).orElse("(inexistente)"));
        if (f.de() != null || f.ate() != null) {
            String ref = f.referencia() == Referencia.ENCERRAMENTO ? "encerramento" : "abertura";
            String de = f.de() == null ? "início" : f.de().format(DATA);
            String ate = f.ate() == null ? "hoje" : f.ate().format(DATA);
            l.add("Período (" + ref + "): " + de + " a " + ate);
        }
        if (f.situacoes() != null && !f.situacoes().isEmpty() && f.situacoes().size() < StatusProcesso.values().length) {
            l.add("Situação: " + java.util.Arrays.stream(StatusProcesso.values()).filter(f.situacoes()::contains)
                    .map(s -> switch (s) { case ABERTO -> "Em andamento"; case CONCLUIDO -> "Concluídos"; case CANCELADO -> "Cancelados"; })
                    .collect(Collectors.joining(", ")));
        }
        return l;
    }

    private static String rotulo(StatusProcesso s, boolean atrasado) {
        return switch (s) {
            case ABERTO -> atrasado ? "Atrasado" : "Em andamento";
            case CONCLUIDO -> "Concluído";
            case CANCELADO -> "Cancelado";
        };
    }

    // ---------------------------------------------------------------- auxiliares

    private static <T> String nome(T t, java.util.function.Function<T, String> f) { return t == null ? "—" : f.apply(t); }

    private static List<Long> ids(List<Processo> ps, java.util.function.Function<Processo, Long> f) {
        return ps.stream().map(f).filter(Objects::nonNull).distinct().toList();
    }

    private static <T> Map<Long, T> porId(List<T> l, java.util.function.Function<T, Long> id) {
        return l.stream().collect(Collectors.toMap(id, java.util.function.Function.identity()));
    }
}
