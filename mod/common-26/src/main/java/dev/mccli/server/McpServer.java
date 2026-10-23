package dev.mccli.server;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.mccli.McCliMod;
import dev.mccli.control.ControlRuntime;
import dev.mccli.events.EventLog;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The game as an MCP server (streamable HTTP, JSON responses) on 127.0.0.1:25590/mcp, so an AI tool talks to it
 * directly. Same token and the same player permissions as the TCP socket; requests from a web page (an Origin that
 * is not localhost) are refused.
 */
public final class McpServer {
    public static final int DEFAULT_PORT = 25590;
    private static final String PROTOCOL = "2025-06-18";
    private static final long MAX_IMAGE_BYTES = 4L << 20;
    private static final Gson GSON = new Gson();

    private final CommandDispatcher dispatcher = new CommandDispatcher();
    private HttpServer http;
    private static volatile int boundPort = -1;

    /** The port the MCP server listens on, or -1. */
    public static int port() {
        return boundPort;
    }

    public void start() {
        String env = System.getenv("MCCLI_MCP_PORT");
        int port = env == null || env.isBlank() ? DEFAULT_PORT : Integer.parseInt(env.trim());
        if (port <= 0) {
            return;
        }
        try {
            http = HttpServer.create(new InetSocketAddress(SocketServer.bindAddress(), port), 16);
        } catch (IOException e) {
            McCliMod.LOGGER.warn("MC-CLI MCP server could not listen on port {}: {}", port, e.getMessage());
            return;
        }
        http.createContext("/mcp", this::handle);
        http.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "MC-CLI-MCP");
            t.setDaemon(true);
            return t;
        }));
        http.start();
        boundPort = port;
        McCliMod.LOGGER.info("MC-CLI MCP server on http://{}:{}/mcp", http.getAddress().getHostString(), port);
    }

    public void stop() {
        if (http != null) http.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String origin = ex.getRequestHeaders().getFirst("Origin");
            if (origin != null && !localOrigin(origin)) {
                send(ex, 403, error(null, -32600, "requests from web pages are refused"));
                return;
            }
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : ex.getRequestHeaders().getFirst("X-MCCLI-Token");
            if (!ControlRuntime.authorized(token)) {
                send(ex, 401, error(null, -32001, "missing or wrong token: Authorization: Bearer <config/mccli-token>"));
                return;
            }
            if (!"POST".equals(ex.getRequestMethod())) {
                ex.getResponseHeaders().add("Allow", "POST");
                ex.sendResponseHeaders(405, -1);
                return;
            }
            JsonElement body = JsonParser.parseString(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (!body.isJsonObject()) {
                send(ex, 400, error(null, -32600, "send one JSON-RPC request per POST"));
                return;
            }
            JsonObject req = body.getAsJsonObject();
            String method = req.has("method") ? req.get("method").getAsString() : "";
            if (!req.has("id")) {
                // a notification (notifications/initialized and the like): nothing to answer
                ex.sendResponseHeaders(202, -1);
                return;
            }
            JsonElement id = req.get("id");
            JsonObject params = req.has("params") && req.get("params").isJsonObject() ? req.getAsJsonObject("params") : new JsonObject();
            send(ex, 200, switch (method) {
                case "initialize" -> result(id, initialize(params));
                case "ping" -> result(id, new JsonObject());
                case "tools/list" -> {
                    JsonObject r = new JsonObject();
                    r.add("tools", McpTools.list());
                    yield result(id, r);
                }
                case "tools/call" -> result(id, call(params));
                default -> error(id, -32601, "method not found: " + method);
            });
        } catch (RuntimeException e) {
            send(ex, 400, error(null, -32700, "bad request: " + e.getMessage()));
        }
    }

    private static boolean localOrigin(String origin) {
        try {
            String host = URI.create(origin).getHost();
            return "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host) || "::1".equals(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static JsonObject initialize(JsonObject params) {
        JsonObject r = new JsonObject();
        r.addProperty("protocolVersion", params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : PROTOCOL);
        JsonObject caps = new JsonObject();
        JsonObject tools = new JsonObject();
        tools.addProperty("listChanged", false);
        caps.add("tools", tools);
        r.add("capabilities", caps);
        JsonObject info = new JsonObject();
        info.addProperty("name", "mccli");
        String version = McCliMod.class.getPackage().getImplementationVersion();
        info.addProperty("version", version != null ? version : "dev");
        r.add("serverInfo", info);
        r.addProperty("instructions", "The player's own Minecraft client. Live servers are read-only: watch and film, never change "
            + "anything. A refused call (CONTROL_*) is the player's choice: tell them, do not retry around it.");
        return r;
    }

    private JsonObject call(JsonObject params) {
        String name = params.has("name") ? params.get("name").getAsString() : "";
        JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject() ? params.getAsJsonObject("arguments") : new JsonObject();
        if (!McpTools.ALL.containsKey(name)) return toolError("unknown tool: " + name);
        String[] refused = ControlRuntime.refuse(name, args);
        if (refused != null) return toolError(refused[0] + ": " + refused[1]);
        if (!"events".equals(name) && !"control".equals(name) && !"status".equals(name)) {
            JsonObject agent = new JsonObject();
            agent.addProperty("command", name);
            agent.addProperty("via", "mcp");
            if (args.has("action")) agent.add("action", args.get("action"));
            EventLog.add("agent", agent);
        }
        JsonObject data;
        try {
            data = dispatcher.dispatch(name, args).get(11, TimeUnit.MINUTES);
        } catch (ExecutionException e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            return toolError(c.getMessage() != null ? c.getMessage() : c.toString());
        } catch (TimeoutException e) {
            return toolError("the game did not answer within 11 minutes");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return toolError("interrupted");
        }
        JsonArray content = new JsonArray();
        content.add(text(GSON.toJson(data)));
        if ("screenshot".equals(name) && (!args.has("return_image") || args.get("return_image").getAsBoolean())) {
            if (data.has("images")) {
                for (JsonElement img : data.getAsJsonArray("images")) image(content, img.getAsJsonObject().get("path").getAsString());
            } else if (data.has("path")) {
                image(content, data.get("path").getAsString());
            }
        }
        JsonObject r = new JsonObject();
        r.add("content", content);
        r.addProperty("isError", false);
        return r;
    }

    private static void image(JsonArray content, String path) {
        try {
            Path file = Path.of(path);
            if (Files.size(file) > MAX_IMAGE_BYTES) return;
            JsonObject o = new JsonObject();
            o.addProperty("type", "image");
            o.addProperty("data", Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
            o.addProperty("mimeType", "image/png");
            content.add(o);
        } catch (IOException | RuntimeException ignored) {
            // the text result still has the path
        }
    }

    private static JsonObject text(String s) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "text");
        o.addProperty("text", s);
        return o;
    }

    private static JsonObject toolError(String message) {
        JsonArray content = new JsonArray();
        content.add(text(message));
        JsonObject r = new JsonObject();
        r.add("content", content);
        r.addProperty("isError", true);
        return r;
    }

    private static JsonObject result(JsonElement id, JsonObject result) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.add("id", id);
        o.add("result", result);
        return o;
    }

    private static JsonObject error(JsonElement id, int code, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        if (id != null) o.add("id", id);
        JsonObject e = new JsonObject();
        e.addProperty("code", code);
        e.addProperty("message", message);
        o.add("error", e);
        return o;
    }

    private static void send(HttpExchange ex, int status, JsonObject body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
