# Tsunami Callback Server

## Introduction

Tsunami Callback Server is a network service that Tsunami uses to help discover
blind vulnerabilities such as server-side request forgery (SSRF) and blind RCE.

## Design

Tsunami callback server is a standalone application that can receive both http requests and DNS queries.  A tsunami plugin can now check against the callback server to see if there are any out-of-bound interactions triggered from the detection stage.

Internally, to identify interactions per plugin, [tsunami payload generator](https://github.com/google/tsunami-security-scanner/tree/master/plugin/src/main/java/com/google/tsunami/plugin/payload) generates a unique id and returns the hash (SHA3 one-way hash by default) of the id with a callback server address for plugin to use and the callback server logs the hashed id from incoming requests. At the end of the detection, [TcsClient](https://github.com/google/tsunami-security-scanner/blob/2c8ac4fa4112aeaade3147890af10071901118dc/plugin/src/main/java/com/google/tsunami/plugin/TcsClient.java#L109) queries the callback server about the original unhashed id. Callback server then applies the same hash on the id, and returns logged interactions if they exist.

## Run Tsunami Scanner with Callback Server

### Running Callback Server Locally

#### HTTP callback

Clone the repository and build the jar file.

```
git clone https://github.com/google/tsunami-security-scanner-callback-server
cd tsunami-security-scanner-callback-server/
./gradlew shadowJar
```

Start callback server

 * With default settings:

```sh
java -cp "main/build/libs/tcs-main-[version]-cli.jar"  com.google.tsunami.callbackserver.main.TcsMain
```

 * With the example [config file](https://github.com/google/tsunami-security-scanner-callback-server/blob/master/tcs_config.yaml):

```sh
java \
    -cp "main/build/libs/tcs-main-0.0.1-SNAPSHOT-cli.jar" \
    com.google.tsunami.callbackserver.main.TcsMain \
    --custom-config=tcs_config.yaml
```

Configure Tsunami scan to use the local callback server by adding the followings to the [Tsunami config file](https://github.com/google/tsunami-security-scanner/blob/master/tsunami.yaml):

```yaml
plugin:
  callbackserver:
    callback_address: "127.0.0.1"  # Running callback server locally
    callback_port: 8881            # Make sure to match with ones configured in tcs_config.yaml
    polling_uri: "http://127.0.0.1:8880"
```

Then run Tsunami following the instructions [here](https://github.com/google/tsunami-security-scanner/blob/master/docs/howto.md#build_n_execute) using the modified tsunami.yaml file.

#### DNS Callback

> :warning: Testing DNS callback locally requires modifying your iptables to
reroute DNS queries to the port that callback DNS server is running at. You
won't be able to resolve other domains once the iptables are updated.

Update the callback server [config file](https://github.com/google/tsunami-security-scanner-callback-server/blob/master/tcs_config.yaml) to also spin up a DNS server:

```yaml
common:
  domain: cb.tsunami
  external_ip: 127.0.0.1
storage:
  in_memory:
    interaction_ttl_secs: 43200  # 12 hours
    cleanup_interval_secs: 3600  # 1 hour
recording:
  http:
    port: 8881
    worker_pool_size: 2
  dns:                           # Config for DNS server
    port: 8883                   # Port to reroute DNS query to
    worker_pool_size: 2
polling:
  port: 8880
  worker_pool_size: 2
```

Start the callback server as before, now you should see a DNS server turned
up as well.

Create 2 new rules in your local iptables to reroute DNS queries to the port where your own DNS server is running at:

```sh
sudo iptables -t nat -A OUTPUT -p udp --dport 53 -j DNAT --to-destination 127.0.0.1:8883
sudo iptables -t nat -A OUTPUT -p tcp --dport 53 -j DNAT --to-destination 127.0.0.1:8883
```

Now if you try to resolve the custom domain you set up with `dig cb.tsunami`, it
should resolve to `127.0.0.1`.

Update `callback_address` field in the [Tsunami config file](https://github.com/google/tsunami-security-scanner/blob/master/tsunami.yaml) to your custom domain to be used by tsunami payload generator.

Then run a Tsunami scan as before, now the payload generator will generate all the tsunami callback payloads using the configured domain, which will send DNS queries to the local DNS server you set up.

Don't forget to remove these iptables rules once you are done, otherwise you won't be able to resolve any other domain names properly.

```sh
sudo iptables -t nat -D OUTPUT -p udp --dport 53 -j DNAT --to-destination 127.0.0.1:8883
sudo iptables -t nat -D OUTPUT -p tcp --dport 53 -j DNAT --to-destination 127.0.0.1:8883
```

## Interacting with the Callback Server

1. Generating a secret, it should be an unique string

```sh
SECRET="<uniq_id>"
```

2. Create a SHA3 hashed callback ID

```sh
CBID=$(printf "${SECRET}" | openssl sha3-224 -binary | xxd -p)
```

3.1 Call callback server using HTTP

```sh
curl http://<callback_server_addr>:<recording_port>/${CBID}
```

3.2 Call callback server using DNS

```sh
dig ${CBID}.<callback_server_addr>
```

4. Verify callback is recorded using the original (unhashed) string.

```sh
curl "http://<callback_server_addr>:<polling_port>/?secret=${SECRET}"
```

## Contributing

Read how to [contribute to Tsunami](docs/contributing.md).

## License

Tsunami CallbackServer is released under the [Apache 2.0 license](LICENSE).

```
Copyright 2022 Google LLC.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

## Disclaimers

Tsunami is not an official Google product.
