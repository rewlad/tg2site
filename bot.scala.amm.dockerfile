FROM ubuntu:24.04 AS sk-downloader
# download tooling in isolated stages so bumping one dependency never stalls the others
# separate downloader layer keeps curl/ca-certificates cacheable for all artifacts
# keep apt/download caches here for maximum layer reuse; only the final runtime image gets cleaned
RUN apt update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends curl ca-certificates xz-utils

FROM sk-downloader AS sk-dl-jdk
# pin BellSoft JDK tarball to keep runtime deterministic regardless of apt repo updates
RUN curl -LO https://download.bell-sw.com/java/21.0.4+9/bellsoft-jdk21.0.4+9-linux-amd64.tar.gz
RUN tar xvf bellsoft-jdk21.0.4+9-linux-amd64.tar.gz && mv jdk-21.0.4 /result

FROM sk-downloader AS sk-dl-amm
RUN curl -LO https://github.com/com-lihaoyi/Ammonite/releases/download/3.0.4/3.3-3.0.4
RUN echo "#!/usr/bin/env sh" > /result && cat 3.3-3.0.4 >> /result && chmod +x /result

FROM ubuntu:24.04
RUN apt update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ca-certificates git && rm -rf /var/lib/apt/lists/*
# reuse default ubuntu user (uid 1000) so runtime matches local non-root expectations
USER ubuntu
COPY --chown=ubuntu:ubuntu --from=sk-dl-jdk /result /tools/jdk
COPY --chown=ubuntu:ubuntu --from=sk-dl-amm /result /tools/amm
ENV PATH=${PATH}:/tools/:/tools/jdk/bin
ENTRYPOINT ["amm","bot.scala"]
COPY bot.scala /

