# SearXNG no seu PC (busca da Naomi)

O SearXNG é um buscador que roda no seu PC, de graça: ele pergunta ao Google, DuckDuckGo, Bing,
Brave e Wikipedia ao mesmo tempo e devolve os resultados juntos. A Naomi pesquisa nele pelo
Tailscale, do mesmo jeito que já fala com o Ollama. Quando ele está desligado, ela pesquisa direto
no DuckDuckGo, que responde menos coisa.

O SearXNG só devolve os resultados (título e um trecho). Quando o trecho não responde, o próprio
celular abre as duas melhores páginas (só `https`, nunca endereços da sua rede) e a Naomi responde
pelo que está escrito nelas.

## Do que precisa

- **Docker.** Linux: Docker Engine com o plugin compose (`docker compose version` tem que
  funcionar; entre no grupo `docker` com `sudo usermod -aG docker $USER` e saia e entre de novo).
  Windows: Docker Desktop (usa o WSL 2) e, nas configurações dele, *Start Docker Desktop when you
  sign in*, para o SearXNG voltar sozinho depois de reiniciar o PC.
- **Tailscale** no PC e no celular, na mesma rede (o que você já usa para o Ollama).

## Instalar

Rode tudo dentro desta pasta (`pc/searxng`), copiada para o PC.

1. **Coloque uma chave aleatória** no lugar de `ultrasecretkey` em `core-config/settings.yml`
   (com a chave padrão o SearXNG se recusa a ligar).
   - Linux:
     ```sh
     sed -i "s/ultrasecretkey/$(openssl rand -hex 32)/" core-config/settings.yml
     ```
   - Windows (PowerShell):
     ```powershell
     $key = -join (1..32 | ForEach-Object { '{0:x2}' -f (Get-Random -Maximum 256) })
     (Get-Content core-config\settings.yml) -replace 'ultrasecretkey', $key | Set-Content core-config\settings.yml
     ```
2. **Ligue:** `docker compose up -d`
3. **Teste no PC** (no Windows, escreva `curl.exe` em vez de `curl`):
   ```sh
   curl "http://127.0.0.1:8080/search?q=teste&format=json&language=pt-BR"
   ```
   Tem que vir um JSON com `"results"`. Se vier erro, veja **Problemas** abaixo.
4. **Coloque na sua rede do Tailscale** (no Linux, com `sudo` na frente):
   ```sh
   tailscale serve --bg --http=8888 8080
   ```
   `tailscale serve status` mostra se ficou ativo, e continua depois de reiniciar. Se ele recusar
   pedindo HTTPS, ative *HTTPS Certificates* na página DNS do painel do Tailscale e rode de novo.
5. **No celular:** Naomi → Settings → *Brain & personality* → *SearXNG server*.
   - Se o cérebro *Custom* já usa o nome do PC no Tailscale (`http://<pc>.<tailnet>.ts.net:11434/v1`),
     deixe o campo em branco: ela usa `http://<pc>.<tailnet>.ts.net:8888` sozinha.
   - Senão, escreva esse endereço, com o nome completo terminado em `.ts.net`. Um IP `100.x.y.z`
     não funciona: o Android só deixa a Naomi usar `http://` sem criptografia com nomes `.ts.net`.
   - Toque em **Test**: ela diz quantos resultados vieram e em quanto tempo.

Depois é só perguntar coisas do momento: "quem ganhou o jogo do Palmeiras ontem?", "quanto tá o
dólar hoje?", "que horas fecha o mercado hoje?", "pesquisa na internet quando estreia o filme novo
do Batman".

## Problemas

| O que aparece | Por quê | Como resolver |
|---|---|---|
| HTTP 403 | O formato JSON está desligado | `json` em `search: formats:` no `settings.yml`, depois `docker compose restart` |
| HTTP 429 | O limitador de robôs está ligado | `limiter: false` e `public_instance: false`, depois `docker compose restart` |
| O container reinicia sem parar | Faltou trocar a chave (passo 1) | `docker compose logs` mostra o erro |
| Poucos resultados, ou nenhum do Google | Algum buscador pediu CAPTCHA ou mudou o site | `docker compose pull && docker compose up -d` (as versões novas consertam); `http://127.0.0.1:8080/stats` mostra os erros de cada um |
| "plain http only works with the full Tailscale name" no Test | Endereço sem o nome `.ts.net` completo | Use `http://<pc>.<tailnet>.ts.net:8888` |

## Segurança

- A porta fica só no próprio PC (`127.0.0.1:8080`); quem a leva para a rede é o `tailscale serve`,
  que só aparece para os aparelhos da sua conta do Tailscale.
- Nunca use `tailscale funnel`: ele abre para a internet inteira, e este SearXNG não tem limite de
  uso nem senha.

Atualizar de vez em quando (o Google muda e o SearXNG acompanha):
`docker compose pull && docker compose up -d`
