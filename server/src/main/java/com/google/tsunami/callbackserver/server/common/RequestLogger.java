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

final class RequestLogger {
  static final RequestLogger INSTANCE = new RequestLogger(true);
  static final RequestLogger INSTANCE_FOR_TESTING = new RequestLogger(false);

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final boolean validateInetAddr;

  private RequestLogger(boolean validateInetAddr) {
    this.validateInetAddr = validateInetAddr;
  }

  InetAddress logRequestAndGetClientAddr(
      String endpointName, ChannelHandlerContext ctx, FullHttpRequest request) {
    return logRequestAndGetClientAddr(endpointName, Protocol.HTTP, ctx, request);
  }

  InetAddress logRequestAndGetClientAddr(
      String endpointName, ChannelHandlerContext ctx, DatagramDnsQuery request) {
    return logRequestAndGetClientAddr(endpointName, Protocol.DNS, ctx, request);
  }

  private InetAddress logRequestAndGetClientAddr(
      String endpointName, Protocol protocol, ChannelHandlerContext ctx, Object request) {
    checkArgument(!Strings.isNullOrEmpty(endpointName));
    checkNotNull(protocol);
    checkNotNull(ctx);
    checkNotNull(request);

    // Logging here will be quite a bit spammy (TCS deployments are usually internet exposed and
    // receive a lot of random traffic). Still we want these logs so that we can easily debug
    // interaction with scanners/scan targets or investigate incidents.
    var clientAddr = getClientAddr(ctx);
    logger.atInfo().log(
        "Received %s request on %s endpoint from IP %s: %s",
        protocol, endpointName, clientAddr.getHostAddress(), request);
    return clientAddr;
  }

  private InetAddress getClientAddr(ChannelHandlerContext ctx) {
    var opaqueClientAddr = ctx.channel().remoteAddress();

    var isInetAddr = opaqueClientAddr instanceof InetSocketAddress;
    if (!isInetAddr) {
      if (validateInetAddr) {
        throw new AssertionError("This should never happen, we only serve IP traffic");
      } else {
        // In unit tests it is way easier to create channels that don't use IP, and we don't want
        // these tests to fail.
        return InetAddress.getLoopbackAddress();
      }
    }

    var socketAddress = (InetSocketAddress) opaqueClientAddr;
    return socketAddress.getAddress();
  }

  private enum Protocol {
    HTTP,
    DNS
  }
}
