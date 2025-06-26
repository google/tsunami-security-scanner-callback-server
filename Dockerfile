# Stage 1: Build phase

FROM ghcr.io/google/tsunami-scanner-devel:latest AS build

# Compile the callback server

WORKDIR /usr/repos/tsunami-security-scanner-callback-server
COPY . .
RUN gradle shadowJar
RUN mkdir /usr/tsunami
RUN find . -name 'tcs-main-*.jar' -exec cp {} /usr/tsunami/tsunami-tcs.jar \; \
    && cp tcs_config.yaml /usr/tsunami/tcs_config.yaml

## Stage 2: Release

FROM scratch AS release

# Copy previous build results
COPY --from=build /usr/tsunami/tsunami-tcs.jar /usr/tsunami/
COPY --from=build /usr/tsunami/tcs_config.yaml /usr/tsunami/
