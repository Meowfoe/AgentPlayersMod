package dev.maddie.agents;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * config/agentplayers.json. Agent tokens are only stored as SHA-256 hashes; the plain token is shown once,
 * to whoever ran /agent create or /agent token.
 */
public final class AgentConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static final class Entry {
        public String token_sha256 = "";
        /** Who answers for this agent (free text, usually a player name). */
        public String owner = "";
        /** Signed Mojang texture property, or empty for the default skin. */
        public String skin_value = "";
        public String skin_signature = "";
        /** Where the skin came from, for /agent list. */
        public String skin_source = "";
        /** Lets the agent run commands with send_chat "/...". Off by default; agents are never op either way. */
        public boolean allow_commands = false;
        /** Lets the agent join on its own with the join tool. When false, only /agent spawn brings it in. */
        public boolean allow_self_join = true;
    }

    /** MCP HTTP listen address. Keep it on localhost and put a reverse proxy (Caddy) in front for HTTPS. */
    public String bind = "127.0.0.1";
    public int port = 25591;
    /** Chunk radius loaded around each agent (the server's own view-distance still caps it). */
    public int view_distance = 6;
    /** Tool calls allowed per agent per minute. */
    public int calls_per_minute = 240;
    /** Despawn an agent after this many minutes without tool calls; 0 = never. */
    public int idle_minutes = 30;
    public Map<String, Entry> agents = new LinkedHashMap<>();

    private static Path path() {
        return FMLPaths.CONFIGDIR.get().resolve("agentplayers.json");
    }

    public static AgentConfig load() {
        Path p = path();
        if (Files.exists(p)) {
            try {
                AgentConfig c = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), AgentConfig.class);
                if (c != null) {
                    if (c.agents == null) c.agents = new LinkedHashMap<>();
                    return c;
                }
            } catch (Exception e) {
                AgentPlayersMod.LOG.error("Could not read {}; using defaults (the file is left untouched)", p, e);
                return new AgentConfig();
            }
        }
        AgentConfig c = new AgentConfig();
        c.save();
        return c;
    }

    public synchronized void save() {
        try {
            Path p = path();
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(this), StandardCharsets.UTF_8);
            Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            AgentPlayersMod.LOG.error("Could not save agentplayers.json", e);
        }
    }

    public static String newToken() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        return "ag_" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
