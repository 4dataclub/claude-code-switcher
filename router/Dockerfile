FROM node:20-alpine

# socat als TCP-Bridge — ccr v2 bindet nur auf 127.0.0.1, ignoriert HOST=0.0.0.0
RUN apk add --no-cache socat

# claude-code-router (CLI: ccr)
RUN npm install -g @musistudio/claude-code-router@2.0.0

# Fix: reasoning-Transformer mit enable:false setzt thinking:{type:"disabled"} statt es zu löschen.
# Ollama kennt das Feld nicht und wirft 400 "does not support thinking".
# Patch: Felder komplett entfernen statt auf disabled setzen.
RUN node -e " \
  const fs=require('fs'); \
  const p='/usr/local/lib/node_modules/@musistudio/claude-code-router/dist/cli.js'; \
  const old='e.thinking={type:\"disabled\",budget_tokens:-1},e.enable_thinking=!1,e'; \
  const fix='delete e.thinking,delete e.enable_thinking,e'; \
  const src=fs.readFileSync(p,'utf8'); \
  if(!src.includes(old)){console.error('Patch-Ziel nicht gefunden!');process.exit(1);} \
  fs.writeFileSync(p,src.replace(old,fix)); \
  console.log('reasoning-Transformer gepatcht'); \
"

WORKDIR /root

ENV NON_INTERACTIVE_MODE=true
ENV LOG_LEVEL=info

# Container-Port: 3457 (socat), forwardet zu ccr auf interne 127.0.0.1:3456
EXPOSE 3457

CMD ["ccr", "start"]
