-- Nome do responsável que foi apagado: processos encerrados continuam mostrando quem cuidou deles.
-- Quando preenchido, o processo só é visível ao gestor do setor e ao administrador.
ALTER TABLE processo ADD COLUMN responsavel_removido_nome VARCHAR(150) NULL;
