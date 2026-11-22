# syntax=docker/dockerfile:1

FROM node:22-alpine AS web-build
WORKDIR /build/web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY shared /build/shared
COPY web /build/web
RUN npm run build

FROM node:22-alpine AS runtime

RUN apk add --no-cache tini \
    && addgroup -S rc \
    && adduser -S rc -G rc \
    && mkdir -p /data \
    && chown -R rc:rc /data

WORKDIR /app

ENV NODE_ENV=production \
    PORT=8787 \
    DATA_DIR=/data \
    WEB_DIST=/app/web/dist

COPY server/package.json server/package-lock.json ./server/
RUN cd server \
    && npm ci --omit=dev --no-audit --no-fund \
    && npm cache clean --force

COPY server/tsconfig.json ./server/
COPY server/src ./server/src
COPY shared ./shared
COPY --from=web-build /build/web/dist ./web/dist

RUN chown -R rc:rc /app
USER rc

EXPOSE 8787

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8787/api/health >/dev/null || exit 1

WORKDIR /app/server

ENTRYPOINT ["/sbin/tini", "--"]
CMD ["node", "node_modules/tsx/dist/cli.mjs", "src/index.ts"]
