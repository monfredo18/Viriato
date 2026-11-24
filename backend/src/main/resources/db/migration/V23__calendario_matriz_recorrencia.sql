-- Fase 3: calendário fiscal, matriz de obrigações, geração recorrente de processos, dependências e campos extras.
CREATE TABLE feriado (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             BIGINT       NOT NULL,
  escopo                VARCHAR(10)  NOT NULL,
  uf                    VARCHAR(2)   NULL,
  codigo_municipio_ibge VARCHAR(7)   NULL,
  data                  DATE         NULL,
  dia                   INT          NULL,
  mes                   INT          NULL,
  descricao             VARCHAR(150) NOT NULL,
  ativo                 BOOLEAN      NOT NULL DEFAULT TRUE,
  CONSTRAINT fk_feriado_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id) ON DELETE CASCADE
);
CREATE INDEX ix_feriado_tenant ON feriado (tenant_id, escopo);

-- Feriados nacionais fixos para as empresas que já existem (as novas recebem o mesmo conjunto na criação).
INSERT INTO feriado (tenant_id, escopo, dia, mes, descricao) SELECT id, 'NACIONAL', 1, 1, 'Confraternização Universal' FROM tenant;
INSERT INTO feriado (tenant_id, escopo, dia, mes, descricao) SELECT id, 'NACIONAL', 21, 4, 'Tiradentes' FROM tenant;
INSERT INTO feriado (tenant_id, escopo, dia, mes, descricao) SELECT id, 'NACIONAL', 1, 5, 'Dia do Trabalhador' FROM tenant;
INSERT INTO feriado (tenant_id, escopo, dia, mes, descricao) SELECT id, 'NACIONAL', 7, 9, 'Independência do Brasil' FROM tenant;
INSERT INTO feriado (tenant_id, escopo, dia, mes, descricao) SELECT id, 'NACIONAL', 12, 10, 'Nossa Senhora Aparecida' FROM tenant;
INSERT INTO feriado (tenant_id, escopo, dia, mes, descricao) SELECT id, 'NACIONAL', 2, 11, 'Finados' FROM tenant;
INSERT INTO feriado (tenant_id, escopo, dia, mes, descricao) SELECT id, 'NACIONAL', 15, 11, 'Proclamação da República' FROM tenant;
INSERT INTO feriado (tenant_id, escopo, dia, mes, descricao) SELECT id, 'NACIONAL', 20, 11, 'Consciência Negra' FROM tenant;
INSERT INTO feriado (tenant_id, escopo, dia, mes, descricao) SELECT id, 'NACIONAL', 25, 12, 'Natal' FROM tenant;

CREATE TABLE obrigacao_modelo (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             BIGINT       NOT NULL,
  nome                  VARCHAR(120) NOT NULL,
  entrega_id            BIGINT       NOT NULL,
  periodicidade         VARCHAR(12)  NOT NULL,
  modo_vencimento       VARCHAR(20)  NOT NULL,
  dia_vencimento        INT          NULL,
  meses_defasagem       INT          NOT NULL DEFAULT 1,
  meses_aplicaveis      VARCHAR(60)  NOT NULL,
  regra_dia_util        VARCHAR(20)  NOT NULL,
  esfera                VARCHAR(10)  NOT NULL,
  regimes_aplicaveis    VARCHAR(200) NULL,
  ufs_aplicaveis        VARCHAR(200) NULL,
  cnaes_aplicaveis      VARCHAR(500) NULL,
  responsavel_padrao_id BIGINT       NULL,
  ativo                 BOOLEAN      NOT NULL DEFAULT TRUE,
  criado_em             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_obrmod_entrega FOREIGN KEY (entrega_id) REFERENCES entrega (id) ON DELETE CASCADE
);
CREATE INDEX ix_obrmod_tenant ON obrigacao_modelo (tenant_id);

CREATE TABLE obrigacao_modelo_dependencia (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             BIGINT NOT NULL,
  modelo_id             BIGINT NOT NULL,
  predecessor_modelo_id BIGINT NOT NULL,
  CONSTRAINT uk_obrmod_dep UNIQUE (modelo_id, predecessor_modelo_id),
  CONSTRAINT fk_obrdep_modelo FOREIGN KEY (modelo_id) REFERENCES obrigacao_modelo (id) ON DELETE CASCADE,
  CONSTRAINT fk_obrdep_pred FOREIGN KEY (predecessor_modelo_id) REFERENCES obrigacao_modelo (id) ON DELETE CASCADE
);

CREATE TABLE cliente_obrigacao (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id     BIGINT  NOT NULL,
  cliente_id    BIGINT  NOT NULL,
  modelo_id     BIGINT  NOT NULL,
  ativo         BOOLEAN NOT NULL DEFAULT TRUE,
  responsavel_id BIGINT NULL,
  personalizado BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT uk_cliente_obrigacao UNIQUE (cliente_id, modelo_id),
  CONSTRAINT fk_cliobr_cliente FOREIGN KEY (cliente_id) REFERENCES cliente (id) ON DELETE CASCADE,
  CONSTRAINT fk_cliobr_modelo FOREIGN KEY (modelo_id) REFERENCES obrigacao_modelo (id) ON DELETE CASCADE
);

-- Processos gerados pelo sistema não têm quem os abriu.
ALTER TABLE processo MODIFY COLUMN criado_por BIGINT NULL;
ALTER TABLE processo ADD COLUMN competencia_ano INT NULL;
ALTER TABLE processo ADD COLUMN competencia_mes INT NULL;
ALTER TABLE processo ADD COLUMN obrigacao_modelo_id BIGINT NULL;
ALTER TABLE processo ADD CONSTRAINT fk_processo_obrmod FOREIGN KEY (obrigacao_modelo_id) REFERENCES obrigacao_modelo (id) ON DELETE SET NULL;
-- Um processo por empresa, obrigação e competência (os manuais, sem competência, não entram: NULL não repete).
CREATE UNIQUE INDEX uk_processo_competencia ON processo (tenant_id, cliente_id, obrigacao_modelo_id, competencia_ano, competencia_mes);

CREATE TABLE processo_dependencia (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id      BIGINT NOT NULL,
  processo_id    BIGINT NOT NULL,
  predecessor_id BIGINT NOT NULL,
  CONSTRAINT uk_processo_dep UNIQUE (processo_id, predecessor_id),
  CONSTRAINT fk_procdep_proc FOREIGN KEY (processo_id) REFERENCES processo (id) ON DELETE CASCADE,
  CONSTRAINT fk_procdep_pred FOREIGN KEY (predecessor_id) REFERENCES processo (id) ON DELETE CASCADE
);

CREATE TABLE entrega_campo (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id   BIGINT        NOT NULL,
  entrega_id  BIGINT        NOT NULL,
  chave       VARCHAR(40)   NOT NULL,
  rotulo      VARCHAR(80)   NOT NULL,
  tipo        VARCHAR(10)   NOT NULL,
  obrigatorio BOOLEAN       NOT NULL DEFAULT FALSE,
  opcoes      VARCHAR(1000) NULL,
  ordem       INT           NOT NULL DEFAULT 0,
  CONSTRAINT uk_entrega_campo UNIQUE (entrega_id, chave),
  CONSTRAINT fk_entcampo_entrega FOREIGN KEY (entrega_id) REFERENCES entrega (id) ON DELETE CASCADE
);
