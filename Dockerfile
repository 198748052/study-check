# 研读后端 · 多阶段构建
# 构建：docker build -t studycheck --build-arg VERSION=1.4.0 .
FROM golang:1.26-alpine AS build
WORKDIR /src
ENV CGO_ENABLED=0
COPY server/go.mod server/go.sum ./
RUN go mod download
COPY server/ ./
ARG VERSION=dev
RUN go build -trimpath -ldflags "-s -w -X main.version=${VERSION}" -o /out/studycheck .

FROM alpine:3.20
RUN apk add --no-cache ca-certificates tzdata \
 && adduser -D -H -u 10001 -s /sbin/nologin studycheck \
 && mkdir -p /data/uploads \
 && chown -R studycheck:studycheck /data

COPY --from=build /out/studycheck /usr/local/bin/studycheck

USER studycheck
ENV SC_ADDR=0.0.0.0:8080 \
    SC_DB=/data/studycheck.db \
    SC_UPLOADS=/data/uploads \
    SC_TZ=Asia/Shanghai \
    SC_UPDATE_ENABLED=false

VOLUME ["/data"]
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s \
  CMD wget -q -O - http://127.0.0.1:8080/api/v1/health >/dev/null || exit 1

ENTRYPOINT ["/usr/local/bin/studycheck"]
