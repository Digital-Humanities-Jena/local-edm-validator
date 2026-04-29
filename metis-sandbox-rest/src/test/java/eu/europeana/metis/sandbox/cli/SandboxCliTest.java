package eu.europeana.metis.sandbox.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SandboxCliTest {

  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void run_shouldExecuteOneShotGetCommand() throws Exception {
    AtomicReference<String> method = new AtomicReference<>();
    server = server(exchange -> {
      method.set(exchange.getRequestMethod());
      respond(exchange, 200, "[{\"name\":\"Europe\",\"xmlValue\":\"Europe\"}]");
    });

    ByteArrayOutputStream output = new ByteArrayOutputStream();
    SandboxCli cli = newCli(output, new ByteArrayOutputStream());

    int exitCode = cli.run(new String[]{"--base-url", baseUrl(), "countries"});

    assertEquals(0, exitCode);
    assertEquals("GET", method.get());
    String cliOutput = output.toString(StandardCharsets.UTF_8);
    assertTrue(cliOutput.contains("HTTP 200"));
    assertTrue(cliOutput.contains("\"xmlValue\" : \"Europe\""));
  }

  @Test
  void execute_shouldSendValidationMultipartRequest() throws Exception {
    Path xmlFile = Files.createTempFile("metis-sandbox-cli", ".xml");
    Files.writeString(xmlFile, "<rdf>record</rdf>", StandardCharsets.UTF_8);
    AtomicReference<String> requestBody = new AtomicReference<>();
    AtomicReference<String> contentType = new AtomicReference<>();
    server = server(exchange -> {
      contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
      requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      respond(exchange, 200, "{\"status\":\"ok\"}");
    });

    ByteArrayOutputStream output = new ByteArrayOutputStream();
    SandboxCli cli = newCli(output, new ByteArrayOutputStream());
    cli.execute("base-url " + baseUrl());

    assertTrue(cli.execute("validate " + xmlFile + " --country Greece --language Greek --continue-on-error"));

    assertTrue(contentType.get().startsWith("multipart/form-data; boundary=metis-sandbox-"));
    assertTrue(requestBody.get().contains("name=\"country\""));
    assertTrue(requestBody.get().contains("Greece"));
    assertTrue(requestBody.get().contains("name=\"language\""));
    assertTrue(requestBody.get().contains("Greek"));
    assertTrue(requestBody.get().contains("name=\"continueOnError\""));
    assertTrue(requestBody.get().contains("true"));
    assertTrue(requestBody.get().contains("name=\"recordToValidate\"; filename=\"" + xmlFile.getFileName() + "\""));
    assertTrue(requestBody.get().contains("Content-Type: application/xml"));
    assertTrue(requestBody.get().contains("<rdf>record</rdf>"));
    assertTrue(output.toString(StandardCharsets.UTF_8).contains("HTTP 200"));
  }

  @Test
  void run_shouldPreserveSpacedOptionValuesInOneShotCommands() throws Exception {
    Path xmlFile = Files.createTempFile("metis-sandbox-cli", ".xml");
    Files.writeString(xmlFile, "<rdf/>", StandardCharsets.UTF_8);
    AtomicReference<String> requestBody = new AtomicReference<>();
    server = server(exchange -> {
      requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      respond(exchange, 200, "{}");
    });

    SandboxCli cli = newCli(new ByteArrayOutputStream(), new ByteArrayOutputStream());

    int exitCode = cli.run(new String[]{
        "--base-url", baseUrl(),
        "validate", xmlFile.toString(),
        "--language", "Multilingual Content"
    });

    assertEquals(0, exitCode);
    assertTrue(requestBody.get().contains("Multilingual Content"));
  }

  @Test
  void execute_shouldReturnFalseForExit() throws Exception {
    SandboxCli cli = newCli(new ByteArrayOutputStream(), new ByteArrayOutputStream());

    assertFalse(cli.execute("exit"));
  }

  @Test
  void run_shouldKeepInteractiveShellOpenAfterCommandErrors() {
    ByteArrayOutputStream error = new ByteArrayOutputStream();
    SandboxCli cli = new SandboxCli(new ByteArrayInputStream("nonsense\nexit\n".getBytes(StandardCharsets.UTF_8)),
        new ByteArrayOutputStream(), error);

    int exitCode = cli.run(new String[0]);

    assertEquals(0, exitCode);
    assertTrue(error.toString(StandardCharsets.UTF_8).contains("Unknown command 'nonsense'"));
  }

  private SandboxCli newCli(ByteArrayOutputStream output, ByteArrayOutputStream error) {
    return new SandboxCli(new ByteArrayInputStream(new byte[0]), output, error);
  }

  private HttpServer server(ExchangeHandler handler) throws IOException {
    HttpServer createdServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    createdServer.createContext("/", exchange -> {
      try {
        handler.handle(exchange);
      } finally {
        exchange.close();
      }
    });
    createdServer.start();
    return createdServer;
  }

  private String baseUrl() {
    return "http://localhost:" + server.getAddress().getPort();
  }

  private static void respond(HttpExchange exchange, int statusCode, String body) throws IOException {
    byte[] responseBody = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(statusCode, responseBody.length);
    exchange.getResponseBody().write(responseBody);
  }

  @FunctionalInterface
  private interface ExchangeHandler {

    void handle(HttpExchange exchange) throws IOException;
  }
}
