# Viriato — Controle de Processos

Sistema web multi-empresa (SaaS) para cadastrar, acompanhar e automatizar processos com fluxos configuráveis, prazos, documentos e notificações.

| Camada | Tecnologia |
|---|---|
| Frontend | React 18 + TypeScript + Vite |
| Backend | Java 21 + Spring Boot 3 (monolito modular) |
| Banco | MySQL 8 (migrações com Flyway) |
| Arquivos | AWS S3 (S3 local em dev) |
| Notificações | In-app (sininho) + e-mail (SMTP/AWS SES) |
| Infra | Docker / Docker Compose |

## Rodando localmente

```bash
docker compose up --build
```

- App: http://localhost:3000 (clique em **Cadastrar minha empresa**)
- Swagger/OpenAPI: http://localhost:8080/swagger-ui.html
- E-mails enviados (Mailpit): http://localhost:8025

Desenvolvimento sem Docker para o código:

```bash
docker compose up -d mysql s3 mailpit   # dependências
cd backend && mvn spring-boot:run       # API em :8080
cd frontend && npm install && npm run dev   # UI em :5173 (proxy para :8080)
```

Testes: `cd backend && mvn test` (usa H2 em modo MySQL; não precisa de Docker).

## Arquitetura

```
backend/src/main/java/br/com/viriato/
  seguranca/   JWT, filtro, config de segurança, CORS
  usuario/     login, cadastro de empresa (onboarding), usuários e papéis
  fluxo/       fluxos, etapas e transições configuráveis por empresa
  processo/    processos, movimentação, histórico, atribuição, cancelamento
  documento/   upload/download (S3), nomes sanitizados
  notificacao/ in-app + e-mail assíncrono + agendador de prazos
  comum/       tratamento de erros, datas
```

### Multi-tenant
- Toda tabela de negócio tem `tenant_id`.
- O `tenant_id` vem **somente do JWT**, nunca do corpo da requisição.
- Toda consulta filtra por `tenant_id`; recurso de outra empresa responde **404**.
- Coberto por testes (`FluxoCompletoTest`).

### Papéis
`ADMIN` (usuários e fluxos), `GESTOR` (fluxos, atribuições, cancelamento), `USUARIO` (opera processos pelos quais é responsável).

### Prazos
Cada etapa pode ter `prazo_dias`. Um job diário (`PRAZOS_CRON`, padrão 08:00 America/Sao_Paulo) avisa o responsável sobre prazos vencendo/vencidos, **uma vez por etapa**.

## Configuração (variáveis de ambiente)

Ver `.env.example` e `backend/src/main/resources/application.yml`. Obrigatórias em produção:

| Variável | Descrição |
|---|---|
| `JWT_SECRET` | ≥ 32 bytes. **Troque o valor padrão.** |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | MySQL |
| `S3_BUCKET`, `S3_REGIAO` | Bucket real. Sem `S3_ENDPOINT`, usa a AWS (credenciais via IAM Role) |
| `MAIL_HOST`, `MAIL_PORT`, `MAIL_USER`, `MAIL_PASSWORD`, `MAIL_AUTH`, `MAIL_STARTTLS`, `MAIL_REMETENTE` | SMTP (ex.: AWS SES) |
| `CORS_ORIGINS` | Origens permitidas (não necessário se front e API ficam no mesmo domínio via nginx) |

## Produção (sugestão AWS)

ECS Fargate (backend + frontend) · RDS MySQL · S3 com bloqueio de acesso público e criptografia · SES para e-mail · Task Role do ECS com permissão só no bucket · HTTPS no ALB. As imagens são geradas pelos `Dockerfile` de `backend/` e `frontend/`.

## Roadmap

- [ ] Edição de fluxos existentes e transições não sequenciais na UI (a API já aceita transições arbitrárias)
- [ ] Recuperação de senha e 2FA
- [ ] Campos personalizados por fluxo (hoje: JSON livre via API)
- [ ] Cobrança/planos e limites por empresa
- [ ] Feriados e prazos em dias úteis
- [ ] Dashboard e relatórios
- [ ] Rate limiting no login
