package dev.maddie.agents.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.maddie.agents.agent.Agent;

/**
 * An MCP tool. {@code readOnly} tools only observe the game. {@code needsBody} tools need the agent to be
 * in the game (everything except join/leave/status-like tools).
 */
public record Tool(String name, String description, JsonObject inputSchema, boolean readOnly, boolean needsBody,
                   Handler handler) {

    public Tool(String name, String description, JsonObject inputSchema, boolean readOnly, Handler handler) {
        this(name, description, inputSchema, readOnly, true, handler);
    }

    @FunctionalInterface
    public interface Handler {
        ToolResult call(Agent agent, Args args) throws Exception;
    }

    JsonObject describe() {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("description", description);
        o.add("inputSchema", inputSchema);
        JsonObject annotations = new JsonObject();
        annotations.addProperty("readOnlyHint", readOnly);
        annotations.addProperty("openWorldHint", false);
        o.add("annotations", annotations);
        return o;
    }

    /** Small builder for JSON-Schema object definitions. */
    public static final class Schema {
        private final JsonObject props = new JsonObject();
        private final JsonArray required = new JsonArray();

        public static Schema of() {
            return new Schema();
        }

        private Schema prop(String name, String type, String description, boolean req) {
            JsonObject p = new JsonObject();
            p.addProperty("type", type);
            p.addProperty("description", description);
            props.add(name, p);
            if (req) required.add(name);
            return this;
        }

        public Schema integer(String name, String description, boolean req) {
            return prop(name, "integer", description, req);
        }

        public Schema number(String name, String description, boolean req) {
            return prop(name, "number", description, req);
        }

        public Schema string(String name, String description, boolean req) {
            return prop(name, "string", description, req);
        }

        public Schema bool(String name, String description, boolean req) {
            return prop(name, "boolean", description, req);
        }

        public Schema stringArray(String name, String description, boolean req) {
            prop(name, "array", description, req);
            JsonObject items = new JsonObject();
            items.addProperty("type", "string");
            props.getAsJsonObject(name).add("items", items);
            return this;
        }

        public Schema xyz(String what) {
            return integer("x", "X block coordinate of " + what, true)
                    .integer("y", "Y block coordinate of " + what, true)
                    .integer("z", "Z block coordinate of " + what, true);
        }

        public JsonObject build() {
            JsonObject o = new JsonObject();
            o.addProperty("type", "object");
            o.add("properties", props);
            if (!required.isEmpty()) o.add("required", required);
            return o;
        }
    }
}
