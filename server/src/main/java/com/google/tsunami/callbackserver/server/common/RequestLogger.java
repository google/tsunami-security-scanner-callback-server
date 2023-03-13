/*
 * Copyright 2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.tsunami.callbackserver.server.common;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Strings;
import com.google.common.flogger.GoogleLogger;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.dns.DatagramDnsQuery;
import io.netty.handler.codec.http.FullHttpRequest;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Optional;

/** Defines utilities to log important information about a request. */
public final class RequestLogger {
  static final RequestLogger INSTANCE = new RequestLogger();

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  Optional<InetAddress> logRequestAndGetClientAddr(
      String endpointName, ChannelHandlerContext ctx, FullHttpRequest request) {
    checkArgument(!Strings.isNullOrEmpty(endpointName));
    checkNotNull(ctx);
    checkNotNull(request);

    var clientAddr = getClientAddr(ctx);
    return logRequestAndGetClientAddr(endpointName, Protocol.HTTP, clientAddr, request);
  }

  Optional<InetAddress> logRequestAndGetClientAddr(String endpointName, DatagramDnsQuery request) {
    checkArgument(!Strings.isNullOrEmpty(endpointName));
    checkNotNull(request);

    var clientAddr = getClientAddr(request);
    return logRequestAndGetClientAddr(endpointName, Protocol.DNS, clientAddr, request);
  }

  private Optional<InetAddress> logRequestAndGetClientAddr(
      String endpointName, Protocol protocol, Optional<InetAddress> clientAddr, Object request) {
    // Logging here will be quite a bit spammy (TCS deployments are usually internet exposed and
    // receive a lot of random traffic). Still we want these logs so that we can easily debug
    // interaction with scanners/scan targets or investigate incidents.
    logger.atInfo().log(
        "Received %s request on %s endpoint from IP %s: %s",
        protocol, endpointName, maybeGetClientAddrAsString(clientAddr), request);
    return clientAddr;
  }

  private Optional<InetAddress> getClientAddr(DatagramDnsQuery dnsQuery) {
    return Optional.ofNullable(dnsQuery.recipient().getAddress());
  }

  private Optional<InetAddress> getClientAddr(ChannelHandlerContext ctx) {
    var opaqueClientAddr = ctx.channel().remoteAddress();

    var isInetAddr = opaqueClientAddr instanceof InetSocketAddress;
    if (!isInetAddr) {
      // We don't want to fail hard here (with an exception or assertion) if we can't extract the
      // IP. We already had a bug that broke the DNS handler because we could not extract the source
      // IP from the channel in that case.
      //
      // Logging is not a reason strong enough to fail answering.
      logger.atSevere().log(
        "Non IP traffic received, IP could not be extracted (inetaddr type: %s)",
        opaqueClientAddr != null ? opaqueClientAddr.getClass().getName() : "n/a");

      return Optional.empty();
    }

    var socketAddress = (InetSocketAddress) opaqueClientAddr;
    return Optional.of(socketAddress.getAddress());
  }

  public static String maybeGetClientAddrAsString(Optional<InetAddress> clientAddr) {
    checkNotNull(clientAddr);
    return clientAddr.map(InetAddress::getHostAddress).orElse("n/a");
  }

  private enum Protocol {
    HTTP,
    DNS
  }
}
