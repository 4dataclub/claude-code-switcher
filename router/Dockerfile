FROM node:20-alpine

# socat als TCP-Bridge — ccr v2 bindet nur auf 127.0.0.1, ignoriert HOST=0.0.0.0
RUN apk add --no-cache socat

# claude-code-router (CLI: ccr)
RUN npm install -g @musistudio/claude-code-router@2.0.0

WORKDIR /root

ENV NON_INTERACTIVE_MODE=true
ENV LOG_LEVEL=info

# Container-Port: 3457 (socat), forwardet zu ccr auf interne 127.0.0.1:3456
EXPOSE 3457

CMD ["ccr", "start"]
