/*
 * Copyright 2022 Google LLC
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

import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.flogger.GoogleLogger;
import com.google.tsunami.callbackserver.server.common.monitoring.TcsEventsObserver;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.dns.DatagramDnsQuery;
import io.netty.handler.codec.dns.DatagramDnsResponse;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.handler.codec.dns.DnsSection;
import java.net.InetAddress;
import java.util.Optional;

/** Base implementation of a Netty handler for serving DNS traffic. */
@SuppressWarnings("FutureReturnValueIgnored")
public abstract class DnsHandler extends SimpleChannelInboundHandler<DatagramDnsQuery> {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  protected final TcsEventsObserver tcsEventsObserver;

  private final String endpointName;
  private final RequestLogger requestLogger;

  protected DnsHandler(String endpointName, TcsEventsObserver tcsEventsObserver) {
    this(endpointName, RequestLogger.INSTANCE, tcsEventsObserver);
  }

  protected DnsHandler(
      String endpointName, RequestLogger requestLogger, TcsEventsObserver tcsEventsObserver) {
    checkArgument(!Strings.isNullOrEmpty(endpointName));
    this.endpointName = endpointName;
    this.requestLogger = checkNotNull(requestLogger);
    this.tcsEventsObserver = checkNotNull(tcsEventsObserver);
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, DatagramDnsQuery request) {
    var stopwatch = Stopwatch.createStarted();
    var clientAddr = requestLogger.logRequestAndGetClientAddr(endpointName, ctx, request);

    DatagramDnsResponse response;
    Optional<Exception> foundEx = Optional.empty();
    try {
      response = handleRequest(request, clientAddr);
    } catch (Exception ex) {
      logger.atSevere().withCause(ex).log(
          "Unable to handle DNS request on %s endpoint from IP %s",
          endpointName, clientAddr.getHostAddress());
      foundEx = Optional.of(ex);
      response = buildServerFailureResponse(request);
    }

    try {
      ctx.writeAndFlush(response);
    } finally {
      var responseCode = response.code();
      if (responseCode.equals(DnsResponseCode.NOERROR)) {
        tcsEventsObserver.onSuccessfullDnsRpc(endpointName, stopwatch.elapsed());
      } else {
        tcsEventsObserver.onFailedDnsRpc(endpointName, stopwatch.elapsed(), responseCode, foundEx);
      }
    }
  }

  private static DatagramDnsResponse buildServerFailureResponse(DatagramDnsQuery request) {
    DatagramDnsResponse response = buildBasicDnsResponse(request);
    response.setCode(DnsResponseCode.SERVFAIL);
    return response;
  }

  protected static DatagramDnsResponse buildBasicDnsResponse(DatagramDnsQuery request) {
    DatagramDnsResponse response =
        new DatagramDnsResponse(
            /* sender= */ request.recipient(), /* recipient= */ request.sender(), request.id());
    response.setAuthoritativeAnswer(true);
    response.addRecord(DnsSection.QUESTION, request.recordAt(DnsSection.QUESTION));
    return response;
  }

  /**
   * Handles the incoming DNS request and returns the DNS response.
   *
   * @param request the incoming HTTP request.
   * @return the DNS response message.
   * @throws Exception when there is a problem processing the DNS request.
   */
  protected abstract DatagramDnsResponse handleRequest(
      DatagramDnsQuery request, InetAddress clientAddr) throws Exception;
}
