package dev.maddie.agents.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** Typed access to tool-call arguments. Lenient about numbers/booleans sent as strings. */
public final class Args {
    private final JsonObject o;

    public Args(JsonObject o) {
        this.o = o == null ? new JsonObject() : o;
    }

    public boolean has(String key) {
        JsonElement e = o.get(key);
        return e != null && !e.isJsonNull();
    }

    private JsonElement require(String key) throws ToolException {
        if (!has(key)) throw new ToolException("Missing required argument '" + key + "'.");
        return o.get(key);
    }

    public double num(String key) throws ToolException {
        try {
            return require(key).getAsDouble();
        } catch (RuntimeException e) {
            throw new ToolException("Argument '" + key + "' must be a number.");
        }
    }

    public double num(String key, double def) throws ToolException {
        return has(key) ? num(key) : def;
    }

    public int integer(String key) throws ToolException {
        return (int) Math.floor(num(key));
    }

    public int integer(String key, int def) throws ToolException {
        return has(key) ? integer(key) : def;
    }

    public String str(String key) throws ToolException {
        JsonElement e = require(key);
        return e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }

    public String str(String key, String def) throws ToolException {
        return has(key) ? str(key) : def;
    }

    /** A list of strings; a single string is accepted as a one-element list. */
    public List<String> strList(String key) throws ToolException {
        JsonElement e = require(key);
        List<String> out = new ArrayList<>();
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) out.add(x.isJsonPrimitive() ? x.getAsString() : x.toString());
        } else {
            out.add(e.getAsString());
        }
        return out;
    }

    public boolean bool(String key, boolean def) throws ToolException {
        if (!has(key)) return def;
        try {
            return o.get(key).getAsBoolean();
        } catch (RuntimeException e) {
            throw new ToolException("Argument '" + key + "' must be true or false.");
        }
    }
}
