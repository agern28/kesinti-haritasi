# syntax=docker/dockerfile:1
# Build context repo koku (compose: context .), cunku "Yenilikler" penceresi CHANGELOG.md'yi okuyor.
# Gonderilen dosyalar Dockerfile.dockerignore'da: sadece frontend/ ve CHANGELOG.md.
FROM node:24-alpine AS build
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
# npm cache'i BuildKit cache'inde: lock dosyasi degismedikce paketler yeniden inmiyor.
RUN --mount=type=cache,target=/root/.npm npm ci
COPY frontend/ ./
# src/App.jsx '../../CHANGELOG.md' okuyor: /app/src -> /CHANGELOG.md
COPY CHANGELOG.md /CHANGELOG.md
# Surum imajin parcasi. Ortam (LOCAL/INT/PROD) degil: o calisma aninda APP_ENV ile geliyor (nginx /env.json).
ARG APP_VERSION=dev
ENV VITE_APP_VERSION=$APP_VERSION
RUN npm run build

# nginx-unprivileged root olmadan (UID 101) 8080'de calisir.
FROM nginxinc/nginx-unprivileged:1.29-alpine
ARG APP_VERSION=dev
LABEL org.opencontainers.image.source="https://github.com/agern28/kesinti-haritasi" \
      org.opencontainers.image.title="kesinti-haritasi-frontend" \
      org.opencontainers.image.description="Kesinti haritasi arayuzu (React + Leaflet), nginx" \
      org.opencontainers.image.version="${APP_VERSION}"
# Varsayilanlar compose icin. NGINX_RESOLVER Docker'in gomulu DNS'i; Kubernetes'te kube-dns'in
# adresi, API_UPSTREAM de api Service'inin adi verilecek (Faz 7).
ENV APP_ENV=LOCAL \
    NGINX_RESOLVER=127.0.0.11 \
    API_UPSTREAM=http://api:8080
# Sablon: nginx acilirken bu degiskenler doldurulup /etc/nginx/conf.d/default.conf'a yaziliyor.
COPY frontend/nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
