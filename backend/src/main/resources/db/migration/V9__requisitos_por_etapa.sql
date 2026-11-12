-- Requisitos para SAIR de uma etapa: documento obrigatório ou confirmação manual.
CREATE TABLE etapa_requisito (
  id        BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT       NOT NULL,
  etapa_id  BIGINT       NOT NULL,
  tipo      VARCHAR(20)  NOT NULL,
  descricao VARCHAR(200) NOT NULL,
  ordem     INT          NOT NULL DEFAULT 0,
  CONSTRAINT fk_req_etapa FOREIGN KEY (etapa_id) REFERENCES etapa (id) ON DELETE CASCADE
);
CREATE INDEX ix_req_etapa ON etapa_requisito (etapa_id);

-- Confirmações manuais feitas em cada processo.
CREATE TABLE processo_requisito (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id    BIGINT    NOT NULL,
  processo_id  BIGINT    NOT NULL,
  requisito_id BIGINT    NOT NULL,
  cumprido_por BIGINT    NULL,
  cumprido_em  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_proc_req UNIQUE (processo_id, requisito_id),
  CONSTRAINT fk_procreq_processo FOREIGN KEY (processo_id) REFERENCES processo (id),
  CONSTRAINT fk_procreq_req FOREIGN KEY (requisito_id) REFERENCES etapa_requisito (id) ON DELETE CASCADE
);

-- Documento anexado para cumprir um requisito (opcional).
ALTER TABLE documento ADD COLUMN requisito_id BIGINT NULL;
ALTER TABLE documento ADD CONSTRAINT fk_doc_req FOREIGN KEY (requisito_id) REFERENCES etapa_requisito (id) ON DELETE SET NULL;

-- Quando o processo entrou na etapa atual: o requisito só vale se cumprido nesta passagem pela etapa.
ALTER TABLE processo ADD COLUMN etapa_desde TIMESTAMP NULL;
UPDATE processo SET etapa_desde = atualizado_em;
