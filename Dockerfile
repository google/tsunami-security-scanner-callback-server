# Stage 1: Build phase

FROM ubuntu:latest AS build

## Dependencies

RUN apt-get update \
 && apt-get install -y --no-install-recommends openjdk-21-jdk \
 && rm -rf /var/lib/apt/lists/* \
 && rm -rf /usr/share/doc && rm -rf /usr/share/man \
 && apt-get clean

# Compile the callback server

WORKDIR /usr/repos/tsunami-security-scanner-callback-server
COPY . .
RUN ./gradlew shadowJar
RUN mkdir /usr/tsunami
RUN find . -name 'tcs-main-*.jar' -exec cp {} /usr/tsunami/tsunami-tcs.jar \; \
    && cp tcs_config.yaml /usr/tsunami/tcs_config.yaml

## Stage 2: Release

FROM scratch AS release

# Copy previous build results
COPY --from=build /usr/tsunami/tsunami-tcs.jar /usr/tsunami/
COPY --from=build /usr/tsunami/tcs_config.yaml /usr/tsunami/
