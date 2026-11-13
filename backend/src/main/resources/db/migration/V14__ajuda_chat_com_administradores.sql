-- Chat de ajuda: cada pessoa (que não é administradora) tem uma conversa com os administradores da empresa.
-- Os contadores guardam quantas mensagens cada lado ainda não viu (para o aviso no botão de ajuda).
CREATE TABLE ajuda_conversa (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id         BIGINT       NOT NULL,
  usuario_id        BIGINT       NOT NULL,
  nao_lidas_admin   INT          NOT NULL DEFAULT 0,
  nao_lidas_usuario INT          NOT NULL DEFAULT 0,
  ultima_texto      VARCHAR(200) NULL,
  ultima_de_admin   BOOLEAN      NOT NULL DEFAULT FALSE,
  atualizado_em     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_ajuda_conversa_usuario UNIQUE (usuario_id),
  CONSTRAINT fk_ajuda_conversa_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id)
);
CREATE INDEX ix_ajuda_conversa_tenant ON ajuda_conversa (tenant_id, atualizado_em);

CREATE TABLE ajuda_mensagem (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id   BIGINT        NOT NULL,
  conversa_id BIGINT        NOT NULL,
  autor_id    BIGINT        NULL,
  autor_nome  VARCHAR(150)  NOT NULL,
  de_admin    BOOLEAN       NOT NULL,
  texto       VARCHAR(2000) NOT NULL,
  criado_em   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_ajuda_mensagem_conversa FOREIGN KEY (conversa_id) REFERENCES ajuda_conversa (id)
);
CREATE INDEX ix_ajuda_mensagem_conversa ON ajuda_mensagem (conversa_id, id);
