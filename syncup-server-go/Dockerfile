FROM golang:1.25-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
ARG VERSION=dev
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w -X main.version=${VERSION}" \
    -o /out/syncup-server ./cmd/syncup-server

FROM alpine:3.22
RUN addgroup -S syncup && adduser -S -G syncup syncup
USER syncup
WORKDIR /app
COPY --from=build /out/syncup-server /usr/local/bin/syncup-server
ENV SYNCUP_STORAGE_ROOT=/data
VOLUME ["/data"]
EXPOSE 8500/tcp 9999/udp
ENTRYPOINT ["syncup-server"]
