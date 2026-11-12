-- E-mail da empresa atendida (opcional): usado pela automação "avisar a empresa por e-mail".
ALTER TABLE cliente ADD COLUMN email VARCHAR(190) NULL;

-- Documentos gerados pelo sistema não têm quem enviou.
ALTER TABLE documento MODIFY COLUMN enviado_por BIGINT NULL;
ALTER TABLE documento ADD COLUMN automatico BOOLEAN NOT NULL DEFAULT FALSE;

-- Regras: quando o processo ENTRA numa etapa (ou é CANCELADO), executa uma ação.
CREATE TABLE automacao_regra (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id        BIGINT       NOT NULL,
  fluxo_id         BIGINT       NOT NULL,
  etapa_id         BIGINT       NULL,
  evento           VARCHAR(20)  NOT NULL,
  tipo             VARCHAR(20)  NOT NULL,
  ativo            BOOLEAN      NOT NULL DEFAULT TRUE,
  assunto          VARCHAR(200) NULL,
  mensagem         TEXT         NULL,
  anexar_documento BOOLEAN      NOT NULL DEFAULT FALSE,
  url              VARCHAR(500) NULL,
  segredo          VARCHAR(200) NULL,
  criado_em        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_autoregra_fluxo FOREIGN KEY (fluxo_id) REFERENCES fluxo (id) ON DELETE CASCADE,
  CONSTRAINT fk_autoregra_etapa FOREIGN KEY (etapa_id) REFERENCES etapa (id) ON DELETE CASCADE
);
CREATE INDEX ix_autoregra_fluxo ON automacao_regra (fluxo_id, evento, etapa_id);

-- Registro de cada execução (sucesso, falha ou ignorada), para consulta no processo.
CREATE TABLE automacao_execucao (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id    BIGINT       NOT NULL,
  processo_id  BIGINT       NOT NULL,
  regra_id     BIGINT       NULL,
  tipo         VARCHAR(20)  NOT NULL,
  evento       VARCHAR(20)  NOT NULL,
  etapa_nome   VARCHAR(100) NULL,
  status       VARCHAR(12)  NOT NULL,
  mensagem     VARCHAR(500) NULL,
  executado_em TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_autoexec_processo FOREIGN KEY (processo_id) REFERENCES processo (id),
  CONSTRAINT fk_autoexec_regra FOREIGN KEY (regra_id) REFERENCES automacao_regra (id) ON DELETE SET NULL
);
CREATE INDEX ix_autoexec_processo ON automacao_execucao (processo_id, id);
