-- Fase 2: ficha fiscal e societária da empresa atendida, cofre de certificados, procurações, checklists e alertas de vencimento.
ALTER TABLE cliente ADD COLUMN cnpj VARCHAR(14) NULL;
ALTER TABLE cliente ADD COLUMN razao_social VARCHAR(200) NULL;
ALTER TABLE cliente ADD COLUMN nome_fantasia VARCHAR(200) NULL;
ALTER TABLE cliente ADD COLUMN inscricao_estadual VARCHAR(30) NULL;
ALTER TABLE cliente ADD COLUMN inscricao_municipal VARCHAR(30) NULL;
ALTER TABLE cliente ADD COLUMN cnae_principal VARCHAR(7) NULL;
ALTER TABLE cliente ADD COLUMN cnae_principal_descricao VARCHAR(255) NULL;
ALTER TABLE cliente ADD COLUMN cnaes_secundarios TEXT NULL;
ALTER TABLE cliente ADD COLUMN regime_tributario VARCHAR(20) NULL;
ALTER TABLE cliente ADD COLUMN uf VARCHAR(2) NULL;
ALTER TABLE cliente ADD COLUMN codigo_municipio_ibge VARCHAR(7) NULL;
ALTER TABLE cliente ADD COLUMN data_abertura_empresa DATE NULL;
ALTER TABLE cliente ADD COLUMN status_contratual VARCHAR(20) NOT NULL DEFAULT 'ATIVO';
CREATE UNIQUE INDEX uk_cliente_cnpj ON cliente (tenant_id, cnpj);

CREATE TABLE cliente_socio (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id        BIGINT        NOT NULL,
  cliente_id       BIGINT        NOT NULL,
  nome             VARCHAR(150)  NOT NULL,
  cpf_cifrado      VARCHAR(400)  NULL,
  cpf_mascara      VARCHAR(20)   NULL,
  percentual       DECIMAL(5,2)  NULL,
  responsavel_legal BOOLEAN      NOT NULL DEFAULT FALSE,
  email            VARCHAR(150)  NULL,
  telefone         VARCHAR(30)   NULL,
  criado_em        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_socio_cliente FOREIGN KEY (cliente_id) REFERENCES cliente (id) ON DELETE CASCADE
);

CREATE TABLE cliente_contato (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id        BIGINT        NOT NULL,
  cliente_id       BIGINT        NOT NULL,
  nome             VARCHAR(150)  NOT NULL,
  area             VARCHAR(20)   NOT NULL,
  email            VARCHAR(150)  NULL,
  telefone         VARCHAR(30)   NULL,
  recebe_guias     BOOLEAN       NOT NULL DEFAULT FALSE,
  recebe_cobrancas BOOLEAN       NOT NULL DEFAULT FALSE,
  criado_em        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_contato_cliente FOREIGN KEY (cliente_id) REFERENCES cliente (id) ON DELETE CASCADE
);

CREATE TABLE cliente_certificado (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id        BIGINT        NOT NULL,
  cliente_id       BIGINT        NOT NULL,
  tipo             VARCHAR(2)    NOT NULL,
  titular          VARCHAR(255)  NULL,
  cnpj_certificado VARCHAR(14)   NULL,
  emissora         VARCHAR(255)  NULL,
  numero_serie     VARCHAR(100)  NULL,
  valido_de        DATE          NULL,
  valido_ate       DATE          NULL,
  arquivo_chave    VARCHAR(400)  NULL,
  senha_cifrada    VARCHAR(400)  NULL,
  observacao       VARCHAR(300)  NULL,
  ativo            BOOLEAN       NOT NULL DEFAULT TRUE,
  criado_por       BIGINT        NULL,
  criado_em        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_cert_cliente FOREIGN KEY (cliente_id) REFERENCES cliente (id) ON DELETE CASCADE
);

CREATE TABLE cliente_procuracao (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id         BIGINT        NOT NULL,
  cliente_id        BIGINT        NOT NULL,
  tipo              VARCHAR(25)   NOT NULL,
  data_outorga      DATE          NOT NULL,
  data_vencimento   DATE          NULL,
  poderes           VARCHAR(1000) NULL,
  comprovante_chave VARCHAR(400)  NULL,
  comprovante_nome  VARCHAR(255)  NULL,
  ativo             BOOLEAN       NOT NULL DEFAULT TRUE,
  criado_em         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_proc_cliente FOREIGN KEY (cliente_id) REFERENCES cliente (id) ON DELETE CASCADE
);

CREATE TABLE cliente_checklist_item (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id     BIGINT        NOT NULL,
  cliente_id    BIGINT        NOT NULL,
  tipo          VARCHAR(12)   NOT NULL,
  ordem         INT           NOT NULL,
  descricao     VARCHAR(200)  NOT NULL,
  concluido     BOOLEAN       NOT NULL DEFAULT FALSE,
  concluido_por BIGINT        NULL,
  concluido_em  TIMESTAMP     NULL,
  CONSTRAINT fk_check_cliente FOREIGN KEY (cliente_id) REFERENCES cliente (id) ON DELETE CASCADE
);

-- Um alerta por item e por marco (30, 15, 5 dias ou vencido), para não avisar duas vezes a mesma coisa.
CREATE TABLE alerta_vencimento (
  id        BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT      NOT NULL,
  tipo      VARCHAR(20) NOT NULL,
  ref_id    BIGINT      NOT NULL,
  marco     INT         NOT NULL,
  criado_em TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_alerta_vencimento UNIQUE (tipo, ref_id, marco)
);

ALTER TABLE notificacao ADD COLUMN cliente_id BIGINT NULL;
