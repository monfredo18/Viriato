-- Fase 1: origem (endereço e navegador) nos eventos de acesso e fila persistida das automações (retentativas).
ALTER TABLE auditoria ADD COLUMN ip VARCHAR(64) NULL;
ALTER TABLE auditoria ADD COLUMN user_agent VARCHAR(300) NULL;

-- automacao_execucao passa a ser também a fila: PENDENTE e PROCESSANDO antes de OK ou FALHA.
ALTER TABLE automacao_execucao ADD COLUMN etapa_id BIGINT NULL;
ALTER TABLE automacao_execucao ADD COLUMN usuario_id BIGINT NULL;
ALTER TABLE automacao_execucao ADD COLUMN tentativas INT NOT NULL DEFAULT 0;
ALTER TABLE automacao_execucao ADD COLUMN proxima_tentativa TIMESTAMP NULL;
ALTER TABLE automacao_execucao ADD COLUMN iniciado_em TIMESTAMP NULL;
CREATE INDEX ix_autoexec_fila ON automacao_execucao (status, proxima_tentativa);
