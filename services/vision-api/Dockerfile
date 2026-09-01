# syntax=docker/dockerfile:1.7

FROM golang:1.25.5-bookworm AS build

WORKDIR /src/services/vision-api

COPY services/vision-api/go.mod ./
RUN go mod download all

COPY services/vision-api/ ./
RUN go mod tidy && go mod verify
RUN CGO_ENABLED=0 GOOS=linux go build \
    -trimpath \
    -ldflags="-s -w" \
    -o /out/vision-api \
    ./cmd/vision-api
RUN CGO_ENABLED=0 GOOS=linux go build \
    -trimpath \
    -ldflags="-s -w" \
    -o /out/healthcheck \
    ./cmd/healthcheck

FROM gcr.io/distroless/static-debian12:nonroot AS runtime

COPY --from=build --chown=nonroot:nonroot /out/vision-api /vision-api
COPY --from=build --chown=nonroot:nonroot /out/healthcheck /healthcheck

USER nonroot:nonroot
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD ["/healthcheck"]

ENTRYPOINT ["/vision-api"]
