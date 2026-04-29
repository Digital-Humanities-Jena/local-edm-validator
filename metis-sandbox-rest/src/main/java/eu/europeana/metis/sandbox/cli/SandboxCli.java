package eu.europeana.metis.sandbox.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class SandboxCli {

  private static final String DEFAULT_BASE_URL = "http://localhost:8080";
  private static final String DEFAULT_COUNTRY = "Europe";
  private static final String DEFAULT_LANGUAGE = "Multilingual Content";

  private final BufferedReader input;
  private final PrintWriter out;
  private final PrintWriter err;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final ObjectWriter jsonWriter;

  private String baseUrl;
  private String bearerToken;

  public SandboxCli(InputStream inputStream, OutputStream outputStream, OutputStream errorStream) {
    this(inputStream, outputStream, errorStream, HttpClient.newBuilder()
                                                          .connectTimeout(Duration.ofSeconds(15))
                                                          .build(), DEFAULT_BASE_URL);
  }

  SandboxCli(InputStream inputStream, OutputStream outputStream, OutputStream errorStream, HttpClient httpClient,
      String baseUrl) {
    this.input = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
    this.out = new PrintWriter(outputStream, true, StandardCharsets.UTF_8);
    this.err = new PrintWriter(errorStream, true, StandardCharsets.UTF_8);
    this.httpClient = httpClient;
    this.objectMapper = new ObjectMapper();
    this.jsonWriter = objectMapper.writerWithDefaultPrettyPrinter();
    this.baseUrl = normalizeBaseUrl(baseUrl);
  }

  public int run(String[] args) {
    try {
      List<String> commandTokens = applyGlobalOptions(args);
      if (!commandTokens.isEmpty()) {
        execute(ParsedCommand.from(commandTokens));
        return 0;
      }

      out.println("Metis Sandbox CLI");
      out.println("Type 'help' for commands. Type 'exit' to quit.");
      String line;
      while (true) {
        out.print("metis-sandbox> ");
        out.flush();
        line = input.readLine();
        if (line == null) {
          break;
        }
        try {
          if (!execute(line)) {
            break;
          }
        } catch (IllegalArgumentException e) {
          err.println(e.getMessage());
        } catch (IOException e) {
          err.println("I/O error: " + e.getMessage());
        }
      }
      return 0;
    } catch (IllegalArgumentException e) {
      err.println(e.getMessage());
      return 2;
    } catch (IOException e) {
      err.println("I/O error: " + e.getMessage());
      return 1;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      err.println("Interrupted");
      return 130;
    }
  }

  boolean execute(String line) throws IOException, InterruptedException {
    List<String> tokens = tokenize(line);
    if (tokens.isEmpty()) {
      return true;
    }

    ParsedCommand command = ParsedCommand.from(tokens);
    return execute(command);
  }

  private boolean execute(ParsedCommand command) throws IOException, InterruptedException {
    return switch (command.name()) {
      case "help", "?" -> {
        printHelp();
        yield true;
      }
      case "exit", "quit" -> false;
      case "base-url" -> {
        handleBaseUrl(command);
        yield true;
      }
      case "token" -> {
        handleToken(command);
        yield true;
      }
      case "countries" -> {
        sendGet("/dataset/countries");
        yield true;
      }
      case "languages" -> {
        sendGet("/dataset/languages");
        yield true;
      }
      case "progress" -> {
        requireArgumentCount(command, 1);
        sendGet("/dataset/" + encodePathSegment(command.arguments().getFirst()) + "/progress");
        yield true;
      }
      case "info" -> {
        requireArgumentCount(command, 1);
        sendGet("/dataset/" + encodePathSegment(command.arguments().getFirst()) + "/info");
        yield true;
      }
      case "validate" -> {
        validate(command);
        yield true;
      }
      case "validate-batch" -> {
        validateBatch(command);
        yield true;
      }
      case "harvest-file" -> {
        harvestFile(command);
        yield true;
      }
      case "harvest-url" -> {
        harvestUrl(command);
        yield true;
      }
      case "harvest-oai" -> {
        harvestOai(command);
        yield true;
      }
      default -> throw new IllegalArgumentException("Unknown command '" + command.name() + "'. Type 'help' for commands.");
    };
  }

  private List<String> applyGlobalOptions(String[] args) {
    List<String> commandTokens = new ArrayList<>();
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      if (arg.startsWith("--base-url=")) {
        baseUrl = normalizeBaseUrl(arg.substring("--base-url=".length()));
      } else if ("--base-url".equals(arg)) {
        requireOptionValue(args, i, "--base-url");
        baseUrl = normalizeBaseUrl(args[++i]);
      } else if (arg.startsWith("--token=")) {
        bearerToken = arg.substring("--token=".length());
      } else if ("--token".equals(arg)) {
        requireOptionValue(args, i, "--token");
        bearerToken = args[++i];
      } else if ("--help".equals(arg) || "-h".equals(arg)) {
        commandTokens.add("help");
      } else {
        commandTokens.add(arg);
      }
    }
    return commandTokens;
  }

  private static void requireOptionValue(String[] args, int currentIndex, String optionName) {
    if (currentIndex + 1 >= args.length) {
      throw new IllegalArgumentException(optionName + " requires a value");
    }
  }

  private void printHelp() {
    out.println("""
        Commands:
          base-url [url]                         Show or set the REST API base URL.
          token [value|clear]                    Set or clear a bearer token for API calls.
          countries                              List accepted country values.
          languages                              List accepted language values.
          progress <dataset-id>                  Show dataset execution progress.
          info <dataset-id>                      Show dataset information.
          validate <xml-file> [options]          Validate one RDF/XML record.
          validate-batch <xml-file>... [options] Validate multiple RDF/XML records.
          harvest-file <name> <archive> [options]
                                                 Submit a compressed dataset file.
          harvest-url <name> <url> [options]     Submit a compressed dataset URL.
          harvest-oai <name> <url> [options]     Submit an OAI-PMH harvest.
          help                                   Show this help.
          exit                                   Quit the interactive shell.

        Options:
          --country=<value>                      Default: Europe
          --language=<value>                     Default: Multilingual Content
          --continue-on-error                    Validation only.
          --stepsize=<number>                    Harvest only. Default: 1
          --metadataformat=<value>               Required for harvest-oai.
          --setspec=<value>                      Optional for harvest-oai.
          --xslt=<file>                          Optional harvest XSLT file.
        """);
  }

  private void handleBaseUrl(ParsedCommand command) {
    if (command.arguments().isEmpty()) {
      out.println(baseUrl);
      return;
    }
    requireArgumentCount(command, 1);
    baseUrl = normalizeBaseUrl(command.arguments().getFirst());
    out.println("Base URL set to " + baseUrl);
  }

  private void handleToken(ParsedCommand command) {
    if (command.arguments().isEmpty()) {
      out.println(bearerToken == null || bearerToken.isBlank() ? "No bearer token configured." : "Bearer token configured.");
      return;
    }
    requireArgumentCount(command, 1);
    if ("clear".equalsIgnoreCase(command.arguments().getFirst())) {
      bearerToken = null;
      out.println("Bearer token cleared.");
      return;
    }
    bearerToken = command.arguments().getFirst();
    out.println("Bearer token configured.");
  }

  private void validate(ParsedCommand command) throws IOException, InterruptedException {
    requireArgumentCount(command, 1);
    Map<String, String> fields = validationFields(command);
    List<FilePart> files = List.of(new FilePart("recordToValidate", Path.of(command.arguments().getFirst())));
    sendMultipart("/record/validation", fields, files);
  }

  private void validateBatch(ParsedCommand command) throws IOException, InterruptedException {
    if (command.arguments().isEmpty()) {
      throw new IllegalArgumentException("validate-batch requires at least one XML file");
    }
    Map<String, String> fields = validationFields(command);
    List<FilePart> files = command.arguments().stream()
                                  .map(file -> new FilePart("recordsToValidate", Path.of(file)))
                                  .toList();
    sendMultipart("/record/validation/batch", fields, files);
  }

  private Map<String, String> validationFields(ParsedCommand command) {
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("country", command.option("country").orElse(DEFAULT_COUNTRY));
    fields.put("language", command.option("language").orElse(DEFAULT_LANGUAGE));
    fields.put("continueOnError", Boolean.toString(command.hasFlag("continue-on-error")));
    return fields;
  }

  private void harvestFile(ParsedCommand command) throws IOException, InterruptedException {
    requireArgumentCount(command, 2);
    Map<String, String> fields = harvestFields(command);
    List<FilePart> files = new ArrayList<>();
    files.add(new FilePart("dataset", Path.of(command.arguments().get(1))));
    addOptionalXslt(command, files);
    sendMultipart("/dataset/" + encodePathSegment(command.arguments().getFirst()) + "/harvestByFile", fields, files);
  }

  private void harvestUrl(ParsedCommand command) throws IOException, InterruptedException {
    requireArgumentCount(command, 2);
    Map<String, String> fields = harvestFields(command);
    fields.put("url", command.arguments().get(1));
    List<FilePart> files = new ArrayList<>();
    addOptionalXslt(command, files);
    sendMultipart("/dataset/" + encodePathSegment(command.arguments().getFirst()) + "/harvestByUrl", fields, files);
  }

  private void harvestOai(ParsedCommand command) throws IOException, InterruptedException {
    requireArgumentCount(command, 2);
    Map<String, String> fields = harvestFields(command);
    fields.put("url", command.arguments().get(1));
    fields.put("metadataformat", command.option("metadataformat")
                                        .orElseThrow(() -> new IllegalArgumentException(
                                            "harvest-oai requires --metadataformat=<value>")));
    command.option("setspec").ifPresent(value -> fields.put("setspec", value));
    List<FilePart> files = new ArrayList<>();
    addOptionalXslt(command, files);
    sendMultipart("/dataset/" + encodePathSegment(command.arguments().getFirst()) + "/harvestOaiPmh", fields, files);
  }

  private Map<String, String> harvestFields(ParsedCommand command) {
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("country", command.option("country").orElse(DEFAULT_COUNTRY));
    fields.put("language", command.option("language").orElse(DEFAULT_LANGUAGE));
    fields.put("stepsize", command.option("stepsize").orElse("1"));
    return fields;
  }

  private void addOptionalXslt(ParsedCommand command, List<FilePart> files) {
    command.option("xslt").ifPresent(file -> files.add(new FilePart("xsltFile", Path.of(file))));
  }

  private void sendGet(String path) throws IOException, InterruptedException {
    HttpRequest.Builder requestBuilder = requestBuilder(path).GET();
    send(requestBuilder.build());
  }

  private void sendMultipart(String path, Map<String, String> fields, List<FilePart> files)
      throws IOException, InterruptedException {
    String boundary = "metis-sandbox-" + UUID.randomUUID();
    byte[] body = multipartBody(boundary, fields, files);
    HttpRequest request = requestBuilder(path)
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        .build();
    send(request);
  }

  private void send(HttpRequest request) throws IOException, InterruptedException {
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    out.println("HTTP " + response.statusCode());
    if (!response.body().isBlank()) {
      out.println(formatBody(response));
    }
  }

  private HttpRequest.Builder requestBuilder(String path) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                                             .timeout(Duration.ofMinutes(2))
                                             .header("Accept", "application/json");
    if (bearerToken != null && !bearerToken.isBlank()) {
      String tokenHeader = bearerToken.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())
          ? bearerToken
          : "Bearer " + bearerToken;
      builder.header("Authorization", tokenHeader);
    }
    return builder;
  }

  private String formatBody(HttpResponse<String> response) {
    String contentType = response.headers().firstValue("Content-Type").orElse("");
    if (!contentType.toLowerCase(Locale.ROOT).contains("json")) {
      return response.body();
    }
    try {
      return jsonWriter.writeValueAsString(objectMapper.readTree(response.body()));
    } catch (IOException e) {
      return response.body();
    }
  }

  private static byte[] multipartBody(String boundary, Map<String, String> fields, List<FilePart> files) throws IOException {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    for (Map.Entry<String, String> field : fields.entrySet()) {
      writeAscii(body, "--" + boundary + "\r\n");
      writeAscii(body, "Content-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n");
      body.write(field.getValue().getBytes(StandardCharsets.UTF_8));
      writeAscii(body, "\r\n");
    }
    for (FilePart file : files) {
      if (!Files.isRegularFile(file.path())) {
        throw new IllegalArgumentException("File does not exist: " + file.path());
      }
      String fileName = file.path().getFileName().toString();
      writeAscii(body, "--" + boundary + "\r\n");
      writeAscii(body, "Content-Disposition: form-data; name=\"" + file.fieldName() + "\"; filename=\"" + fileName + "\"\r\n");
      writeAscii(body, "Content-Type: " + contentType(file.path()) + "\r\n\r\n");
      body.write(Files.readAllBytes(file.path()));
      writeAscii(body, "\r\n");
    }
    writeAscii(body, "--" + boundary + "--\r\n");
    return body.toByteArray();
  }

  private static String contentType(Path path) throws IOException {
    String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
    if (fileName.endsWith(".xml") || fileName.endsWith(".xslt")) {
      return "application/xml";
    }
    String probed = Files.probeContentType(path);
    return probed == null || probed.isBlank() ? "application/octet-stream" : probed;
  }

  private static void writeAscii(ByteArrayOutputStream outputStream, String value) throws IOException {
    outputStream.write(value.getBytes(StandardCharsets.US_ASCII));
  }

  private static void requireArgumentCount(ParsedCommand command, int expectedCount) {
    if (command.arguments().size() != expectedCount) {
      throw new IllegalArgumentException(command.name() + " requires " + expectedCount + " argument(s)");
    }
  }

  private static String normalizeBaseUrl(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Base URL must not be blank");
    }
    String trimmed = value.strip();
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    URI uri = URI.create(trimmed);
    if (uri.getScheme() == null || uri.getHost() == null) {
      throw new IllegalArgumentException("Base URL must include a scheme and host");
    }
    return trimmed;
  }

  private static String encodePathSegment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static List<String> tokenize(String line) {
    List<String> tokens = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inSingleQuotes = false;
    boolean inDoubleQuotes = false;
    boolean escaping = false;
    for (int i = 0; i < line.length(); i++) {
      char character = line.charAt(i);
      if (escaping) {
        current.append(character);
        escaping = false;
      } else if (character == '\\') {
        escaping = true;
      } else if (character == '\'' && !inDoubleQuotes) {
        inSingleQuotes = !inSingleQuotes;
      } else if (character == '"' && !inSingleQuotes) {
        inDoubleQuotes = !inDoubleQuotes;
      } else if (Character.isWhitespace(character) && !inSingleQuotes && !inDoubleQuotes) {
        if (!current.isEmpty()) {
          tokens.add(current.toString());
          current.setLength(0);
        }
      } else {
        current.append(character);
      }
    }
    if (escaping) {
      current.append('\\');
    }
    if (inSingleQuotes || inDoubleQuotes) {
      throw new IllegalArgumentException("Unterminated quoted value");
    }
    if (!current.isEmpty()) {
      tokens.add(current.toString());
    }
    return tokens;
  }

  private record FilePart(String fieldName, Path path) {
  }

  private record ParsedCommand(String name, List<String> arguments, Map<String, String> options) {

    private static ParsedCommand from(List<String> tokens) {
      String name = tokens.getFirst().toLowerCase(Locale.ROOT);
      List<String> arguments = new ArrayList<>();
      Map<String, String> options = new LinkedHashMap<>();
      for (int i = 1; i < tokens.size(); i++) {
        String token = tokens.get(i);
        if (token.startsWith("--")) {
          String option = token.substring(2);
          int separatorIndex = option.indexOf('=');
          if (separatorIndex >= 0) {
            options.put(option.substring(0, separatorIndex), option.substring(separatorIndex + 1));
          } else if (i + 1 < tokens.size() && !tokens.get(i + 1).startsWith("--")) {
            options.put(option, tokens.get(++i));
          } else {
            options.put(option, "true");
          }
        } else {
          arguments.add(token);
        }
      }
      return new ParsedCommand(name, List.copyOf(arguments), Map.copyOf(options));
    }

    private Optional<String> option(String name) {
      return Optional.ofNullable(options.get(name));
    }

    private boolean hasFlag(String name) {
      return Boolean.parseBoolean(options.get(name));
    }
  }
}
