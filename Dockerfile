# Stage 1: Build phase

FROM ghcr.io/google/tsunami-scanner-devel:latest AS build

# Compile the callback server
WORKDIR /usr/repos/tsunami-security-scanner-callback-server
COPY . .
RUN gradle shadowJar
RUN mkdir -p /usr/tsunami
RUN find . -name 'tcs-main-*.jar' -exec cp {} /usr/tsunami/tsunami-tcs.jar \; \
    && cp tcs_config.yaml /usr/tsunami/tcs_config.yaml

# Compile proto definitions for Python plugins
RUN mkdir -p /usr/tsunami/py_server
RUN python3 -m grpc_tools.protoc \
  -I/usr/repos/tsunami-security-scanner-callback-server/proto \
  --python_out=/usr/tsunami/py_server/ \
  --grpc_python_out=/usr/tsunami/py_server/ \
  /usr/repos/tsunami-security-scanner-callback-server/proto/*.proto

## Stage 2: Release

FROM scratch AS release

# Copy previous build results
COPY --from=build /usr/tsunami/tsunami-tcs.jar /usr/tsunami/
COPY --from=build /usr/tsunami/tcs_config.yaml /usr/tsunami/
COPY --from=build /usr/tsunami/py_server/ /usr/tsunami/py_server
