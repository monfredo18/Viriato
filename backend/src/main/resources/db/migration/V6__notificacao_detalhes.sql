-- A notificação guarda, no momento do envio, empresa, entrega e quem fez a ação.
ALTER TABLE notificacao ADD COLUMN empresa_nome VARCHAR(150) NULL;
ALTER TABLE notificacao ADD COLUMN entrega_nome VARCHAR(150) NULL;
ALTER TABLE notificacao ADD COLUMN autor_nome VARCHAR(150) NULL;
