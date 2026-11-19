/*
 *  Copyright 2026 Collate
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.openmetadata.mcp;

import static org.openmetadata.mcp.server.transport.HttpServletStatelessServerTransport.pathInContext;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Serves the fonts of the MCP Apps cards at {@link McpAppsResources#FONT_PATH}, without
 * authentication: a card's iframe cannot send the user's token, and the files are public fonts. A
 * filter rather than a servlet for the same reason as {@link OAuthWellKnownFilter}: the asset
 * servlet at {@code /*} answers every other path with the SPA shell.
 *
 * <p>Only the files listed here are served, and every response allows any origin: a card's iframe
 * has an opaque origin, and browsers fetch fonts in CORS mode.
 */
public class McpCardFontsFilter implements Filter {
  private static final String RESOURCE_DIR = "mcp-apps/fonts/";
  private static final List<String> FILES =
      List.of(
          "inter-latin.woff2",
          "inter-latin-ext.woff2",
          "roboto-mono-latin.woff2",
          "roboto-mono-latin-ext.woff2");

  private final Map<String, byte[]> fonts =
      FILES.stream().collect(Collectors.toMap(f -> f, f -> load(f)));

  @Override
  public void doFilter(
      final ServletRequest servletRequest,
      final ServletResponse servletResponse,
      final FilterChain filterChain)
      throws IOException, ServletException {
    final HttpServletRequest request = (HttpServletRequest) servletRequest;
    final HttpServletResponse response = (HttpServletResponse) servletResponse;
    final String path = pathInContext(request);
    final String prefix = McpAppsResources.FONT_PATH + "/";
    final byte[] font = path.startsWith(prefix) ? fonts.get(path.substring(prefix.length())) : null;
    if (font == null || !isReadMethod(request.getMethod())) {
      filterChain.doFilter(servletRequest, servletResponse);
      return;
    }
    response.setContentType("font/woff2");
    response.setContentLength(font.length);
    response.setHeader("Access-Control-Allow-Origin", "*");
    response.setHeader("Cross-Origin-Resource-Policy", "cross-origin");
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("Cache-Control", "public, max-age=604800");
    if ("GET".equals(request.getMethod())) {
      response.getOutputStream().write(font);
    }
  }

  private static boolean isReadMethod(final String method) {
    return "GET".equals(method) || "HEAD".equals(method);
  }

  private static byte[] load(final String file) {
    try (InputStream stream =
        McpCardFontsFilter.class.getClassLoader().getResourceAsStream(RESOURCE_DIR + file)) {
      if (stream == null) {
        throw new IllegalStateException("MCP card font missing from the classpath: " + file);
      }
      return stream.readAllBytes();
    } catch (IOException exception) {
      throw new java.io.UncheckedIOException("Cannot read MCP card font " + file, exception);
    }
  }
}
