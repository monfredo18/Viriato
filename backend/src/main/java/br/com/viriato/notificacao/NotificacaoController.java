package br.com.viriato.notificacao;

import br.com.viriato.comum.NaoEncontradoException;
import br.com.viriato.seguranca.Contexto;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notificacoes")
public class NotificacaoController {
    private final NotificacaoRepository repo;

    public NotificacaoController(NotificacaoRepository repo) { this.repo = repo; }

    public record NotificacaoDto(Long id, String titulo, String mensagem, String empresaNome, String entregaNome, String autorNome,
                                 Long processoId, boolean lida, Instant criadoEm) {
        static NotificacaoDto de(Notificacao n) {
            return new NotificacaoDto(n.getId(), n.getTitulo(), n.getMensagem(), n.getEmpresaNome(), n.getEntregaNome(), n.getAutorNome(),
                    n.getProcessoId(), n.isLida(), n.getCriadoEm());
        }
    }

    @GetMapping
    @Transactional(readOnly = true)
    public org.springframework.http.ResponseEntity<List<NotificacaoDto>> listar(@RequestParam(required = false) Integer pagina, @RequestParam(required = false) Integer tamanho) {
        var u = Contexto.atual();
        int t = br.com.viriato.comum.Paginacao.tamanho(tamanho, 50, 200), p = br.com.viriato.comum.Paginacao.pagina(pagina);
        List<NotificacaoDto> itens = repo.findByUsuarioIdAndTenantIdOrderByIdDesc(u.id(), u.tenantId(), PageRequest.of(p, t))
                .stream().map(NotificacaoDto::de).toList();
        return br.com.viriato.comum.Paginacao.resposta(itens, repo.countByUsuarioIdAndTenantId(u.id(), u.tenantId()), p, t);
    }

    @GetMapping("/nao-lidas")
    public Map<String, Long> naoLidas() {
        var u = Contexto.atual();
        return Map.of("total", repo.countByUsuarioIdAndTenantIdAndLidaFalse(u.id(), u.tenantId()));
    }

    @PostMapping("/{id}/ler")
    @Transactional
    public NotificacaoDto ler(@PathVariable Long id) {
        var u = Contexto.atual();
        Notificacao n = repo.findByIdAndUsuarioIdAndTenantId(id, u.id(), u.tenantId())
                .orElseThrow(() -> new NaoEncontradoException("Notificação"));
        n.setLida(true);
        return NotificacaoDto.de(repo.save(n));
    }

    @PostMapping("/ler-todas")
    @Transactional
    public Map<String, Integer> lerTodas() {
        var u = Contexto.atual();
        return Map.of("atualizadas", repo.marcarTodasLidas(u.id(), u.tenantId()));
    }
}
