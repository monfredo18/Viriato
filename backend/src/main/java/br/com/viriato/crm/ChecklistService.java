package br.com.viriato.crm;

import br.com.viriato.auditoria.AuditoriaService;
import br.com.viriato.cadastro.Cliente;
import br.com.viriato.cadastro.ClienteRepository;
import br.com.viriato.comum.NaoEncontradoException;
import br.com.viriato.crm.Enums.StatusContratual;
import br.com.viriato.crm.Enums.TipoChecklist;
import br.com.viriato.seguranca.UsuarioLogado;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checklists automáticos: onboarding quando a empresa entra (status ONBOARDING) e offboarding quando começa o encerramento
 * (status OFFBOARDING). Concluir o último item do onboarding ativa a empresa; o do offboarding a encerra.
 */
@Service
public class ChecklistService {
    static final List<String> ONBOARDING = List.of(
            "Coletar o contrato social e as alterações contratuais",
            "Conferir CNPJ, regime tributário e CNAEs",
            "Cadastrar os sócios e os contatos por área",
            "Cadastrar o certificado digital (A1 ou A3)",
            "Cadastrar as procurações eletrônicas (e-CAC, SEFAZ, Prefeitura, FGTS)",
            "Definir quem cuida do fiscal, do contábil e da folha",
            "Combinar prazos e canais de envio de documentos com o cliente");
    static final List<String> OFFBOARDING = List.of(
            "Emitir as últimas guias e obrigações em aberto",
            "Concluir ou cancelar os processos em andamento",
            "Entregar ao cliente os documentos e arquivos",
            "Revogar as procurações eletrônicas",
            "Remover o certificado digital do cofre",
            "Registrar o distrato e a data de encerramento");

    public record ItemDto(Long id, TipoChecklist tipo, int ordem, String descricao, boolean concluido, Long concluidoPor, Instant concluidoEm) {}

    private final ClienteChecklistItemRepository itens;
    private final ClienteRepository clientes;
    private final AuditoriaService auditoria;

    public ChecklistService(ClienteChecklistItemRepository itens, ClienteRepository clientes, AuditoriaService auditoria) {
        this.itens = itens;
        this.clientes = clientes;
        this.auditoria = auditoria;
    }

    /** Chamado depois de salvar a empresa; {@code antes} é nulo quando a empresa acabou de ser criada. */
    @Transactional
    public void aoMudarStatus(Cliente c, StatusContratual antes) {
        StatusContratual agora = c.getStatusContratual();
        if (agora == antes) return;
        if (agora == StatusContratual.ONBOARDING) gerar(c, TipoChecklist.ONBOARDING, ONBOARDING);
        if (agora == StatusContratual.OFFBOARDING) gerar(c, TipoChecklist.OFFBOARDING, OFFBOARDING);
    }

    private void gerar(Cliente c, TipoChecklist tipo, List<String> modelo) {
        if (itens.existsByClienteIdAndTipo(c.getId(), tipo)) return;
        for (int i = 0; i < modelo.size(); i++) {
            ClienteChecklistItem it = new ClienteChecklistItem();
            it.setTenantId(c.getTenantId());
            it.setClienteId(c.getId());
            it.setTipo(tipo);
            it.setOrdem(i + 1);
            it.setDescricao(modelo.get(i));
            itens.save(it);
        }
    }

    @Transactional(readOnly = true)
    public List<ItemDto> listar(Long tenantId, Long clienteId) {
        clientes.findByIdAndTenantId(clienteId, tenantId).orElseThrow(() -> new NaoEncontradoException("Empresa"));
        return itens.findByClienteIdAndTenantIdOrderByTipoAscOrdemAsc(clienteId, tenantId).stream().map(ChecklistService::dto).toList();
    }

    @Transactional
    public ItemDto marcar(UsuarioLogado quem, Long clienteId, Long itemId, boolean concluido) {
        Long t = quem.tenantId();
        Cliente c = clientes.findByIdAndTenantId(clienteId, t).orElseThrow(() -> new NaoEncontradoException("Empresa"));
        ClienteChecklistItem it = itens.findByIdAndClienteIdAndTenantId(itemId, clienteId, t).orElseThrow(() -> new NaoEncontradoException("Item do checklist"));
        it.setConcluido(concluido);
        it.setConcluidoPor(concluido ? quem.id() : null);
        it.setConcluidoEm(concluido ? Instant.now() : null);
        itens.save(it);
        boolean tudo = concluido && itens.findByClienteIdAndTenantIdAndTipo(clienteId, t, it.getTipo()).stream().allMatch(ClienteChecklistItem::isConcluido);
        if (tudo && it.getTipo() == TipoChecklist.ONBOARDING && c.getStatusContratual() == StatusContratual.ONBOARDING) {
            c.setStatusContratual(StatusContratual.ATIVO);
            clientes.save(c);
            auditoria.registrar(t, quem.id(), "EMPRESA_ATIVADA", "Onboarding concluído: " + c.getNome());
        }
        if (tudo && it.getTipo() == TipoChecklist.OFFBOARDING && c.getStatusContratual() == StatusContratual.OFFBOARDING) {
            c.setStatusContratual(StatusContratual.ENCERRADO);
            c.setAtivo(false);
            clientes.save(c);
            auditoria.registrar(t, quem.id(), "EMPRESA_ENCERRADA", "Offboarding concluído: " + c.getNome());
        }
        return dto(it);
    }

    static ItemDto dto(ClienteChecklistItem i) {
        return new ItemDto(i.getId(), i.getTipo(), i.getOrdem(), i.getDescricao(), i.isConcluido(), i.getConcluidoPor(), i.getConcluidoEm());
    }
}
