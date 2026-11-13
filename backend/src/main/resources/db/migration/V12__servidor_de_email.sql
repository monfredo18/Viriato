-- Servidor SMTP de cada empresa (configurado pelo administrador na tela). A senha fica criptografada.
CREATE TABLE tenant_email (
  tenant_id     BIGINT       NOT NULL PRIMARY KEY,
  host          VARCHAR(255) NOT NULL,
  porta         INT          NOT NULL,
  seguranca     VARCHAR(10)  NOT NULL,
  usuario       VARCHAR(255) NULL,
  senha_cifrada VARCHAR(700) NULL,
  remetente     VARCHAR(190) NOT NULL,
  atualizado_em TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_tenant_email FOREIGN KEY (tenant_id) REFERENCES tenant (id)
);
