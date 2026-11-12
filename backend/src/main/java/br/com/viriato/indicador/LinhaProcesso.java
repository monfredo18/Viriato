package br.com.viriato.indicador;

import br.com.viriato.processo.StatusProcesso;
import java.time.Instant;
import java.time.LocalDate;

/** Só o que o painel de indicadores precisa de cada processo (sem carregar a entidade inteira). */
public record LinhaProcesso(Long id, Long setorId, Long responsavelId, StatusProcesso status, LocalDate prazoEtapa,
                            Instant criadoEm, Instant atualizadoEm, Long fluxoId) {}
