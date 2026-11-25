-- Certificados passam a ser lidos do computador do usuário por um agente local (nada de upload de .pfx nem de senha no sistema).
-- Os registros manuais antigos (A1 enviado, A3 informativo) deixam de existir.
DELETE FROM alerta_vencimento WHERE tipo = 'CERTIFICADO';
DROP TABLE cliente_certificado;

-- Um agente é o programa instalado no computador de um usuário. O token só é guardado em hash.
CREATE TABLE agente (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id      BIGINT        NOT NULL,
  usuario_id     BIGINT        NOT NULL,
  nome           VARCHAR(120)  NOT NULL,
  versao         VARCHAR(30)   NULL,
  token_hash     VARCHAR(64)   NOT NULL,
  revogado       BOOLEAN       NOT NULL DEFAULT FALSE,
  ultimo_contato TIMESTAMP     NULL,
  criado_em      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_agente_token UNIQUE (token_hash),
  CONSTRAINT fk_agente_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id) ON DELETE CASCADE
);
CREATE INDEX ix_agente_tenant ON agente (tenant_id);

-- Código de uso único, de vida curta, que o usuário digita no agente para ligá-lo à própria conta.
CREATE TABLE agente_pareamento (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id   BIGINT       NOT NULL,
  usuario_id  BIGINT       NOT NULL,
  codigo_hash VARCHAR(64)  NOT NULL,
  expira_em   TIMESTAMP    NOT NULL,
  usado       BOOLEAN      NOT NULL DEFAULT FALSE,
  criado_em   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_agente_pareamento UNIQUE (codigo_hash),
  CONSTRAINT fk_pareamento_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id) ON DELETE CASCADE
);

-- Só os dados públicos do certificado (nunca a chave privada, que não sai do computador).
CREATE TABLE cliente_certificado (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id        BIGINT        NOT NULL,
  cliente_id       BIGINT        NULL,
  thumbprint       VARCHAR(64)   NOT NULL,
  titular          VARCHAR(255)  NULL,
  cnpj_certificado VARCHAR(14)   NULL,
  emissora         VARCHAR(255)  NULL,
  numero_serie     VARCHAR(100)  NULL,
  valido_de        DATE          NULL,
  valido_ate       DATE          NULL,
  vinculo_manual   BOOLEAN       NOT NULL DEFAULT FALSE,
  ativo            BOOLEAN       NOT NULL DEFAULT TRUE,
  visto_em         TIMESTAMP     NULL,
  criado_em        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_cert_thumbprint UNIQUE (tenant_id, thumbprint),
  CONSTRAINT fk_cert_cliente FOREIGN KEY (cliente_id) REFERENCES cliente (id) ON DELETE SET NULL
);
CREATE INDEX ix_cert_cliente ON cliente_certificado (cliente_id);

-- Em quais computadores (agentes) cada certificado está instalado.
CREATE TABLE certificado_agente (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  certificado_id BIGINT    NOT NULL,
  agente_id      BIGINT    NOT NULL,
  visto_em       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_cert_agente UNIQUE (certificado_id, agente_id),
  CONSTRAINT fk_certag_cert FOREIGN KEY (certificado_id) REFERENCES cliente_certificado (id) ON DELETE CASCADE,
  CONSTRAINT fk_certag_agente FOREIGN KEY (agente_id) REFERENCES agente (id) ON DELETE CASCADE
);

-- Trabalhos que o Viriato pede ao agente (hoje: uma chamada HTTPS com o certificado do computador, para a SEFAZ).
CREATE TABLE agente_tarefa (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id      BIGINT        NOT NULL,
  certificado_id BIGINT        NOT NULL,
  agente_id      BIGINT        NULL,
  tipo           VARCHAR(20)   NOT NULL,
  url            VARCHAR(500)  NOT NULL,
  content_type   VARCHAR(100)  NOT NULL,
  corpo          MEDIUMTEXT    NULL,
  status         VARCHAR(15)   NOT NULL,
  resposta       MEDIUMTEXT    NULL,
  http_status    INT           NULL,
  erro           VARCHAR(500)  NULL,
  criado_em      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  atendido_em    TIMESTAMP     NULL,
  CONSTRAINT fk_tarefa_cert FOREIGN KEY (certificado_id) REFERENCES cliente_certificado (id) ON DELETE CASCADE
);
CREATE INDEX ix_tarefa_status ON agente_tarefa (status, certificado_id);
