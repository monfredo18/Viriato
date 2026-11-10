-- Multi-tenant: toda tabela de negócio carrega tenant_id.

CREATE TABLE tenant (
  id        BIGINT AUTO_INCREMENT PRIMARY KEY,
  nome      VARCHAR(150) NOT NULL,
  ativo     BOOLEAN      NOT NULL DEFAULT TRUE,
  criado_em TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE usuario (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id  BIGINT       NOT NULL,
  nome       VARCHAR(150) NOT NULL,
  email      VARCHAR(190) NOT NULL,
  senha_hash VARCHAR(100) NOT NULL,
  papel      VARCHAR(20)  NOT NULL,
  ativo      BOOLEAN      NOT NULL DEFAULT TRUE,
  criado_em  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_usuario_email UNIQUE (email),
  CONSTRAINT fk_usuario_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id)
);

CREATE TABLE fluxo (
  id        BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT       NOT NULL,
  nome      VARCHAR(150) NOT NULL,
  ativo     BOOLEAN      NOT NULL DEFAULT TRUE,
  CONSTRAINT fk_fluxo_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id)
);

CREATE TABLE etapa (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id  BIGINT       NOT NULL,
  fluxo_id   BIGINT       NOT NULL,
  nome       VARCHAR(100) NOT NULL,
  ordem      INT          NOT NULL,
  prazo_dias INT          NULL,
  inicial    BOOLEAN      NOT NULL DEFAULT FALSE,
  final_     BOOLEAN      NOT NULL DEFAULT FALSE,
  CONSTRAINT fk_etapa_fluxo FOREIGN KEY (fluxo_id) REFERENCES fluxo (id)
);

CREATE TABLE transicao (
  id        BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  fluxo_id  BIGINT NOT NULL,
  de_etapa  BIGINT NOT NULL,
  para_etapa BIGINT NOT NULL,
  CONSTRAINT uk_transicao UNIQUE (de_etapa, para_etapa),
  CONSTRAINT fk_transicao_fluxo FOREIGN KEY (fluxo_id) REFERENCES fluxo (id),
  CONSTRAINT fk_transicao_de FOREIGN KEY (de_etapa) REFERENCES etapa (id),
  CONSTRAINT fk_transicao_para FOREIGN KEY (para_etapa) REFERENCES etapa (id)
);

CREATE TABLE processo (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id      BIGINT       NOT NULL,
  numero         VARCHAR(30)  NOT NULL,
  titulo         VARCHAR(200) NOT NULL,
  descricao      TEXT         NULL,
  fluxo_id       BIGINT       NOT NULL,
  etapa_atual_id BIGINT       NOT NULL,
  responsavel_id BIGINT       NULL,
  status         VARCHAR(20)  NOT NULL,
  prazo_etapa    DATE         NULL,
  prazo_alertado BOOLEAN      NOT NULL DEFAULT FALSE,
  campos_extras  TEXT         NULL,
  criado_por     BIGINT       NOT NULL,
  criado_em      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  atualizado_em  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_processo_numero UNIQUE (tenant_id, numero),
  CONSTRAINT fk_processo_fluxo FOREIGN KEY (fluxo_id) REFERENCES fluxo (id),
  CONSTRAINT fk_processo_etapa FOREIGN KEY (etapa_atual_id) REFERENCES etapa (id),
  CONSTRAINT fk_processo_resp FOREIGN KEY (responsavel_id) REFERENCES usuario (id)
);
CREATE INDEX ix_processo_tenant_status ON processo (tenant_id, status);
CREATE INDEX ix_processo_prazo ON processo (status, prazo_etapa);

CREATE TABLE processo_historico (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id   BIGINT       NOT NULL,
  processo_id BIGINT       NOT NULL,
  usuario_id  BIGINT       NULL,
  acao        VARCHAR(30)  NOT NULL,
  de_etapa    VARCHAR(100) NULL,
  para_etapa  VARCHAR(100) NULL,
  comentario  TEXT         NULL,
  criado_em   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_hist_processo FOREIGN KEY (processo_id) REFERENCES processo (id)
);
CREATE INDEX ix_hist_processo ON processo_historico (processo_id);

CREATE TABLE documento (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id     BIGINT       NOT NULL,
  processo_id   BIGINT       NOT NULL,
  nome_original VARCHAR(255) NOT NULL,
  chave_s3      VARCHAR(400) NOT NULL,
  content_type  VARCHAR(150) NULL,
  tamanho       BIGINT       NOT NULL,
  enviado_por   BIGINT       NOT NULL,
  criado_em     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_doc_processo FOREIGN KEY (processo_id) REFERENCES processo (id)
);
CREATE INDEX ix_doc_processo ON documento (processo_id);

CREATE TABLE notificacao (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id   BIGINT       NOT NULL,
  usuario_id  BIGINT       NOT NULL,
  titulo      VARCHAR(200) NOT NULL,
  mensagem    VARCHAR(500) NULL,
  processo_id BIGINT       NULL,
  lida        BOOLEAN      NOT NULL DEFAULT FALSE,
  criado_em   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_notif_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id)
);
CREATE INDEX ix_notif_usuario ON notificacao (usuario_id, lida);
