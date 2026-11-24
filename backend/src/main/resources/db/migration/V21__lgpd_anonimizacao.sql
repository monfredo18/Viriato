-- Fase 1 (LGPD): marca de quando os dados pessoais de um titular foram anonimizados.
ALTER TABLE usuario ADD COLUMN anonimizado_em TIMESTAMP NULL;
ALTER TABLE cliente ADD COLUMN anonimizado_em TIMESTAMP NULL;
