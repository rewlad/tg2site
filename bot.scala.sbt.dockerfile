FROM ubuntu:24.04 AS sk-downloader
# download tooling in isolated stages so bumping one dependency never stalls the others
# separate downloader layer keeps curl/ca-certificates cacheable for all artifacts
# keep apt/download caches here for maximum layer reuse; only the final runtime image gets cleaned
RUN apt update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends curl ca-certificates xz-utils

FROM sk-downloader AS sk-dl-jdk
# pin BellSoft JDK tarball to keep runtime deterministic regardless of apt repo updates
RUN curl -LO https://download.bell-sw.com/java/21.0.4+9/bellsoft-jdk21.0.4+9-linux-amd64.tar.gz
RUN tar xvf bellsoft-jdk21.0.4+9-linux-amd64.tar.gz && mv jdk-21.0.4 /result

FROM sk-downloader AS sk-dl-sbt
RUN curl -LO https://github.com/sbt/sbt/releases/download/v1.11.7/sbt-1.11.7.tgz
RUN tar xvf sbt-1.11.7.tgz && mv sbt /result

FROM sk-downloader AS sk-build
COPY --from=sk-dl-jdk /result /tools/jdk
COPY --from=sk-dl-sbt /result /tools/sbt
ENV PATH=${PATH}:/tools/sbt/bin:/tools/jdk/bin
WORKDIR /app
RUN mkdir project \
 && echo 'addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.11.4")' > project/build.sbt \
 && echo 'scalaVersion := "3.7.2"' > build.sbt \
 && echo 'enablePlugins(JavaServerAppPackaging)' >> build.sbt \
 && echo 'libraryDependencies += "com.lihaoyi" %% "os-lib" % "0.11.6"' >> build.sbt \
 && echo 'libraryDependencies += "com.lihaoyi" %% "requests" % "0.9.0"' >> build.sbt \
 && echo 'libraryDependencies += "com.lihaoyi" %% "ujson" % "4.4.1"' >> build.sbt \
 && sbt update
COPY bot.scala .
RUN sbt stage

FROM ubuntu:24.04
RUN apt update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ca-certificates git && rm -rf /var/lib/apt/lists/*
# reuse default ubuntu user (uid 1000) so runtime matches local non-root expectations
USER ubuntu
COPY --chown=ubuntu:ubuntu --from=sk-dl-jdk /result /tools/jdk
COPY --chown=ubuntu:ubuntu --from=sk-build /app/target/universal/stage /app
ENV PATH=${PATH}:/tools/jdk/bin
ENTRYPOINT ["/app/bin/app"]
