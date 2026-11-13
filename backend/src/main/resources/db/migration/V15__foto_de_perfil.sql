-- Foto de perfil: o arquivo fica no armazenamento de arquivos (S3 ou disco); aqui só a chave, o tipo e a versão
-- (usada pelo navegador para saber quando buscar a foto de novo).
ALTER TABLE usuario ADD COLUMN foto_chave  VARCHAR(300) NULL;
ALTER TABLE usuario ADD COLUMN foto_tipo   VARCHAR(30)  NULL;
ALTER TABLE usuario ADD COLUMN foto_versao BIGINT       NULL;
