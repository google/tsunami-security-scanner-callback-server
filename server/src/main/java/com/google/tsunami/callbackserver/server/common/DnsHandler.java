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

import com.google.common.flogger.GoogleLogger;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.dns.DatagramDnsQuery;
import io.netty.handler.codec.dns.DatagramDnsResponse;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.handler.codec.dns.DnsSection;

/** Base implementation of a Netty handler for serving DNS traffic. */
@SuppressWarnings("FutureReturnValueIgnored")
public abstract class DnsHandler extends SimpleChannelInboundHandler<DatagramDnsQuery> {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, DatagramDnsQuery request) {
    logger.atInfo().log("Received DNS request %s", request);

    DatagramDnsResponse response;
    try {
      response = handleRequest(request);
    } catch (Exception e) {
      response = buildServerFailureResponse(request);
    }
    ctx.writeAndFlush(response);
  }

  private static DatagramDnsResponse buildServerFailureResponse(DatagramDnsQuery request) {
    DatagramDnsResponse response = buildBasicDnsResponse(request);
    response.setCode(DnsResponseCode.SERVFAIL);
    return response;
  }

  protected static DatagramDnsResponse buildBasicDnsResponse(DatagramDnsQuery request) {
    DatagramDnsResponse response =
        new DatagramDnsResponse(
            /*sender=*/ request.recipient(), /*recipient=*/ request.sender(), request.id());
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
  protected abstract DatagramDnsResponse handleRequest(DatagramDnsQuery request) throws Exception;
}
