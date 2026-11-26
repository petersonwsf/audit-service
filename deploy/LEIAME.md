# Deploy — Audit Service

A cada push na `main`, o workflow `.github/workflows/cd.yml` executa três jobs:

1. **test** — repete os testes.
2. **publish_ghcr** — build da imagem e envio ao GitHub Container Registry
   (`ghcr.io/<dono>/audit_service:latest` e `:<sha>`).
3. **deploy_vps** — conecta na VPS por SSH, entra em `VPS_PROJECT_PATH`, faz o login temporário no GHCR e executa
   `docker compose pull audit_service` e `docker compose up -d audit_service`.

## Secrets no GitHub (Settings → Secrets and variables → Actions)

| Secret | Descrição |
|---|---|
| `VPS_HOST` | IP ou domínio da VPS |
| `VPS_USERNAME` | usuário SSH (precisa usar o Docker) |
| `VPS_PORT` | porta SSH (ex.: `22`) |
| `VPS_SSH_KEY` | chave privada SSH usada no deploy |
| `VPS_PASSPHRASE` | senha da chave privada (vazio se não tiver) |
| `VPS_PROJECT_PATH` | caminho absoluto, na VPS, do diretório com este `docker-compose.yml` e o `.env` |
| `GHCR_USERNAME` | usuário do GitHub que lê a imagem |
| `GHCR_READ_TOKEN` | PAT com `read:packages` usado pela VPS para baixar a imagem |

O `GITHUB_TOKEN` é fornecido automaticamente (publicação da imagem no GHCR).

## Preparação da VPS (uma vez)

1. Docker e Docker Compose instalados; usuário SSH no grupo `docker`; chave pública de `VPS_SSH_KEY` em `~/.ssh/authorized_keys`.
2. Criar a rede compartilhada: `docker network create audit-net`.
3. Copiar `docker-compose.yml` e `.env.example` (como `.env`) para `VPS_PROJECT_PATH` e preencher os segredos
   (`openssl rand -base64 48`; leitura e administração precisam ser diferentes).
4. `docker compose up -d`.
5. Cadastrar a primeira aplicação e gerar a chave (a chave aparece **uma única vez**):
   ```
   curl -s -X POST http://audit-service:8090/v1/admin/applications \
     -H "Authorization: Bearer $AUDIT_ADMIN_TOKEN" -H "Content-Type: application/json" \
     -d '{"code":"social-flux","name":"Social Flux"}'
   curl -s -X POST http://audit-service:8090/v1/admin/applications/social-flux/keys \
     -H "Authorization: Bearer $AUDIT_ADMIN_TOKEN"
   ```
   (rode de um container na rede `audit-net`, por exemplo `docker run --rm --network audit-net curlimages/curl ...`).
6. Colocar a chave no `.env` da aplicação (`AUDIT_API_KEY`) e a aplicação na rede externa `audit-net`.

Nunca rode `docker compose down -v`: o volume `audit-postgres-dados` guarda toda a auditoria.
