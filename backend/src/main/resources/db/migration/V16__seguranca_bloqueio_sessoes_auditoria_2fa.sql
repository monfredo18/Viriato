-- Bloqueio de conta por tentativas de senha erradas, verificação em duas etapas (TOTP),
-- revogação de sessões ao sair e registro de auditoria.
ALTER TABLE usuario ADD COLUMN tentativas_falhas  INT          NOT NULL DEFAULT 0;
ALTER TABLE usuario ADD COLUMN bloqueado          BOOLEAN      NOT NULL DEFAULT FALSE;
ALTER TABLE usuario ADD COLUMN totp_segredo       VARCHAR(200) NULL;      -- cifrado
ALTER TABLE usuario ADD COLUMN totp_ativo         BOOLEAN      NOT NULL DEFAULT FALSE;
ALTER TABLE usuario ADD COLUMN totp_ultimo_passo  BIGINT       NULL;      -- impede reuso do mesmo código

CREATE TABLE sessao_revogada (
  jti       VARCHAR(40) PRIMARY KEY,
  expira_em TIMESTAMP   NOT NULL
);
CREATE INDEX ix_sessao_revogada_expira ON sessao_revogada (expira_em);

CREATE TABLE auditoria (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id    BIGINT       NULL,
  usuario_id   BIGINT       NULL,
  usuario_nome VARCHAR(150) NULL,
  acao         VARCHAR(60)  NOT NULL,
  detalhe      VARCHAR(500) NULL,
  criado_em    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_auditoria_tenant ON auditoria (tenant_id, id);
