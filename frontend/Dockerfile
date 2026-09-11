# Faz 1 icin yeterli hali. Faz 5'te sikilastirilacak.
FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
ARG APP_VERSION=dev
ARG APP_ENV=LOCAL
ENV VITE_APP_VERSION=$APP_VERSION VITE_APP_ENV=$APP_ENV
RUN npm run build

# nginx-unprivileged root olmadan 8080'de calisir.
FROM nginxinc/nginx-unprivileged:1.29-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
