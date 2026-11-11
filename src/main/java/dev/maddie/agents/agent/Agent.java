package dev.maddie.agents.agent;

import dev.maddie.agents.AgentConfig;
import dev.maddie.agents.game.ActionRunner;
import dev.maddie.agents.game.Behaviors;
import dev.maddie.agents.game.ChatLog;
import dev.maddie.agents.game.Events;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.inventory.MenuType;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * One AI agent: its config entry, its in-game body (when online) and everything it remembers between tool calls
 * (chat it saw, pending events, the open screen). Lives as long as the server; the player comes and goes.
 */
public final class Agent {
    private final String name;
    private final UUID uuid;
    private volatile AgentConfig.Entry entry;
    private volatile AgentPlayer player;

    private final Body body = new Body(this);
    private final ActionRunner runner = new ActionRunner(this);
    private final Events events = new Events();
    private final ChatLog chat = new ChatLog();
    private final Behaviors behaviors = new Behaviors();

    // What the agent's "screen" shows, from the packets a client would get.
    private volatile int screenContainerId = -1;
    private volatile String screenTitle;
    private volatile String screenType;
    private volatile BlockPos signPos;
    private volatile boolean signFront;
    private volatile String actionBar = "";
    private volatile long actionBarTick;
    private volatile String lastDeathMessage;

    /** Client replies that must wait for the next tick (answering inside a send would re-enter the server code). */
    private final List<Runnable> replies = new ArrayList<>();

    private final Deque<Long> recentCalls = new ArrayDeque<>();
    private volatile long lastCallMillis = System.currentTimeMillis();

    Agent(String name, AgentConfig.Entry entry) {
        this.name = name;
        this.uuid = offlineUuid(name);
        this.entry = entry;
    }

    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    public String name() {
        return name;
    }

    public UUID uuid() {
        return uuid;
    }

    public AgentConfig.Entry entry() {
        return entry;
    }

    void setEntry(AgentConfig.Entry entry) {
        this.entry = entry;
    }

    /** The body, or null when offline. Only touch it on the server thread. */
    public AgentPlayer player() {
        return player;
    }

    void attach(@Nullable AgentPlayer p) {
        this.player = p;
        if (p == null) {
            runner.cancel("You left the game.");
            body.releaseKeys();
            clearScreen();
            signPos = null;
        }
    }

    public boolean online() {
        AgentPlayer p = player;
        return p != null && !p.isRemoved() && p.server.getPlayerList().getPlayer(uuid) == p;
    }

    public Body body() {
        return body;
    }

    public ActionRunner runner() {
        return runner;
    }

    public Events events() {
        return events;
    }

    public ChatLog chat() {
        return chat;
    }

    public Behaviors behaviors() {
        return behaviors;
    }

    // ---- rate limit and idle tracking (HTTP threads) ----

    public synchronized boolean allowCall() {
        long now = System.currentTimeMillis();
        lastCallMillis = now;
        while (!recentCalls.isEmpty() && now - recentCalls.peekFirst() > 60_000) recentCalls.removeFirst();
        if (recentCalls.size() >= Math.max(1, Agents.config().calls_per_minute)) return false;
        recentCalls.addLast(now);
        return true;
    }

    public long lastCallMillis() {
        return lastCallMillis;
    }

    // ---- screen state ----

    /** Title of the open container screen, or null when only the inventory is open. */
    public @Nullable String screenTitle() {
        AgentPlayer p = player;
        if (p == null || p.containerMenu == p.inventoryMenu || p.containerMenu.containerId != screenContainerId) return null;
        return screenTitle;
    }

    public @Nullable String screenType() {
        return screenTitle() == null ? null : screenType;
    }

    public @Nullable BlockPos signPos() {
        return signPos;
    }

    public boolean signFront() {
        return signFront;
    }

    public void clearSign() {
        signPos = null;
    }

    private void clearScreen() {
        screenContainerId = -1;
        screenTitle = null;
        screenType = null;
    }

    /** The last action-bar message, if it is recent (within ~5 s). */
    public String actionBar() {
        AgentPlayer p = player;
        if (p == null || actionBar.isEmpty() || p.server.getTickCount() - actionBarTick > 100) return "";
        return actionBar;
    }

    public @Nullable String takeDeathMessage() {
        String m = lastDeathMessage;
        lastDeathMessage = null;
        return m;
    }

    // ---- what the server sends us ----

    /** Every packet the server sends this agent ends up here. Usually the server thread. */
    void onPacket(Packet<?> packet) {
        if (packet instanceof ClientboundSystemChatPacket sys) {
            String text = sys.content().getString();
            if (sys.overlay()) {
                actionBar = text;
                AgentPlayer p = player;
                actionBarTick = p == null ? 0 : p.server.getTickCount();
            } else if (!text.isBlank()) {
                chat.add(text, false);
            }
        } else if (packet instanceof ClientboundPlayerChatPacket pc) {
            Component content = pc.unsignedContent() != null ? pc.unsignedContent() : Component.literal(pc.body().content());
            chat.add(pc.chatType().decorate(content).getString(), uuid.equals(pc.sender()));
        } else if (packet instanceof ClientboundDisguisedChatPacket dc) {
            boolean own = dc.chatType().name().getString().equals(name);
            chat.add(dc.chatType().decorate(dc.message()).getString(), own);
        } else if (packet instanceof ClientboundOpenScreenPacket open) {
            screenContainerId = open.getContainerId();
            screenTitle = open.getTitle().getString();
            MenuType<?> type = open.getType();
            var key = type == null ? null : BuiltInRegistries.MENU.getKey(type);
            screenType = key == null ? "unknown" : key.toString();
        } else if (packet instanceof ClientboundHorseScreenOpenPacket horse) {
            screenContainerId = horse.getContainerId();
            screenTitle = "Mount inventory";
            screenType = "horse";
        } else if (packet instanceof ClientboundContainerClosePacket close) {
            if (close.getContainerId() == screenContainerId) clearScreen();
        } else if (packet instanceof ClientboundOpenSignEditorPacket sign) {
            signPos = sign.getPos();
            signFront = sign.isFrontText();
        } else if (packet instanceof ClientboundPlayerCombatKillPacket kill) {
            AgentPlayer p = player;
            if (p != null && kill.playerId() == p.getId()) lastDeathMessage = kill.message().getString();
        } else if (packet instanceof ClientboundPlayerPositionPacket pos) {
            // A real client confirms every teleport; the server finishes dimension changes on that answer.
            int id = pos.getId();
            synchronized (replies) {
                replies.add(() -> {
                    AgentPlayer p = player;
                    if (p != null) p.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(id));
                });
            }
        }
    }

    // ---- per tick (server thread, from AgentPlayer.tick) ----

    void beforeTick(AgentPlayer p) {
        if (p != player) return; // an old body that is being removed
        List<Runnable> todo;
        synchronized (replies) {
            todo = replies.isEmpty() ? List.of() : new ArrayList<>(replies);
            replies.clear();
        }
        for (Runnable r : todo) r.run();
        if (p.containerMenu == p.inventoryMenu && screenTitle != null) clearScreen();
        events.tick(this, p);
        runner.tick(p);
        body.apply(p);
    }
}
