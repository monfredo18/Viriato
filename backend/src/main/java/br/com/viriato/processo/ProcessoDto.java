package br.com.viriato.processo;

import br.com.viriato.fluxo.FluxoService.EtapaDto;
import br.com.viriato.fluxo.RequisitoService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record ProcessoDto(Long id, String numero, String titulo, String descricao,
                          Long clienteId, String clienteNome, Long entregaId, String entregaNome,
                          Long setorId, String setorNome,
                          Long fluxoId, String fluxoNome,
                          Long etapaAtualId, String etapaAtual, boolean etapaFinal,
                          Long responsavelId, String responsavelNome,
                          StatusProcesso status, LocalDate prazoEtapa, boolean atrasado,
                          Map<String, Object> camposExtras, Instant criadoEm, Instant atualizadoEm) {

    public record HistoricoDto(Long id, String acao, String usuarioNome, String deEtapa, String paraEtapa,
                               String comentario, Instant criadoEm) {}

    public record Detalhe(ProcessoDto processo, List<EtapaDto> proximasEtapas, List<HistoricoDto> historico,
                          List<RequisitoService.RequisitoStatusDto> requisitos, Integer etapaOrdem) {}
}
