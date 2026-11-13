-- O envio de e-mail foi retirado do sistema.
-- Apaga as automações de e-mail (e o registro das execuções delas) e o servidor de e-mail das empresas,
-- que guardava a senha criptografada do SMTP.
DELETE FROM automacao_execucao WHERE tipo = 'EMAIL_EMPRESA';
DELETE FROM automacao_regra WHERE tipo = 'EMAIL_EMPRESA';
DROP TABLE tenant_email;
