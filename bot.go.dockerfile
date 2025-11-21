FROM ubuntu:24.04 AS sk-downloader
RUN apt update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends curl ca-certificates xz-utils

FROM sk-downloader AS sk-dl-go
RUN curl -LO https://go.dev/dl/go1.25.4.linux-amd64.tar.gz
RUN tar xvf go1.25.4.linux-amd64.tar.gz && mv go /result

FROM sk-downloader AS sk-build
COPY --chown=ubuntu:ubuntu --from=sk-dl-go /result /tools/go
ENV PATH=${PATH}:/tools/go/bin
WORKDIR /app
COPY bot.go .
RUN go build bot.go

FROM ubuntu:24.04
RUN apt update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ca-certificates git && rm -rf /var/lib/apt/lists/*
USER ubuntu
COPY --chown=ubuntu:ubuntu --from=sk-build /app/bot /app/bot
ENTRYPOINT ["/app/bot"]
