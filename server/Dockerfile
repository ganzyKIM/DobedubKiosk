# 보고팡 백엔드(vogopang_back)의 빌드 규약에 맞춘 이미지.
#   - 베이스: node:22-alpine, 멀티스테이지
#   - 배포 대상: ECS on EC2 (linux/arm64) — 빌드 시 --platform linux/arm64 지정
#   - 헬스체크: GET /health (30초 간격, timeout 5s, 재시도 3)
#
# 로컬(관리자 PC)에서는 Docker 없이 `npm start` 로 그냥 돌려도 된다.
# 이 파일은 나중에 ECR/ECS로 옮길 때 그대로 쓰기 위한 사전 준비물이다.
#
# ⚠ better-sqlite3 는 네이티브 모듈이라 alpine(musl) 에서 컴파일이 필요해
#   빌드 스테이지에 python3/make/g++ 를 넣는다. 보고팡 인프라로 이전하면서
#   저장소를 RDS(MariaDB)로 바꾸면 이 의존성 자체가 사라진다 — README 참조.

# ---------- build ----------
FROM node:22-alpine AS build
WORKDIR /app

RUN apk add --no-cache python3 make g++

COPY package.json package-lock.json ./
RUN npm ci --omit=dev

# ---------- runtime ----------
FROM node:22-alpine AS runtime
WORKDIR /app

ENV NODE_ENV=production \
    PORT=8090 \
    DATA_DIR=/data

# 컨테이너 파일시스템은 재배포마다 초기화된다. /data 는 반드시 외부 볼륨을
# 마운트할 것 — 안 하면 기기 이력(SQLite)과 업로드한 APK가 배포 때마다 사라진다.
VOLUME ["/data"]

COPY --from=build /app/node_modules ./node_modules
COPY package.json ./
COPY *.js ./

# root 로 돌리지 않는다(node 이미지에 기본 포함된 비특권 계정).
RUN mkdir -p /data && chown -R node:node /data /app
USER node

EXPOSE 8090

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:'+(process.env.PORT||8090)+'/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

CMD ["node", "server.js"]
