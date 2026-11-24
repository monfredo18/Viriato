package br.com.viriato.lgpd;

import br.com.viriato.auditoria.AuditoriaService;
import br.com.viriato.cadastro.Cliente;
import br.com.viriato.cadastro.ClienteRepository;
import br.com.viriato.cadastro.Setor;
import br.com.viriato.cadastro.SetorRepository;
import br.com.viriato.comum.Datas;
import br.com.viriato.comum.NaoEncontradoException;
import br.com.viriato.comum.NegocioException;
import br.com.viriato.seguranca.UsuarioLogado;
import br.com.viriato.usuario.FotoService;
import br.com.viriato.usuario.Papel;
import br.com.viriato.usuario.Usuario;
import br.com.viriato.usuario.UsuarioRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Direitos do titular (LGPD), só para administradores: exportar todos os dados pessoais ligados a uma pessoa e
 * anonimizá-la sem quebrar o histórico. Anonimizar troca nome e e-mail por valores neutros, apaga o que só existe por
 * causa da pessoa (foto, chat de ajuda, notificações recebidas, endereço e navegador dos acessos) e inativa a conta, mas
 * mantém processos, histórico e auditoria com o nome neutro, porque são registros do negócio. Toda consulta filtra por empresa.
 */
@Service
public class LgpdService {
    static final int LIMITE = 5000;

    private final JdbcTemplate jdbc;
    private final UsuarioRepository usuarios;
    private final ClienteRepository clientes;
    private final SetorRepository setores;
    private final FotoService fotos;
    private final PasswordEncoder encoder;
    private final AuditoriaService auditoria;
    private final br.com.viriato.ajuda.AjudaService ajuda;

    public LgpdService(JdbcTemplate jdbc, UsuarioRepository usuarios, ClienteRepository clientes, SetorRepository setores,
                       FotoService fotos, PasswordEncoder encoder, AuditoriaService auditoria, br.com.viriato.ajuda.AjudaService ajuda) {
        this.jdbc = jdbc;
        this.usuarios = usuarios;
        this.clientes = clientes;
        this.setores = setores;
        this.fotos = fotos;
        this.encoder = encoder;
        this.auditoria = auditoria;
        this.ajuda = ajuda;
    }

    // ---------- exportação ----------

    /** Tudo o que o sistema guarda sobre a pessoa, em seções (cada seção é um mapa ou uma lista de linhas). */
    @Transactional(readOnly = true)
    public Map<String, Object> exportarUsuario(UsuarioLogado quem, Long id) {
        Usuario u = usuarios.findByIdAndTenantId(id, quem.tenantId()).orElseThrow(() -> new NaoEncontradoException("Funcionário"));
        Long t = quem.tenantId();
        Map<String, Object> cadastro = new LinkedHashMap<>();
        cadastro.put("id", u.getId());
        cadastro.put("nome", u.getNome());
        cadastro.put("email", u.getEmail());
        cadastro.put("papel", u.getPapel().name());
        cadastro.put("setor", u.getSetorId() == null ? null : setores.findByIdAndTenantId(u.getSetorId(), t).map(Setor::getNome).orElse(null));
        cadastro.put("ativo", u.isAtivo());
        cadastro.put("verificacaoEmDuasEtapas", u.isTotpAtivo());
        cadastro.put("temFoto", u.getFotoChave() != null);
        cadastro.put("criadoEm", u.getCriadoEm());
        cadastro.put("anonimizadoEm", u.getAnonimizadoEm());

        Map<String, Object> out = cabecalho("USUARIO", u.getId());
        out.put("cadastro", cadastro);
        out.put("processosSobResponsabilidade", jdbc.queryForList(
                "select numero, titulo, status, prazo_etapa as prazo from processo where tenant_id = ? and responsavel_id = ? order by id desc limit " + LIMITE, t, id));
        out.put("atividadesNosProcessos", jdbc.queryForList(
                "select p.numero as processo, h.acao, h.comentario, h.criado_em as quando from processo_historico h join processo p on p.id = h.processo_id "
                        + "where h.tenant_id = ? and h.usuario_id = ? order by h.id desc limit " + LIMITE, t, id));
        out.put("documentosEnviados", jdbc.queryForList(
                "select d.nome_original as nome, p.numero as processo, d.criado_em as quando from documento d join processo p on p.id = d.processo_id "
                        + "where d.tenant_id = ? and d.enviado_por = ? order by d.id desc limit " + LIMITE, t, id));
        out.put("notificacoesRecebidas", jdbc.queryForList(
                "select titulo, mensagem, criado_em as quando from notificacao where tenant_id = ? and usuario_id = ? order by id desc limit " + LIMITE, t, id));
        out.put("mensagensDeAjuda", jdbc.queryForList(
                "select m.criado_em as quando, m.autor_nome as autor, m.texto from ajuda_mensagem m join ajuda_conversa c on c.id = m.conversa_id "
                        + "where c.tenant_id = ? and (c.usuario_id = ? or m.autor_id = ?) order by m.id limit " + LIMITE, t, id, id));
        out.put("acessosERegistrosDeAuditoria", jdbc.queryForList(
                "select criado_em as quando, acao, detalhe, ip, user_agent as navegador from auditoria where tenant_id = ? and usuario_id = ? order by id desc limit " + LIMITE, t, id));
        auditoria.registrar(t, quem.id(), "LGPD_EXPORTACAO", "Exportação dos dados do funcionário #" + id);
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> exportarCliente(UsuarioLogado quem, Long id) {
        Cliente c = clientes.findByIdAndTenantId(id, quem.tenantId()).orElseThrow(() -> new NaoEncontradoException("Empresa atendida"));
        Map<String, Object> cadastro = new LinkedHashMap<>();
        cadastro.put("id", c.getId());
        cadastro.put("numero", c.getNumero());
        cadastro.put("nome", c.getNome());
        cadastro.put("ativo", c.isAtivo());
        cadastro.put("anonimizadoEm", c.getAnonimizadoEm());
        Map<String, Object> out = cabecalho("EMPRESA_ATENDIDA", c.getId());
        out.put("cadastro", cadastro);
        out.put("processos", jdbc.queryForList(
                "select numero, titulo, status, criado_em as aberto_em from processo where tenant_id = ? and cliente_id = ? order by id desc limit " + LIMITE, quem.tenantId(), id));
        auditoria.registrar(quem.tenantId(), quem.id(), "LGPD_EXPORTACAO", "Exportação dos dados da empresa atendida #" + id);
        return out;
    }

    private static Map<String, Object> cabecalho(String tipo, Long id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("titular", tipo);
        m.put("titularId", id);
        m.put("geradoEm", Instant.now());
        m.put("base", "Lei nº 13.709/2018 (LGPD), art. 18: confirmação e acesso aos dados do titular");
        return m;
    }

    // ---------- anonimização ----------

    /** Anonimiza o funcionário. Não vale para si mesmo nem para o último administrador ativo; não se repete. */
    @Transactional
    public void anonimizarUsuario(UsuarioLogado quem, Long id) {
        Long t = quem.tenantId();
        Usuario u = usuarios.findByIdAndTenantId(id, t).orElseThrow(() -> new NaoEncontradoException("Funcionário"));
        if (u.getAnonimizadoEm() != null) throw new NegocioException("Este titular já foi anonimizado");
        if (u.getId().equals(quem.id())) throw new NegocioException("Você não pode anonimizar a própria conta");
        if (u.getPapel() == Papel.ADMIN && u.isAtivo()
                && usuarios.findByTenantIdAndPapelInAndAtivoTrue(t, List.of(Papel.ADMIN)).size() <= 1) {
            throw new NegocioException("Não é possível anonimizar o único administrador ativo da empresa");
        }
        String antigo = u.getNome();
        String novo = "Anonimizado " + u.getId();

        fotos.remover(id, t);
        ajuda.apagarDe(id, t);
        u.setNome(novo);
        u.setEmail("anonimizado-" + u.getId() + "@anonimizado.invalid");
        u.setAtivo(false);
        u.setSenhaHash(encoder.encode(UUID.randomUUID().toString()));
        u.setSenhaProvisoria(false);
        u.setBloqueado(false);
        u.setTentativasFalhas(0);
        u.setVersaoSessao(u.getVersaoSessao() + 1);   // derruba qualquer sessão aberta
        u.setTotpAtivo(false);
        u.setTotpSegredo(null);
        u.setTotpUltimoPasso(null);
        u.setAnonimizadoEm(Instant.now());
        usuarios.save(u);

        // O nome sai dos campos de texto que o guardaram na hora; processos e histórico continuam, agora com o nome neutro.
        jdbc.update("update auditoria set usuario_nome = ?, ip = null, user_agent = null where tenant_id = ? and usuario_id = ?", novo, t, id);
        jdbc.update("update auditoria set detalhe = replace(detalhe, ?, ?) where tenant_id = ? and locate(?, detalhe) > 0", antigo, novo, t, antigo);
        jdbc.update("update notificacao set titulo = replace(titulo, ?, ?), autor_nome = ? where tenant_id = ? and autor_nome = ?",
                " - " + antigo, " - " + novo, novo, t, antigo);
        jdbc.update("delete from notificacao where tenant_id = ? and usuario_id = ?", t, id);
        jdbc.update("update processo set responsavel_removido_nome = ? where tenant_id = ? and responsavel_removido_nome = ?", novo, t, antigo);
        auditoria.registrar(t, quem.id(), "LGPD_ANONIMIZACAO", "Funcionário #" + id + " anonimizado");
    }

    /** Anonimiza a empresa atendida (útil quando é MEI ou pessoa física): o nome vira neutro nos processos e avisos. */
    @Transactional
    public void anonimizarCliente(UsuarioLogado quem, Long id) {
        Long t = quem.tenantId();
        Cliente c = clientes.findByIdAndTenantId(id, t).orElseThrow(() -> new NaoEncontradoException("Empresa atendida"));
        if (c.getAnonimizadoEm() != null) throw new NegocioException("Este titular já foi anonimizado");
        String antigo = c.getNome();
        String novo = "Empresa anonimizada " + c.getNumero();
        c.setNome(novo);
        c.setAtivo(false);
        c.setAnonimizadoEm(Instant.now());
        clientes.save(c);
        jdbc.update("update processo set titulo = replace(titulo, ?, ?) where tenant_id = ? and cliente_id = ?", antigo, novo, t, id);
        jdbc.update("update notificacao set empresa_nome = ?, titulo = replace(titulo, ?, ?), mensagem = replace(mensagem, ?, ?) where tenant_id = ? and empresa_nome = ?",
                novo, antigo, novo, antigo, novo, t, antigo);
        auditoria.registrar(t, quem.id(), "LGPD_ANONIMIZACAO", "Empresa atendida #" + id + " anonimizada");
    }

    /** Só para o nome do arquivo de exportação. */
    public static String carimbo() { return Datas.DATA.format(Datas.hoje()); }
}
