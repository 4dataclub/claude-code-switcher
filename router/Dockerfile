FROM node:20-alpine

# claude-code-router (CLI: ccr)
RUN npm install -g @musistudio/claude-code-router@2.0.0

# Config wird via volume gemountet nach /root/.claude-code-router/
WORKDIR /root

ENV NON_INTERACTIVE_MODE=true
ENV LOG_LEVEL=info

EXPOSE 3456

# `ccr start` startet den Proxy-Server im Foreground
CMD ["ccr", "start"]
