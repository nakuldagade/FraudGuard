# syntax=docker/dockerfile:1
# ---- build stage ----
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# Empty = the app calls /api on its own origin, and nginx proxies that to the backend (no CORS needed)
ARG VITE_API_BASE_URL=""
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
# Demo logins for the sign-in page. DEMO ONLY: they are compiled into the page, so anyone who can load the page can read them.
# Build with --build-arg VITE_SHOW_DEMO_LOGINS=false (or leave the four values empty) to hide them.
ARG VITE_SHOW_DEMO_LOGINS="true"
ARG VITE_DEMO_ANALYST_USERNAME=""
ARG VITE_DEMO_ANALYST_PASSWORD=""
ARG VITE_DEMO_ADMIN_USERNAME=""
ARG VITE_DEMO_ADMIN_PASSWORD=""
ENV VITE_SHOW_DEMO_LOGINS=$VITE_SHOW_DEMO_LOGINS \
    VITE_DEMO_ANALYST_USERNAME=$VITE_DEMO_ANALYST_USERNAME VITE_DEMO_ANALYST_PASSWORD=$VITE_DEMO_ANALYST_PASSWORD \
    VITE_DEMO_ADMIN_USERNAME=$VITE_DEMO_ADMIN_USERNAME VITE_DEMO_ADMIN_PASSWORD=$VITE_DEMO_ADMIN_PASSWORD
RUN npm run build

# ---- run stage: nginx (unprivileged image: non-root, listens on 8080) ----
FROM nginxinc/nginx-unprivileged:alpine
# Upgrade OS packages (curl, expat, brotli ...) to their fixed versions: the base image can lag behind Alpine's security fixes,
# and the image scan fails on HIGH/CRITICAL findings that have a fix. The unprivileged image runs as user 101, so switch to root only for this.
USER root
RUN apk upgrade --no-cache
USER 101
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=5 CMD wget -qO- http://127.0.0.1:8080/ >/dev/null || exit 1
