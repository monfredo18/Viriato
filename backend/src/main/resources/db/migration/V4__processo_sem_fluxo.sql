-- Ao apagar um fluxo, os processos já encerrados continuam existindo, sem fluxo nem etapa atual.
-- (O histórico guarda os nomes das etapas em texto, então continua legível.)
ALTER TABLE processo MODIFY COLUMN fluxo_id BIGINT NULL;
ALTER TABLE processo MODIFY COLUMN etapa_atual_id BIGINT NULL;
