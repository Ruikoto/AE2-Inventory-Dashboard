package local.ae2dashboard.http;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import local.ae2dashboard.*;
import local.ae2dashboard.model.*;

public final class DashboardHttp implements AutoCloseable {
  private final DashboardService service;
  private final Config config;
  private final Sessions sessions;
  private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
  private final HttpServer server;

  private record ViewKey(
      String network, long version, boolean merged, boolean zeros, String group) {}

  private record InventoryView(
      List<Resource.Stored> rows, Map<String, Long> variants, long bytes) {}

  private final Map<ViewKey, InventoryView> inventoryCache = new LinkedHashMap<>(16, 0.75f, true);
  private int inventoryCacheEntries;
  private long inventoryCacheBytes;
  private final ThreadPoolExecutor executor =
      new ThreadPoolExecutor(
          4,
          4,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(32),
          r -> {
            var t = new Thread(r, "ae2-dashboard-http");
            t.setDaemon(true);
            return t;
          });

  public DashboardHttp(DashboardService service, Config config) throws IOException {
    this.service = service;
    this.config = config;
    this.sessions = new Sessions(config.password(), config.authMode().equals("public"));
    server = HttpServer.create(new InetSocketAddress(config.bindAddress(), config.port()), 32);
    server.setExecutor(executor);
    server.createContext("/", this::handle);
  }

  public void start() {
    server.start();
  }

  public int port() {
    return server.getAddress().getPort();
  }

  private void handle(HttpExchange exchange) {
    try {
      exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
      exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
      exchange
          .getResponseHeaders()
          .set(
              "Content-Security-Policy",
              "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src"
                  + " 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self';"
                  + " frame-ancestors 'none'");
      exchange.getResponseHeaders().set("Cache-Control", "no-store");
      String path = exchange.getRequestURI().getPath();
      if (path.contains("..") || path.contains("\\") || path.length() > 2048) {
        error(exchange, 400, "INVALID_PATH");
        return;
      }
      if (!path.startsWith("/api/")) {
        staticFile(exchange, path);
        return;
      }
      String method = exchange.getRequestMethod();
      if (!path.equals("/api/v1/session") && !Set.of("GET", "HEAD").contains(method)) {
        exchange.getResponseHeaders().set("Allow", "GET, HEAD");
        error(exchange, 405, "READ_ONLY");
        return;
      }
      String token = cookie(exchange);
      long now = System.currentTimeMillis();
      boolean authorized = sessions.authorized(token, now);
      if (path.equals("/api/v1/status")) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("authMode", config.authMode());
        response.put("authenticated", authorized);
        if (authorized) {
          response.putAll(service.status());
          response.put("sampleIntervalSeconds", config.sampleIntervalSeconds());
        }
        json(exchange, 200, response);
        return;
      }
      if (path.equals("/api/v1/session")) {
        session(exchange, token, now);
        return;
      }
      if (!authorized) {
        error(exchange, 401, "UNAUTHORIZED");
        return;
      }
      Map<String, String> query = query(exchange);
      if (path.equals("/api/v1/networks")) {
        var list =
            service.networks().stream()
                .sorted(Comparator.comparingLong(Network::createdAt).thenComparing(Network::id))
                .map(
                    n -> {
                      Map<String, Object> row = new LinkedHashMap<>();
                      row.put("id", n.id());
                      row.put("label", n.label());
                      row.put("status", n.status());
                      row.put("lastSeen", n.lastSeen());
                      row.put("createdAt", n.createdAt());
                      row.put("anchors", n.anchors());
                      return row;
                    })
                .toList();
        json(exchange, 200, Map.of("worldId", service.worldId(), "networks", list));
        return;
      }
      if (path.equals("/api/v1/assets/manifest")) {
        json(exchange, 200, service.assets().summary());
        return;
      }
      if (path.startsWith("/api/v1/assets/") && path.endsWith(".png")) {
        String hash = path.substring("/api/v1/assets/".length(), path.length() - 4);
        byte[] bytes = service.assets().bytes(hash);
        if (bytes == null) {
          error(exchange, 404, "ASSET_NOT_FOUND");
          return;
        }
        exchange.getResponseHeaders().set("Cache-Control", "private, max-age=86400, immutable");
        send(exchange, 200, "image/png", bytes);
        return;
      }
      if (path.equals("/api/v1/resources")) {
        int offset = integer(query, "offset", 0, 0, 1_000_000),
            limit = integer(query, "limit", 500, 1, 2000);
        json(
            exchange,
            200,
            Map.of(
                "worldId",
                service.worldId(),
                "offset",
                offset,
                "resources",
                service.store().resources(offset, limit).stream().map(this::resource).toList()));
        return;
      }
      String[] parts = path.split("/");
      if (parts.length == 6 && parts[3].equals("networks")) {
        String id = UUID.fromString(parts[4]).toString();
        Network network =
            service.networks().stream().filter(n -> n.id().equals(id)).findFirst().orElse(null);
        if (network == null) {
          error(exchange, 404, "NETWORK_NOT_FOUND");
          return;
        }
        switch (parts[5]) {
          case "inventory" -> inventory(exchange, network, query, now);
          case "events" ->
              json(
                  exchange,
                  200,
                  Map.of(
                      "worldId",
                      service.worldId(),
                      "networkId",
                      id,
                      "events",
                      service.store().events(id)));
          case "history" -> {
            long from = number(query, "from", now - 3_600_000),
                to = number(query, "to", now),
                step = number(query, "step", 0);
            List<String> ids =
                Arrays.stream(query.getOrDefault("ids", "").split(","))
                    .filter(s -> !s.isBlank())
                    .distinct()
                    .toList();
            json(
                exchange,
                200,
                Map.of(
                    "worldId",
                    service.worldId(),
                    "networkId",
                    id,
                    "history",
                    service.store().history(id, ids, from, to, step, now)));
          }
          default -> error(exchange, 404, "NOT_FOUND");
        }
        return;
      }
      error(exchange, 404, "NOT_FOUND");
    } catch (History.InvalidGranularity e) {
      try {
        json(exchange, 400, Map.of("error", "INVALID_PARAMETER", "suggestedStep", e.suggestedStep));
      } catch (IOException ignored) {
        exchange.close();
      }
    } catch (IllegalArgumentException e) {
      safeError(exchange, 400, "INVALID_PARAMETER");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      safeError(exchange, 503, "QUERY_CANCELLED");
    } catch (Exception e) {
      safeError(exchange, 503, "TEMPORARILY_UNAVAILABLE");
    } finally {
      exchange.close();
    }
  }

  private void inventory(
      HttpExchange exchange, Network network, Map<String, String> query, long now)
      throws Exception {
    var current = service.current(network.id());
    Snapshot snapshot = current == null ? null : current.snapshot(),
        old = current == null ? null : current.before();
    if (snapshot == null) {
      boolean grouped = Boolean.parseBoolean(query.getOrDefault("merged", "false"));
      var stored =
          service
              .store()
              .lastInventory(
                  network.id(),
                  grouped,
                  query.getOrDefault("group", ""),
                  integer(query, "offset", 0, 0, 1_000_000),
                  integer(query, "limit", 1000, 1, 2000));
      List<Map<String, Object>> rows = new ArrayList<>();
      for (var value : stored.rows()) {
        var row = resource(value.resource());
        row.put("quantity", value.quantity().toString());
        row.put("delta", null);
        row.put("rate", null);
        row.put("variantCount", grouped ? -1 : 1);
        rows.add(row);
      }
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("worldId", service.worldId());
      result.put("networkId", network.id());
      result.put("status", network.status());
      result.put("available", stored.timestamp() > 0);
      result.put("stale", true);
      result.put("timestamp", stored.timestamp());
      result.put("version", stored.timestamp());
      result.put("rows", rows);
      result.put("total", stored.total());
      json(exchange, 200, result);
      return;
    }
    boolean merge = Boolean.parseBoolean(query.getOrDefault("merged", "false"));
    boolean zeros = Boolean.parseBoolean(query.getOrDefault("zeros", "false"));
    String group = query.getOrDefault("group", "");
    var viewKey = new ViewKey(network.id(), snapshot.version(), merge, zeros, group);
    InventoryView view;
    synchronized (inventoryCache) {
      view = inventoryCache.get(viewKey);
    }
    if (view == null) {
      Map<String, Resource.Stored> values = new HashMap<>(snapshot.values());
      if (zeros && old != null)
        old.values()
            .forEach(
                (id, value) ->
                    values.putIfAbsent(id, new Resource.Stored(value.resource(), BigInteger.ZERO)));
      if (zeros)
        for (int page = 0; page <= 200000; page += 2000) {
          var known = service.store().networkResources(network.id(), merge, page, 2000);
          if (page == 200000 && !known.isEmpty())
            throw new IllegalArgumentException(
                "Historical catalog exceeds view limit; disable zero stock");
          for (Resource resource : known)
            values.putIfAbsent(resource.id(), new Resource.Stored(resource, BigInteger.ZERO));
          if (known.size() < 2000) break;
        }
      var sorted =
          values.values().stream()
              .filter(v -> v.resource().id().startsWith("group:") == merge)
              .filter(v -> group.isEmpty() || v.resource().groupId().equals(group))
              .sorted(Comparator.comparing(v -> v.resource().id()))
              .toList();
      if (sorted.size() > 200000)
        throw new IllegalArgumentException("Inventory view limit exceeded");
      Map<String, Long> counts =
          merge
              ? snapshot.values().values().stream()
                  .filter(v -> !v.resource().id().startsWith("group:"))
                  .collect(
                      Collectors.groupingBy(v -> v.resource().groupId(), Collectors.counting()))
              : Map.of();
      long viewBytes =
          sorted.stream().mapToLong(v -> v.resource().estimatedBytes()).sum() + counts.size() * 96L;
      if (viewBytes > 32L * 1024 * 1024)
        throw new IllegalArgumentException("Inventory display metadata exceeds 32 MiB");
      view = new InventoryView(sorted, Map.copyOf(counts), viewBytes);
      int weight = view.rows().size() + view.variants().size();
      synchronized (inventoryCache) {
        if (weight <= 200000 && !inventoryCache.containsKey(viewKey)) {
          while (!inventoryCache.isEmpty()
              && (inventoryCache.size() >= 16
                  || inventoryCacheEntries + weight > 200000
                  || inventoryCacheBytes + viewBytes > 32L * 1024 * 1024)) {
            var iterator = inventoryCache.entrySet().iterator();
            var removed = iterator.next().getValue();
            inventoryCacheEntries -= removed.rows().size() + removed.variants().size();
            inventoryCacheBytes -= removed.bytes();
            iterator.remove();
          }
          inventoryCache.put(viewKey, view);
          inventoryCacheEntries += weight;
          inventoryCacheBytes += viewBytes;
        }
      }
    }
    List<Resource.Stored> rows = view.rows();
    int offset = integer(query, "offset", 0, 0, 1_000_000),
        limit = integer(query, "limit", 1000, 1, 2000);
    List<Map<String, Object>> output = new ArrayList<>();
    Map<String, Long> variantCounts = view.variants();
    for (var value :
        rows.subList(Math.min(offset, rows.size()), Math.min(offset + limit, rows.size()))) {
      Map<String, Object> row = resource(value.resource());
      row.put("quantity", value.quantity().toString());
      Resource.Stored prior = old == null ? null : old.values().get(value.resource().id());
      boolean continuous =
          old != null
              && old.segment().equals(snapshot.segment())
              && snapshot.timestamp() - old.timestamp() <= config.sampleIntervalSeconds() * 3000L;
      BigInteger delta =
          continuous
              ? value.quantity().subtract(prior == null ? BigInteger.ZERO : prior.quantity())
              : null;
      row.put("delta", delta == null ? null : delta.toString());
      row.put(
          "rate",
          delta == null ? null : History.rate(delta, snapshot.timestamp() - old.timestamp()));
      row.put(
          "variantCount", merge ? variantCounts.getOrDefault(value.resource().groupId(), 0L) : 1);
      output.add(row);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("worldId", service.worldId());
    result.put("networkId", network.id());
    result.put("available", true);
    result.put("timestamp", snapshot.timestamp());
    result.put("version", snapshot.version());
    result.put("status", network.status());
    result.put(
        "stale",
        !network.status().equals("online")
            || now - snapshot.timestamp() > config.sampleIntervalSeconds() * 3000L);
    result.put("windowMs", old == null ? 0 : snapshot.timestamp() - old.timestamp());
    result.put("rows", output);
    result.put("offset", offset);
    result.put("total", rows.size());
    json(exchange, 200, result);
  }

  private Map<String, Object> resource(Resource resource) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", resource.id());
    row.put("groupId", resource.groupId());
    row.put("type", resource.type());
    row.put("registryId", resource.registryId());
    row.put("unit", resource.unit());
    row.put("amountPerUnit", resource.amountPerUnit());
    var mapping = service.assets().entry(resource.id());
    if (mapping != null
        && (!mapping.registryId().equals(resource.registryId())
            || !mapping.type().equals(resource.type()))) mapping = null;
    row.put("name", mapping == null ? resource.name() : mapping.name());
    if (mapping != null
        && service.assets().hasIcon(resource.id())
        && mapping.registryId().equals(resource.registryId())
        && mapping.type().equals(resource.type())) {
      var page = service.assets().page(mapping.page());
      row.put(
          "icon",
          Map.of(
              "url",
              "api/v1/assets/" + mapping.page() + ".png",
              "x",
              mapping.x(),
              "y",
              mapping.y(),
              "width",
              page.width(),
              "height",
              page.height()));
    }
    return row;
  }

  private void session(HttpExchange exchange, String token, long now) throws IOException {
    if (!sameOrigin(exchange)) {
      error(exchange, 403, "ORIGIN_REJECTED");
      return;
    }
    if (exchange.getRequestMethod().equals("POST")) {
      byte[] body = exchange.getRequestBody().readNBytes(4097);
      if (body.length > 4096) {
        error(exchange, 413, "REQUEST_TOO_LARGE");
        return;
      }
      try {
        var input =
            JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
        String password = input.get("password").getAsString();
        if (password.length() > 1024) throw new IllegalArgumentException();
        String result =
            sessions.login(
                exchange.getRemoteAddress().getAddress().getHostAddress(), password, now);
        if (result == null) {
          error(exchange, 401, "UNAUTHORIZED");
          return;
        }
        exchange.getResponseHeaders().add("Set-Cookie", cookieValue(result, false));
        json(exchange, 200, Map.of("authenticated", true));
      } catch (Sessions.RateLimited e) {
        error(exchange, 429, "TOO_MANY_ATTEMPTS");
      } catch (RuntimeException e) {
        error(exchange, 400, "INVALID_REQUEST");
      }
    } else if (exchange.getRequestMethod().equals("DELETE")) {
      sessions.logout(token);
      exchange.getResponseHeaders().add("Set-Cookie", cookieValue("", true));
      json(exchange, 200, Map.of("authenticated", false));
    } else {
      exchange.getResponseHeaders().set("Allow", "POST, DELETE");
      error(exchange, 405, "METHOD_NOT_ALLOWED");
    }
  }

  private String cookieValue(String token, boolean clear) {
    return "ae2dash="
        + token
        + "; Path=/; HttpOnly; SameSite=Strict"
        + (config.secureCookie() ? "; Secure" : "")
        + (clear ? "; Max-Age=0" : "");
  }

  private boolean sameOrigin(HttpExchange exchange) {
    String origin = exchange.getRequestHeaders().getFirst("Origin");
    if (origin == null) return false;
    String expected =
        config.publicOrigin().isBlank()
            ? (config.secureCookie() ? "https://" : "http://")
                + exchange.getRequestHeaders().getFirst("Host")
            : config.publicOrigin();
    return origin.equals(expected)
        && !"cross-site".equals(exchange.getRequestHeaders().getFirst("Sec-Fetch-Site"));
  }

  private static String cookie(HttpExchange exchange) {
    String cookies = exchange.getRequestHeaders().getFirst("Cookie");
    if (cookies != null)
      for (String part : cookies.split(";"))
        if (part.trim().startsWith("ae2dash=")) return part.trim().substring(8);
    return "";
  }

  private void staticFile(HttpExchange exchange, String path) throws IOException {
    if (!Set.of("GET", "HEAD").contains(exchange.getRequestMethod())) {
      error(exchange, 405, "READ_ONLY");
      return;
    }
    if (path.equals("/")) path = "/index.html";
    if (!path.matches("/[a-zA-Z0-9_./-]+")) {
      error(exchange, 404, "NOT_FOUND");
      return;
    }
    try (var in = DashboardHttp.class.getResourceAsStream("/web" + path)) {
      if (in == null) {
        error(exchange, 404, "NOT_FOUND");
        return;
      }
      String type =
          path.endsWith(".js")
              ? "text/javascript; charset=utf-8"
              : path.endsWith(".css")
                  ? "text/css; charset=utf-8"
                  : path.endsWith(".svg")
                      ? "image/svg+xml"
                      : path.endsWith(".png") ? "image/png" : "text/html; charset=utf-8";
      send(exchange, 200, type, in.readAllBytes());
    }
  }

  private void json(HttpExchange exchange, int status, Object body) throws IOException {
    send(
        exchange,
        status,
        "application/json; charset=utf-8",
        gson.toJson(body).getBytes(StandardCharsets.UTF_8));
  }

  private void error(HttpExchange exchange, int status, String code) throws IOException {
    json(exchange, status, Map.of("error", code));
  }

  private void safeError(HttpExchange exchange, int status, String code) {
    try {
      error(exchange, status, code);
    } catch (IOException ignored) {
      exchange.close();
    }
  }

  private void send(HttpExchange exchange, int status, String type, byte[] bytes)
      throws IOException {
    if (bytes.length > 16 * 1024 * 1024) throw new IOException("Response exceeds budget");
    exchange.getResponseHeaders().set("Content-Type", type);
    exchange.sendResponseHeaders(
        status, exchange.getRequestMethod().equals("HEAD") ? -1 : bytes.length);
    if (!exchange.getRequestMethod().equals("HEAD")) exchange.getResponseBody().write(bytes);
  }

  private static Map<String, String> query(HttpExchange exchange) {
    String raw = exchange.getRequestURI().getRawQuery();
    if (raw == null) return Map.of();
    if (raw.length() > 4096) throw new IllegalArgumentException("Query too large");
    return Arrays.stream(raw.split("&"))
        .map(s -> s.split("=", 2))
        .collect(
            Collectors.toMap(
                p -> URLDecoder.decode(p[0], StandardCharsets.UTF_8),
                p -> p.length > 1 ? URLDecoder.decode(p[1], StandardCharsets.UTF_8) : "",
                (a, b) -> {
                  throw new IllegalArgumentException("Duplicate parameter");
                }));
  }

  private static long number(Map<String, String> query, String key, long fallback) {
    return query.containsKey(key) ? Long.parseLong(query.get(key)) : fallback;
  }

  private static int integer(
      Map<String, String> query, String key, int fallback, int min, int max) {
    long n = number(query, key, fallback);
    if (n < min || n > max) throw new IllegalArgumentException("Out of bounds");
    return (int) n;
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }
}
