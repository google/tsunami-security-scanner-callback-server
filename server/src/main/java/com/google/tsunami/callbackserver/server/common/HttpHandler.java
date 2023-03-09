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
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Strings;
import com.google.common.flogger.GoogleLogger;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.net.InetAddress;

/** Base implementation of a Netty handler for serving HTTP traffic. */
@SuppressWarnings("FutureReturnValueIgnored")
public abstract class HttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final String endpointName;
  private final RequestLogger requestLogger;
  private final LogNotFoundEx logNotFound;

  /** Controls whether or not this handler logs NotFound exceptions. */
  protected enum LogNotFoundEx {
    LOG,
    DONT_LOG;
  }

  protected HttpHandler(String endpointName) {
    this(endpointName, RequestLogger.INSTANCE, LogNotFoundEx.LOG);
  }

  protected HttpHandler(String endpointName, LogNotFoundEx logNotFound) {
    this(endpointName, RequestLogger.INSTANCE, logNotFound);
  }

  protected HttpHandler(
    String endpointName, RequestLogger requestLogger, LogNotFoundEx logNotFound) {
    checkArgument(!Strings.isNullOrEmpty(endpointName));
    checkNotNull(requestLogger);
    checkNotNull(logNotFound);
    this.endpointName = endpointName;
    this.requestLogger = requestLogger;
    this.logNotFound = logNotFound;
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
    var clientAddr = requestLogger.logRequestAndGetClientAddr(endpointName, ctx, request);
    try {
      String responseContent = JsonFormat.printer().print(handleRequest(request, clientAddr));
      replyJson(ctx, responseContent);
    } catch (NotFoundException ex) {
      // Logging not found exceptions is conditional since in some cases (see the polling endpoint)
      // the majority of requests will actually return this.
      if (logNotFound == LogNotFoundEx.LOG) {
        logger.atSevere().withCause(ex).log(
          "Unable to handle HTTP request on %s endpoint from IP %s",
          endpointName, clientAddr.getHostAddress());
      }
      replyNotFound(ctx);
    } catch (Exception ex) {
      logger.atSevere().withCause(ex).log(
          "Unable to handle HTTP request on %s endpoint from IP %s",
          endpointName, clientAddr.getHostAddress());
      if (ex instanceof IllegalArgumentException) {
        replyBadRequest(ctx);
      } else {
        replyInternalError(ctx);
      }
    }
  }

  private void replyJson(ChannelHandlerContext ctx, String jsonContent) {
    ctx.writeAndFlush(
        buildResponse(HttpResponseStatus.OK, jsonContent, HttpHeaderValues.APPLICATION_JSON));
  }

  private void replyBadRequest(ChannelHandlerContext ctx) {
    ctx.writeAndFlush(
        buildResponse(HttpResponseStatus.BAD_REQUEST, "Bad Request.", HttpHeaderValues.TEXT_PLAIN));
  }

  private void replyNotFound(ChannelHandlerContext ctx) {
    ctx.writeAndFlush(
        buildResponse(HttpResponseStatus.NOT_FOUND, "Not Found.", HttpHeaderValues.TEXT_PLAIN));
  }

  private void replyInternalError(ChannelHandlerContext ctx) {
    ctx.writeAndFlush(
        buildResponse(
            HttpResponseStatus.INTERNAL_SERVER_ERROR,
            "Server Error.",
            HttpHeaderValues.TEXT_PLAIN));
  }

  private FullHttpResponse buildResponse(
      HttpResponseStatus status, CharSequence content, CharSequence contentType) {
    FullHttpResponse response =
        new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, status, Unpooled.copiedBuffer(content, UTF_8));
    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
    response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
    return response;
  }

  /**
   * Handles the incoming HTTP request and returns a protobuf message as the response body.
   *
   * @param request the incoming HTTP request.
   * @return the HTTP response body in protobuf format.
   * @throws Exception when there is a problem processing the HTTP request.
   */
  protected abstract Message handleRequest(FullHttpRequest request, InetAddress clientAddr)
      throws Exception;
}
