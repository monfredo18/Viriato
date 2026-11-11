-- O processo passa a registrar para quem é feito (cliente), o que será entregue e qual setor faz.
-- Colunas opcionais no banco para não invalidar processos criados antes desta versão;
-- a API exige os três campos em processos novos.
ALTER TABLE processo ADD COLUMN cliente_id BIGINT NULL;
ALTER TABLE processo ADD COLUMN entrega_id BIGINT NULL;
ALTER TABLE processo ADD COLUMN setor_id BIGINT NULL;
ALTER TABLE processo ADD CONSTRAINT fk_processo_cliente FOREIGN KEY (cliente_id) REFERENCES cliente (id);
ALTER TABLE processo ADD CONSTRAINT fk_processo_entrega FOREIGN KEY (entrega_id) REFERENCES entrega (id);
ALTER TABLE processo ADD CONSTRAINT fk_processo_setor FOREIGN KEY (setor_id) REFERENCES setor (id);
CREATE INDEX ix_processo_cliente ON processo (tenant_id, cliente_id);
CREATE INDEX ix_processo_setor ON processo (tenant_id, setor_id);
