package dev.maddie.agents.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** The content returned from a tool call. */
public final class ToolResult {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private final JsonArray content = new JsonArray();

    public static ToolResult text(String text) {
        return new ToolResult().addText(text);
    }

    public static ToolResult json(JsonElement json) {
        return text(GSON.toJson(json));
    }

    public ToolResult addText(String text) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "text");
        o.addProperty("text", text);
        content.add(o);
        return this;
    }

    /** All text parts joined, for logs and tests. */
    public String plainText() {
        StringBuilder sb = new StringBuilder();
        content.forEach(e -> {
            JsonObject o = e.getAsJsonObject();
            if (o.has("text")) sb.append(sb.isEmpty() ? "" : "\n").append(o.get("text").getAsString());
        });
        return sb.toString();
    }

    JsonObject toJson(boolean isError) {
        JsonObject o = new JsonObject();
        o.add("content", content);
        o.addProperty("isError", isError);
        return o;
    }
}
