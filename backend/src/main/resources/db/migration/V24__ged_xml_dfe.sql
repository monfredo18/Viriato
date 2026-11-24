-- Fase 4: GED contábil (documentos por empresa, ano, mês e setor; guarda legal), notas fiscais importadas e controle da consulta à SEFAZ.
ALTER TABLE documento MODIFY COLUMN processo_id BIGINT NULL;
ALTER TABLE documento ADD COLUMN cliente_id BIGINT NULL;
ALTER TABLE documento ADD COLUMN setor_id BIGINT NULL;
ALTER TABLE documento ADD COLUMN ano INT NULL;
ALTER TABLE documento ADD COLUMN mes INT NULL;
ALTER TABLE documento ADD COLUMN categoria VARCHAR(20) NULL;
ALTER TABLE documento ADD COLUMN guarda_legal BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE documento ADD COLUMN arquivado BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE documento ADD COLUMN arquivado_em TIMESTAMP NULL;
ALTER TABLE documento ADD COLUMN arquivado_por BIGINT NULL;

-- Os documentos que já existem passam a ter empresa, setor e período (o do envio).
UPDATE documento SET cliente_id = (SELECT p.cliente_id FROM processo p WHERE p.id = documento.processo_id),
                     setor_id   = (SELECT p.setor_id FROM processo p WHERE p.id = documento.processo_id),
                     ano = YEAR(criado_em), mes = MONTH(criado_em);
CREATE INDEX ix_documento_ged ON documento (tenant_id, cliente_id, ano, mes);

CREATE TABLE documento_fiscal_xml (
  id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id          BIGINT         NOT NULL,
  cliente_id         BIGINT         NOT NULL,
  documento_id       BIGINT         NULL,
  chave_acesso       VARCHAR(60)    NOT NULL,
  modelo             VARCHAR(8)     NOT NULL,
  numero_nota        VARCHAR(20)    NULL,
  serie              VARCHAR(10)    NULL,
  data_emissao       DATE           NULL,
  competencia_ano    INT            NULL,
  competencia_mes    INT            NULL,
  cnpj_emitente      VARCHAR(14)    NULL,
  nome_emitente      VARCHAR(200)   NULL,
  cnpj_destinatario  VARCHAR(14)    NULL,
  nome_destinatario  VARCHAR(200)   NULL,
  tipo_operacao      VARCHAR(8)     NOT NULL,
  valor_total        DECIMAL(15,2)  NOT NULL DEFAULT 0,
  valor_icms         DECIMAL(15,2)  NOT NULL DEFAULT 0,
  valor_ipi          DECIMAL(15,2)  NOT NULL DEFAULT 0,
  valor_pis          DECIMAL(15,2)  NOT NULL DEFAULT 0,
  valor_cofins       DECIMAL(15,2)  NOT NULL DEFAULT 0,
  valor_iss          DECIMAL(15,2)  NOT NULL DEFAULT 0,
  cfops              VARCHAR(500)   NULL,
  ncms               VARCHAR(1000)  NULL,
  origem             VARCHAR(10)    NOT NULL,
  criado_em          TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_xml_chave UNIQUE (tenant_id, chave_acesso),
  CONSTRAINT fk_xml_cliente FOREIGN KEY (cliente_id) REFERENCES cliente (id) ON DELETE CASCADE,
  CONSTRAINT fk_xml_documento FOREIGN KEY (documento_id) REFERENCES documento (id) ON DELETE SET NULL
);
CREATE INDEX ix_xml_competencia ON documento_fiscal_xml (tenant_id, cliente_id, competencia_ano, competencia_mes);

CREATE TABLE dfe_controle (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id        BIGINT       NOT NULL,
  cliente_id       BIGINT       NOT NULL,
  ultimo_nsu       VARCHAR(15)  NOT NULL DEFAULT '0',
  ultima_consulta  TIMESTAMP    NULL,
  proxima_consulta TIMESTAMP    NULL,
  status           VARCHAR(20)  NULL,
  mensagem         VARCHAR(300) NULL,
  CONSTRAINT uk_dfe_cliente UNIQUE (cliente_id),
  CONSTRAINT fk_dfe_cliente FOREIGN KEY (cliente_id) REFERENCES cliente (id) ON DELETE CASCADE
);
