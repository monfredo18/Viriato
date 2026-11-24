package br.com.viriato.processo;

import br.com.viriato.cadastro.CadastroService;
import br.com.viriato.comum.NaoEncontradoException;
import br.com.viriato.comum.NegocioException;
import br.com.viriato.seguranca.UsuarioLogado;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Campos extras por entrega (texto, número, data, moeda e seleção), definidos por administrador ou gestor e preenchidos
 * no detalhe do processo. Os valores ficam no JSON {@code campos_extras} do processo, já validados pelo tipo.
 * Obrigatórios só travam a conclusão do processo.
 */
@Service
public class CampoExtraService {
    public record CampoDto(Long id, String chave, String rotulo, EntregaCampo.Tipo tipo, boolean obrigatorio, List<String> opcoes, int ordem) {}
    public record CampoReq(String chave, String rotulo, EntregaCampo.Tipo tipo, boolean obrigatorio, List<String> opcoes) {}

    private final EntregaCampoRepository repo;
    private final CadastroService cadastros;
    private final ObjectMapper json;

    public CampoExtraService(EntregaCampoRepository repo, CadastroService cadastros, ObjectMapper json) {
        this.repo = repo;
        this.cadastros = cadastros;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public List<CampoDto> daEntrega(UsuarioLogado quem, Long entregaId) {
        if (cadastros.listarEntregas(quem).stream().noneMatch(e -> e.id().equals(entregaId))) throw new NaoEncontradoException("Entrega");
        return esquema(entregaId, quem.tenantId()).stream().map(this::dto).toList();
    }

    /** Substitui a lista de campos da entrega (administrador, ou gestor da entrega do próprio setor). */
    @Transactional
    public List<CampoDto> definir(UsuarioLogado quem, Long entregaId, List<CampoReq> campos) {
        cadastros.entregaQueEleMexe(quem, entregaId);
        if (campos.size() > 30) throw new NegocioException("No máximo 30 campos por entrega");
        Set<String> chaves = new LinkedHashSet<>();
        List<EntregaCampo> novos = new ArrayList<>();
        int ordem = 0;
        for (CampoReq r : campos) {
            if (r.rotulo() == null || r.rotulo().isBlank() || r.rotulo().length() > 80) throw new NegocioException("O nome de cada campo deve ter de 1 a 80 caracteres");
            if (r.tipo() == null) throw new NegocioException("Escolha o tipo do campo \"" + r.rotulo() + "\"");
            String chave = (r.chave() == null || r.chave().isBlank() ? r.rotulo() : r.chave()).trim().toLowerCase()
                    .replaceAll("[áàâã]", "a").replaceAll("[éê]", "e").replace("í", "i").replaceAll("[óôõ]", "o").replaceAll("[úü]", "u").replace("ç", "c")
                    .replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
            if (chave.isEmpty() || chave.length() > 40) throw new NegocioException("Nome de campo inválido: " + r.rotulo());
            if (!chaves.add(chave)) throw new NegocioException("Há dois campos com o mesmo nome: " + r.rotulo());
            List<String> opcoes = r.opcoes() == null ? List.of() : r.opcoes().stream().map(String::trim).filter(o -> !o.isEmpty()).distinct().toList();
            if (r.tipo() == EntregaCampo.Tipo.SELECAO && opcoes.size() < 2) throw new NegocioException("O campo de seleção \"" + r.rotulo() + "\" precisa de ao menos 2 opções");
            EntregaCampo c = new EntregaCampo();
            c.setTenantId(quem.tenantId());
            c.setEntregaId(entregaId);
            c.setChave(chave);
            c.setRotulo(r.rotulo().trim());
            c.setTipo(r.tipo());
            c.setObrigatorio(r.obrigatorio());
            try { c.setOpcoes(r.tipo() == EntregaCampo.Tipo.SELECAO ? json.writeValueAsString(opcoes) : null); } catch (Exception e) { throw new IllegalStateException(e); }
            c.setOrdem(ordem++);
            novos.add(c);
        }
        repo.deleteByEntregaIdAndTenantId(entregaId, quem.tenantId());
        repo.flush();
        repo.saveAll(novos);
        return novos.stream().map(this::dto).toList();
    }

    public List<EntregaCampo> esquema(Long entregaId, Long tenantId) {
        return entregaId == null ? List.of() : repo.findByEntregaIdAndTenantIdOrderByOrdemAscIdAsc(entregaId, tenantId);
    }

    /**
     * Valida e normaliza valores pelo esquema da entrega. Sem esquema, vale o JSON livre de antes. Valor vazio apaga o campo.
     * Devolve só as chaves enviadas (nulo = apagar).
     */
    public Map<String, Object> normalizar(List<EntregaCampo> esquema, Map<String, Object> valores) {
        if (valores == null || valores.isEmpty()) return new LinkedHashMap<>();
        if (esquema.isEmpty()) return new LinkedHashMap<>(valores);
        Map<String, EntregaCampo> porChave = esquema.stream().collect(Collectors.toMap(EntregaCampo::getChave, c -> c));
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : valores.entrySet()) {
            EntregaCampo c = porChave.get(e.getKey());
            if (c == null) throw new NegocioException("Campo desconhecido para esta entrega: " + e.getKey());
            out.put(c.getChave(), converter(c, e.getValue()));
        }
        return out;
    }

    private Object converter(EntregaCampo c, Object v) {
        if (v == null || (v instanceof String s && s.isBlank())) return null;
        try {
            return switch (c.getTipo()) {
                case TEXTO -> {
                    String t = String.valueOf(v).trim();
                    if (t.length() > 500) throw new NegocioException("\"" + c.getRotulo() + "\" aceita até 500 caracteres");
                    yield t;
                }
                case NUMERO -> numero(v);
                case MOEDA -> numero(v).setScale(2, RoundingMode.HALF_UP);
                case DATA -> LocalDate.parse(String.valueOf(v).trim()).toString();
                case SELECAO -> {
                    String t = String.valueOf(v).trim();
                    List<String> ops = json.readValue(c.getOpcoes(), new TypeReference<List<String>>() {});
                    if (!ops.contains(t)) throw new NegocioException("\"" + c.getRotulo() + "\": escolha uma das opções (" + String.join(", ", ops) + ")");
                    yield t;
                }
            };
        } catch (NegocioException e) {
            throw e;
        } catch (Exception e) {
            throw new NegocioException("Valor inválido em \"" + c.getRotulo() + "\" (" + rotuloTipo(c.getTipo()) + ")");
        }
    }

    private static BigDecimal numero(Object v) {
        if (v instanceof Number n) return new BigDecimal(n.toString());
        return new BigDecimal(String.valueOf(v).trim().replace(".", "").replace(",", ".").replaceAll("[^0-9.\\-]", ""));
    }

    private static String rotuloTipo(EntregaCampo.Tipo t) { return switch (t) { case TEXTO -> "texto"; case NUMERO -> "número"; case DATA -> "data AAAA-MM-DD"; case MOEDA -> "valor em reais"; case SELECAO -> "seleção"; }; }

    /** Obrigatórios precisam estar preenchidos para concluir o processo. */
    public void exigirObrigatorios(Long entregaId, Long tenantId, Map<String, Object> atuais) {
        List<String> faltam = esquema(entregaId, tenantId).stream().filter(EntregaCampo::isObrigatorio)
                .filter(c -> atuais == null || atuais.get(c.getChave()) == null).map(EntregaCampo::getRotulo).toList();
        if (!faltam.isEmpty()) throw new NegocioException("Preencha os campos obrigatórios antes de concluir: " + String.join(", ", faltam));
    }

    public List<CampoDto> esquemaDto(Long entregaId, Long tenantId) { return esquema(entregaId, tenantId).stream().map(this::dto).toList(); }

    private CampoDto dto(EntregaCampo c) {
        List<String> ops = List.of();
        if (c.getOpcoes() != null) { try { ops = json.readValue(c.getOpcoes(), new TypeReference<List<String>>() {}); } catch (Exception ignored) { /* sem opções */ } }
        return new CampoDto(c.getId(), c.getChave(), c.getRotulo(), c.getTipo(), c.isObrigatorio(), ops, c.getOrdem());
    }
}
