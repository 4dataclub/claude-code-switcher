# Stage 1: Build Angular
FROM node:20-alpine AS build

WORKDIR /app

# Nur package files + vendor (Tarball) kopieren (Cache nutzen)
COPY package*.json ./
COPY vendor ./vendor

# Sauberer Install für Docker
RUN npm ci

# Rest kopieren
COPY . .

# Angular Build (Standalone-App, kein --configuration production via Default-Target)
RUN npx ng build

# Stage 2: Serve with Nginx
FROM nginx:stable-alpine

COPY --from=build /app/dist/claude-switcher-angular/browser /usr/share/nginx/html

COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
