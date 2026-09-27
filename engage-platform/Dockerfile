# One Dockerfile for every service. Pick the Gradle module with MODULE:
#   docker build --build-arg MODULE=ingest-api -t wumika/ingest-api .
#   docker build --build-arg MODULE=admin-api  -t wumika/admin-api  .

FROM eclipse-temurin:25-jdk AS build
ARG MODULE
WORKDIR /src
# Wrapper and build scripts first, so the Gradle download is cached
# independently of source changes.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
RUN sh ./gradlew --no-daemon --version
COPY . .
# Tests run in CI before the image is built; they need a Postgres this stage does not have.
RUN sh ./gradlew --no-daemon :${MODULE}:installDist -x test

FROM eclipse-temurin:25-jre
ARG MODULE
# Fixed uid: the admin key volume on the host is chowned to it (deploy/server-setup.sh).
RUN useradd --system --uid 10001 --home-dir /app engage
COPY --from=build /src/${MODULE}/build/install/${MODULE} /app
RUN ln -s /app/bin/${MODULE} /app/bin/start
USER engage
WORKDIR /app
ENTRYPOINT ["/app/bin/start"]
