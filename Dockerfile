# ivy with its connector bundles, on the JVM.
#   docker build -t ivy .                            sql, kafka, redis, mongo, couchbase
#   docker build -t ivy:browser --target browser .   the same, plus the browser connector and Chromium
#   docker run --rm -v "$PWD:/work" ivy run tests/
#   docker run --rm -i -v "$PWD:/work" ivy mcp --workspace /work

# Java bytecode is the same on every architecture: build once, natively, for all target platforms
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
ARG IVY_VERSION=0.0.1
RUN ./gradlew --no-daemon -PivyVersion=${IVY_VERSION} :ivy-cli:quarkusBuild bundle -x test \
 && mkdir -p /out/bundles /out/browser \
 && cp -r ivy-cli/build/quarkus-app /out/app \
 && for c in sql kafka redis mongo couchbase; do cp connectors/$c/build/bundle/*.jar /out/bundles/; done \
 && cp connectors/browser/build/bundle/*.jar /out/browser/

FROM eclipse-temurin:25-jre AS ivy
LABEL org.opencontainers.image.title="ivy" \
      org.opencontainers.image.description="Declarative integration tests in YAML or Gherkin, with an MCP server" \
      org.opencontainers.image.source="https://github.com/fokion/ivy" \
      org.opencontainers.image.licenses="Apache-2.0"
COPY --from=build /out/app /opt/ivy/app
COPY --from=build /out/bundles /opt/ivy/bundles
COPY LICENSE NOTICE /opt/ivy/
RUN printf '#!/bin/sh\nexec java --enable-native-access=ALL-UNNAMED $JAVA_OPTS -jar /opt/ivy/app/quarkus-run.jar "$@"\n' \
      > /usr/local/bin/ivy \
 && chmod 0755 /usr/local/bin/ivy \
 && useradd --create-home --uid 10001 ivy \
 && mkdir /work && chown ivy /work
ENV IVY_BUNDLES_DIR=/opt/ivy/bundles \
    IS_TTY=false
USER ivy
WORKDIR /work
ENTRYPOINT ["ivy"]
CMD ["--help"]

FROM ivy AS browser
USER root
COPY --from=build /out/browser /opt/ivy/bundles
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/ivy/browsers
# Chromium and the system libraries it needs, installed with the Playwright of the bundle
RUN ivy browser install --with-deps chromium && chmod -R a+rX /opt/ivy/browsers
USER ivy

# the default target: ivy without the browser connector
FROM ivy
