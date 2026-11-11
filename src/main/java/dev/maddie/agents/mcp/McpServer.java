package dev.maddie.agents.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.maddie.agents.AgentPlayersMod;
import dev.maddie.agents.agent.Agent;
import dev.maddie.agents.agent.Agents;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MCP server using the "Streamable HTTP" transport (plain JSON responses, no SSE stream), adapted from
 * mongoose's MCP Bridge. One endpoint for every agent: the bearer token decides which agent a request drives.
 * Endpoint: POST /mcp with {@code Authorization: Bearer <token>}, or POST /mcp/&lt;token&gt;.
 */
public final class McpServer {
    private static final List<String> PROTOCOL_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final HttpServer http;
    private final ExecutorService executor;
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final String instructions;
    private final String version;

    public McpServer(String host, int port, List<Tool> toolList, String instructions, String version) throws IOException {
        this.instructions = instructions;
        this.version = version;
        for (Tool t : toolList) tools.put(t.name(), t);
        http = HttpServer.create(new InetSocketAddress(host, port), 0);
        executor = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "AgentPlayers-MCP");
            t.setDaemon(true);
            return t;
        });
        http.setExecutor(executor);
        http.createContext("/mcp", this::handle);
    }

    public void start() {
        http.start();
    }

    public void stop() {
        http.stop(0);
        executor.shutdownNow();
    }

    public int port() {
        return http.getAddress().getPort();
    }

    private void handle(HttpExchange ex) {
        try {
            Agent agent = authorize(ex);
            if (agent == null) {
                send(ex, 401, errorBody("Unauthorized: missing or unknown agent token."), null);
                return;
            }
            switch (ex.getRequestMethod()) {
                case "POST" -> handlePost(ex, agent);
                case "DELETE" -> send(ex, 200, null, null); // session end; we keep no per-session state
                default -> {
                    // We do not offer a server-initiated SSE stream; 405 tells clients to skip it.
                    ex.getResponseHeaders().set("Allow", "POST, DELETE");
                    send(ex, 405, null, null);
                }
            }
        } catch (Exception e) {
            AgentPlayersMod.LOG.warn("MCP request failed", e);
            try {
                send(ex, 500, errorBody("Internal error: " + e), null);
            } catch (IOException ignored) {
            }
        } finally {
            ex.close();
        }
    }

    private static Agent authorize(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Agents.byToken(header.substring(7).trim());
        }
        String path = ex.getRequestURI().getPath();
        if (path.startsWith("/mcp/") && path.length() > 5) return Agents.byToken(path.substring(5));
        return null;
    }

    private void handlePost(HttpExchange ex, Agent agent) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(body);
        } catch (RuntimeException e) {
            send(ex, 400, rpcError(null, -32700, "Parse error"), null);
            return;
        }

        if (parsed.isJsonArray()) {
            JsonArray responses = new JsonArray();
            for (JsonElement el : parsed.getAsJsonArray()) {
                JsonObject r = el.isJsonObject() ? handleMessage(agent, el.getAsJsonObject()) : rpcError(null, -32600, "Invalid request");
                if (r != null) responses.add(r);
            }
            send(ex, responses.isEmpty() ? 202 : 200, responses.isEmpty() ? null : responses, null);
            return;
        }
        if (!parsed.isJsonObject()) {
            send(ex, 400, rpcError(null, -32600, "Invalid request"), null);
            return;
        }
        JsonObject msg = parsed.getAsJsonObject();
        String sessionId = "initialize".equals(stringOrNull(msg, "method")) ? UUID.randomUUID().toString() : null;
        JsonObject response = handleMessage(agent, msg);
        send(ex, response == null ? 202 : 200, response, sessionId);
    }

    /** Returns the JSON-RPC response, or null for notifications and client responses. */
    private JsonObject handleMessage(Agent agent, JsonObject msg) {
        String method = stringOrNull(msg, "method");
        if (method == null) return null; // a response to something we never send; ignore
        JsonElement id = msg.get("id");
        boolean notification = id == null || id.isJsonNull();
        JsonObject params = msg.has("params") && msg.get("params").isJsonObject() ? msg.getAsJsonObject("params") : new JsonObject();

        JsonObject result;
        switch (method) {
            case "initialize" -> result = initialize(agent, params);
            case "ping" -> result = new JsonObject();
            case "tools/list" -> {
                JsonArray list = new JsonArray();
                tools.values().forEach(t -> list.add(t.describe()));
                result = new JsonObject();
                result.add("tools", list);
            }
            case "tools/call" -> {
                String name = stringOrNull(params, "name");
                Tool tool = name == null ? null : tools.get(name);
                if (tool == null) return notification ? null : rpcError(id, -32602, "Unknown tool: " + name);
                result = callTool(agent, tool, params.has("arguments") && params.get("arguments").isJsonObject()
                        ? params.getAsJsonObject("arguments") : new JsonObject());
            }
            default -> {
                return notification ? null : rpcError(id, -32601, "Method not found: " + method);
            }
        }
        if (notification) return null;
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);
        response.add("result", result);
        return response;
    }

    private JsonObject initialize(Agent agent, JsonObject params) {
        String requested = stringOrNull(params, "protocolVersion");
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", PROTOCOL_VERSIONS.contains(requested) ? requested : PROTOCOL_VERSIONS.get(0));
        JsonObject capabilities = new JsonObject();
        JsonObject toolsCap = new JsonObject();
        toolsCap.addProperty("listChanged", false);
        capabilities.add("tools", toolsCap);
        result.add("capabilities", capabilities);
        JsonObject info = new JsonObject();
        info.addProperty("name", "agentplayers");
        info.addProperty("version", version);
        result.add("serverInfo", info);
        result.addProperty("instructions", instructions.replace("{name}", agent.name()));
        return result;
    }

    /** Runs one tool call for an agent; also used by the devtest. */
    public ToolResult call(Agent agent, String toolName, JsonObject arguments, boolean[] isError) {
        Tool tool = tools.get(toolName);
        if (tool == null) {
            isError[0] = true;
            return ToolResult.text("Unknown tool: " + toolName);
        }
        JsonObject r = callTool(agent, tool, arguments);
        isError[0] = r.get("isError").getAsBoolean();
        ToolResult out = new ToolResult();
        r.getAsJsonArray("content").forEach(c -> out.addText(c.getAsJsonObject().get("text").getAsString()));
        return out;
    }

    private JsonObject callTool(Agent agent, Tool tool, JsonObject arguments) {
        ToolResult result;
        boolean error = false;
        try {
            if (!agent.allowCall()) throw new ToolException("Too many calls; slow down a little.");
            if (tool.needsBody() && !agent.online()) {
                throw new ToolException("You are not in the game right now. Call join first.");
            }
            result = tool.handler().call(agent, new Args(arguments));
        } catch (ToolException e) {
            result = ToolResult.text(e.getMessage());
            error = true;
        } catch (Exception e) {
            AgentPlayersMod.LOG.warn("Tool {} failed for agent {}", tool.name(), agent.name(), e);
            result = ToolResult.text("Internal error in " + tool.name() + ": " + e);
            error = true;
        }
        String extra = agent.events().drain();
        if (extra != null) result.addText(extra);
        return result.toJson(error);
    }

    private static String stringOrNull(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static JsonObject rpcError(JsonElement id, int code, String message) {
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.add("id", id);
        o.add("error", err);
        return o;
    }

    private static JsonObject errorBody(String message) {
        return rpcError(null, -32000, message);
    }

    private static void send(HttpExchange ex, int code, JsonElement body, String sessionId) throws IOException {
        if (sessionId != null) ex.getResponseHeaders().set("Mcp-Session-Id", sessionId);
        if (body == null) {
            ex.sendResponseHeaders(code, -1);
            return;
        }
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
