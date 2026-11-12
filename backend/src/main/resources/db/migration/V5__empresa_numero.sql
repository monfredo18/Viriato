-- Empresa atendida passa a ter um número (código interno), e o número do processo é o número da empresa.

ALTER TABLE cliente ADD COLUMN numero VARCHAR(10) NULL;
-- empresas já cadastradas recebem o próprio identificador como número
UPDATE cliente SET numero = CONCAT('', id);
ALTER TABLE cliente MODIFY COLUMN numero VARCHAR(10) NOT NULL;
ALTER TABLE cliente ADD CONSTRAINT uk_cliente_numero UNIQUE (tenant_id, numero);

-- Vários processos da mesma empresa compartilham o número: deixa de ser único.
ALTER TABLE processo DROP CONSTRAINT uk_processo_numero;
CREATE INDEX ix_processo_numero ON processo (tenant_id, numero);
