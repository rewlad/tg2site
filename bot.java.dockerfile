FROM ubuntu:24.04 AS sk-downloader
RUN apt update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends curl ca-certificates xz-utils

FROM sk-downloader AS sk-dl-jdk
RUN curl -LO https://download.bell-sw.com/java/21.0.4+9/bellsoft-jdk21.0.4+9-linux-amd64.tar.gz
RUN tar xvf bellsoft-jdk21.0.4+9-linux-amd64.tar.gz && mv jdk-21.0.4 /result

FROM sk-downloader AS sk-dl-coursier
RUN curl -LO https://github.com/coursier/launchers/raw/master/coursier
RUN chmod +x coursier && mv coursier /result

FROM sk-downloader AS sk-build
COPY --chown=ubuntu:ubuntu --from=sk-dl-jdk /result /tools/jdk
COPY --chown=ubuntu:ubuntu --from=sk-dl-coursier result /tools/coursier
ENV PATH=${PATH}:/tools/:/tools/jdk/bin
WORKDIR /app
RUN coursier fetch --classpath tools.jackson.core:jackson-databind:3.0.2 > /app/classpath
RUN perl -e 'print join " ", "cp", `cat /app/classpath`=~/[^\s:]+/g, "."' | sh
COPY bot.java .
RUN javac --enable-preview -source 21 -cp '*' -d build bot.java && cd build && jar -cf ../bot.jar .

FROM ubuntu:24.04
RUN apt update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ca-certificates git && rm -rf /var/lib/apt/lists/*
USER ubuntu
COPY --chown=ubuntu:ubuntu --from=sk-dl-jdk /result /tools/jdk
COPY --chown=ubuntu:ubuntu --from=sk-build /app/*.jar /app/
ENV PATH=${PATH}:/tools/jdk/bin
ENTRYPOINT ["java","--enable-preview","-cp","/app/*","bot"]
