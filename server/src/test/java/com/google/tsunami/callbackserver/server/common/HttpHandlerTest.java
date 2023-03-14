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

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.google.protobuf.Empty;
import com.google.protobuf.Message;
import com.google.tsunami.callbackserver.server.common.monitoring.TcsEventsObserver;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Tests for {@link HttpHandler}. */
@RunWith(JUnit4.class)
public final class HttpHandlerTest {
  private static final String ENDPOINT_NAME = "TestHttpHandler";

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();
  @Mock TcsEventsObserver eventsObserverMock;

  @Test
  public void handleRequest_withValidRequest_returnsOk() {
    FullHttpResponse response = runRequest(new OkStatusHttpHandler());

    String expectedContent = "{\n}";
    assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
    assertThat(response.content().toString(UTF_8)).isEqualTo(expectedContent);
    assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE))
        .isEqualTo(HttpHeaderValues.APPLICATION_JSON.toString());
    assertThat(response.headers().get(HttpHeaderNames.CONTENT_LENGTH))
        .isEqualTo(String.valueOf(expectedContent.length()));
    verify(eventsObserverMock)
        .onSuccessfulHttpRpc(eq(ENDPOINT_NAME), any(Duration.class), eq(HttpResponseStatus.OK));
  }

  @Test
  public void handleRequest_withBadRequestError_returnsBadRequest() {
    FullHttpResponse response = runRequest(new BadRequestHttpHandler());

    String expectedContent = "Bad Request.";
    assertThat(response.status()).isEqualTo(HttpResponseStatus.BAD_REQUEST);
    assertThat(response.content().toString(UTF_8)).isEqualTo(expectedContent);
    assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE))
        .isEqualTo(HttpHeaderValues.TEXT_PLAIN.toString());
    assertThat(response.headers().get(HttpHeaderNames.CONTENT_LENGTH))
        .isEqualTo(String.valueOf(expectedContent.length()));
    verify(eventsObserverMock)
        .onFailedHttpRpc(
            eq(ENDPOINT_NAME),
            any(Duration.class),
            eq(HttpResponseStatus.BAD_REQUEST),
            any(Exception.class));
  }

  @Test
  public void handleRequest_withNotFoundError_returnsNotFound() {
    FullHttpResponse response = runRequest(new NotFoundHttpHandler());

    String expectedContent = "Not Found.";
    assertThat(response.status()).isEqualTo(HttpResponseStatus.NOT_FOUND);
    assertThat(response.content().toString(UTF_8)).isEqualTo(expectedContent);
    assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE))
        .isEqualTo(HttpHeaderValues.TEXT_PLAIN.toString());
    assertThat(response.headers().get(HttpHeaderNames.CONTENT_LENGTH))
        .isEqualTo(String.valueOf(expectedContent.length()));
    verify(eventsObserverMock)
        .onFailedHttpRpc(
            eq(ENDPOINT_NAME),
            any(Duration.class),
            eq(HttpResponseStatus.NOT_FOUND),
            any(Exception.class));
  }

  @Test
  public void handleRequest_withInternalError_returnsInternalError() {
    FullHttpResponse response = runRequest(new InternalErrorHttpHandler());

    String expectedContent = "Server Error.";
    assertThat(response.status()).isEqualTo(HttpResponseStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.content().toString(UTF_8)).isEqualTo(expectedContent);
    assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE))
        .isEqualTo(HttpHeaderValues.TEXT_PLAIN.toString());
    assertThat(response.headers().get(HttpHeaderNames.CONTENT_LENGTH))
        .isEqualTo(String.valueOf(expectedContent.length()));
    assertThat(response.headers().get(HttpHeaderNames.CONTENT_LENGTH))
        .isEqualTo(String.valueOf(expectedContent.length()));
    verify(eventsObserverMock)
        .onFailedHttpRpc(
            eq(ENDPOINT_NAME),
            any(Duration.class),
            eq(HttpResponseStatus.INTERNAL_SERVER_ERROR),
            any(Exception.class));
  }

  private static FullHttpResponse runRequest(HttpHandler handler) {
    EmbeddedChannel channel = new EmbeddedChannel(handler);
    channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
    return channel.readOutbound();
  }

  private abstract class BaseTestHttpHandler extends HttpHandler {
    BaseTestHttpHandler() {
      super(
          ENDPOINT_NAME,
          HttpHandler.LogNotFoundEx.DONT_LOG,
          RequestLogger.INSTANCE,
          eventsObserverMock);
    }
  }

  private class OkStatusHttpHandler extends BaseTestHttpHandler {
    @Override
    protected Message handleRequest(FullHttpRequest request, Optional<InetAddress> clientAddr)
        throws Exception {
      return Empty.getDefaultInstance();
    }
  }

  private class BadRequestHttpHandler extends BaseTestHttpHandler {
    @Override
    protected Message handleRequest(FullHttpRequest request, Optional<InetAddress> clientAddr)
        throws Exception {
      throw new IllegalArgumentException();
    }
  }

  private class NotFoundHttpHandler extends BaseTestHttpHandler {
    @Override
    protected Message handleRequest(FullHttpRequest request, Optional<InetAddress> clientAddr)
        throws Exception {
      throw new NotFoundException("");
    }
  }

  private class InternalErrorHttpHandler extends BaseTestHttpHandler {
    @Override
    protected Message handleRequest(FullHttpRequest request, Optional<InetAddress> clientAddr)
        throws Exception {
      throw new RuntimeException();
    }
  }
}
