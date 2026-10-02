# syntax=docker/dockerfile:1.7
# Build context: repository root (npm workspaces share the root lockfile).

# ---- Build the static bundle ----
FROM node:24-alpine AS build
WORKDIR /app

COPY package.json package-lock.json ./
COPY web/package.json web/
RUN --mount=type=cache,target=/root/.npm npm ci --workspace web --no-audit --no-fund

COPY web/ web/
ARG VITE_SHOW_FLIGHT_OPERATIONS=false
ENV VITE_SHOW_FLIGHT_OPERATIONS=${VITE_SHOW_FLIGHT_OPERATIONS}
RUN npm run build -w web

# ---- Serve with unprivileged nginx and proxy /api to Spring Boot ----
FROM nginxinc/nginx-unprivileged:1.29-alpine AS runtime

# Rendered by the image entrypoint with envsubst (API_UPSTREAM).
COPY web/nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY web/nginx/security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY --from=build /app/web/dist /usr/share/nginx/html

ENV API_UPSTREAM=http://api:3101
EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --retries=5 \
  CMD wget -qO- http://127.0.0.1:8080/healthz >/dev/null || exit 1
