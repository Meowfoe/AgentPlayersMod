package dev.maddie.agents.agent;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import dev.maddie.agents.AgentConfig;
import dev.maddie.agents.AgentPlayersMod;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** All agents known to the server (from config/agentplayers.json), and bringing their bodies in and out. */
public final class Agents {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Map<String, Agent> BY_NAME = new ConcurrentHashMap<>();
    private static volatile AgentConfig config = new AgentConfig();

    private Agents() {
    }

    public static AgentConfig config() {
        return config;
    }

    /** (Re)loads the config. Agents that still exist keep their state. */
    public static synchronized void load() {
        config = AgentConfig.load();
        for (var e : config.agents.entrySet()) {
            Agent a = BY_NAME.get(key(e.getKey()));
            if (a != null && a.name().equals(e.getKey())) a.setEntry(e.getValue());
            else BY_NAME.put(key(e.getKey()), new Agent(e.getKey(), e.getValue()));
        }
        BY_NAME.values().removeIf(a -> !config.agents.containsKey(a.name()) && !a.online());
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public static Collection<Agent> all() {
        return BY_NAME.values();
    }

    public static @Nullable Agent get(String name) {
        return BY_NAME.get(key(name));
    }

    public static boolean isAgent(ServerPlayer p) {
        return p instanceof AgentPlayer;
    }

    /** The agent this bearer token belongs to, or null. Compares hashes in constant time. */
    public static @Nullable Agent byToken(String token) {
        if (token == null || token.isBlank()) return null;
        byte[] h = AgentConfig.hash(token.trim()).getBytes(StandardCharsets.US_ASCII);
        Agent found = null;
        for (Agent a : BY_NAME.values()) {
            byte[] mine = a.entry().token_sha256.getBytes(StandardCharsets.US_ASCII);
            if (mine.length > 0 && MessageDigest.isEqual(h, mine)) found = a;
        }
        return found;
    }

    // ---- managing agents (server thread, from /agent) ----

    /** Creates an agent and returns its token (shown once). */
    public static synchronized String create(MinecraftServer server, String name, String owner) {
        if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("Names are 3-16 letters, digits or _.");
        if (get(name) != null) throw new IllegalArgumentException("An agent named " + name + " already exists.");
        if (server.getPlayerList().getPlayerByName(name) != null) {
            throw new IllegalArgumentException(name + " is a player who is online right now.");
        }
        var known = server.getProfileCache() == null ? null : server.getProfileCache().get(name).orElse(null);
        if (known != null && !known.getId().equals(Agent.offlineUuid(name))) {
            throw new IllegalArgumentException(name + " is a real player who has played here. Pick another name.");
        }
        AgentConfig.Entry e = new AgentConfig.Entry();
        e.owner = owner;
        String token = AgentConfig.newToken();
        e.token_sha256 = AgentConfig.hash(token);
        config.agents.put(name, e);
        config.save();
        BY_NAME.put(key(name), new Agent(name, e));
        return token;
    }

    public static synchronized String newToken(Agent a) {
        String token = AgentConfig.newToken();
        a.entry().token_sha256 = AgentConfig.hash(token);
        config.save();
        return token;
    }

    public static synchronized void remove(Agent a) {
        despawn(a, "Removed.");
        config.agents.remove(a.name());
        config.save();
        BY_NAME.remove(key(a.name()));
    }

    public static synchronized void saveConfig() {
        config.save();
    }

    // ---- bodies (server thread) ----

    /** Brings the agent into the game where it last logged out (or at world spawn the first time). */
    public static AgentPlayer spawn(MinecraftServer server, Agent a) {
        if (a.online()) return a.player();
        ServerPlayer clash = server.getPlayerList().getPlayerByName(a.name());
        if (clash != null) throw new IllegalStateException("A player called " + a.name() + " is already online.");

        GameProfile profile = new GameProfile(a.uuid(), a.name());
        AgentConfig.Entry e = a.entry();
        if (!e.skin_value.isBlank()) {
            profile.getProperties().put("textures", new Property("textures", e.skin_value,
                    e.skin_signature.isBlank() ? null : e.skin_signature));
        }
        ClientInformation info = clientInfo();
        AgentPlayer p = new AgentPlayer(server, server.overworld(), profile, info, a);
        AgentConnection conn = new AgentConnection(a);
        a.attach(p);
        try {
            server.getPlayerList().placeNewPlayer(conn, p, new CommonListenerCookie(profile, 0, info, false, ConnectionType.NEOFORGE));
        } catch (RuntimeException ex) {
            a.attach(null);
            throw ex;
        }
        conn.drain();
        AgentPlayersMod.LOG.info("Agent {} joined at {} in {}", a.name(), p.blockPosition(), p.level().dimension().location());
        return p;
    }

    public static ClientInformation clientInfo() {
        return new ClientInformation("en_us", Math.max(2, config.view_distance), ChatVisiblity.FULL, true, 0x7F,
                HumanoidArm.RIGHT, false, true);
    }

    /** Takes the agent's body out of the game (saved like a normal logout). */
    public static void despawn(Agent a, String reason) {
        AgentPlayer p = a.player();
        if (p == null) return;
        if (a.online()) {
            // What the server does when a client's connection drops: "left the game", save, remove.
            p.connection.onDisconnect(new DisconnectionDetails(Component.literal(reason)));
        }
        a.attach(null);
        AgentPlayersMod.LOG.info("Agent {} left: {}", a.name(), reason);
    }

    /** Called when the server replaces the body (respawn, End exit). */
    static void replaced(Agent a, AgentPlayer fresh) {
        a.attach(fresh);
    }

    public static void despawnAll(String reason) {
        for (Agent a : new ArrayList<>(BY_NAME.values())) despawn(a, reason);
    }

    // ---- housekeeping ----

    private static int tick;

    /** Once a second: idle timeouts, and bodies the server dropped on its own (kick, ban...). */
    public static void serverTick(MinecraftServer server) {
        if (++tick % 20 != 0) return;
        long idleMs = config.idle_minutes * 60_000L;
        for (Agent a : BY_NAME.values()) {
            AgentPlayer p = a.player();
            if (p == null) continue;
            if (!a.online()) { // kicked, banned, or removed by something else
                a.attach(null);
                continue;
            }
            if (idleMs > 0 && System.currentTimeMillis() - a.lastCallMillis() > idleMs && !a.runner().busy()) {
                despawn(a, "Idle for " + config.idle_minutes + " minutes.");
            }
        }
    }

    /** A real player with an agent's name joined: the agent makes room. */
    public static void onRealPlayerJoin(ServerPlayer real) {
        Agent a = get(real.getGameProfile().getName());
        if (a != null && a.online() && a.player() != real) despawn(a, "A player with this name joined.");
    }

    public static List<String> describeAll() {
        List<String> out = new ArrayList<>();
        for (Agent a : BY_NAME.values()) {
            AgentPlayer p = a.player();
            String where = a.online() ? "online at " + p.blockPosition().toShortString() + " in " + p.level().dimension().location()
                    : "offline";
            String action = a.runner().currentName();
            out.add(a.name() + " (owner " + (a.entry().owner.isBlank() ? "?" : a.entry().owner) + "): " + where
                    + (action == null ? "" : ", doing " + action)
                    + (a.entry().skin_source.isBlank() ? "" : ", skin " + a.entry().skin_source));
        }
        return out;
    }
}
