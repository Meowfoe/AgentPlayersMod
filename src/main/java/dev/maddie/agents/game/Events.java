package dev.maddie.agents.game;

import dev.maddie.agents.agent.Agent;
import dev.maddie.agents.agent.AgentPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Notable things that happened to one agent (damage, death, mobs approaching, chat, nightfall...). They are
 * attached to the next tool result, so the AI learns about them even while it was idle.
 */
public final class Events {
    private static final int MAX = 30;
    private final List<String> pending = new ArrayList<>();

    // Watcher state (server thread only).
    private float lastHealth = -1;
    private int lastFood = -1;
    private long lastDayTime = -1;
    private boolean wasDead, toolWarned;
    private long lastChatId = -1;
    private int tick;
    private final Map<Integer, Integer> reported = new HashMap<>(); // entity id -> tick reported

    public synchronized void add(String event) {
        pending.add(event);
        while (pending.size() > MAX) pending.remove(0);
    }

    /** Returns and clears pending events, or null if there are none. */
    public synchronized String drain() {
        if (pending.isEmpty()) return null;
        String s = "Events since your last call:\n- " + String.join("\n- ", pending);
        pending.clear();
        return s;
    }

    public void tick(Agent agent, AgentPlayer p) {
        tick++;
        if (lastChatId < 0) lastChatId = agent.chat().lastId(); // the join flood is not news

        float health = p.getHealth();
        if (lastHealth >= 0 && health < lastHealth - 0.01f && !p.isDeadOrDying()) {
            add("Took " + Game.round(lastHealth - health) + " damage, health " + Game.round(health) + "/"
                    + Game.round(p.getMaxHealth()) + ". " + Game.dangerReport(p));
        }
        lastHealth = health;

        boolean dead = p.isDeadOrDying();
        if (dead && !wasDead) {
            String msg = agent.takeDeathMessage();
            add("You died" + (msg == null || msg.isBlank() ? "" : " (" + msg + ")") + ". Call respawn.");
        }
        wasDead = dead;

        int food = p.getFoodData().getFoodLevel();
        if (lastFood > 6 && food <= 6) add("Hunger is low (food " + food + "/20): eat soon. You cannot sprint at 6 or below.");
        lastFood = food;

        long time = p.level().getDayTime() % 24000;
        if (lastDayTime >= 0 && lastDayTime < 12542 && time >= 12542) {
            add("Night is falling: hostile mobs will spawn. Shelter, light, or sleep in a bed.");
        }
        lastDayTime = time;

        ItemStack held = p.getMainHandItem();
        if (held.isDamageableItem()) {
            int left = held.getMaxDamage() - held.getDamageValue();
            boolean low = left <= Math.max(3, held.getMaxDamage() / 20);
            if (low && !toolWarned) add(Game.id(held.getItem()) + " is about to break (" + left + " uses left).");
            toolWarned = low;
        }

        for (var e : agent.chat().since(lastChatId, 5)) {
            if (!e.own()) add("Chat: " + e.text());
        }
        lastChatId = agent.chat().lastId();

        if (tick % 10 == 0) {
            reported.values().removeIf(t -> tick - t > 20 * 30);
            for (Entity e : p.level().getEntities(p, p.getBoundingBox().inflate(12), x -> x instanceof Enemy && x.isAlive())) {
                double d = e.distanceTo(p);
                boolean creeperClose = e instanceof Creeper && d <= 7;
                if (d > 12 || (reported.containsKey(e.getId()) && !creeperClose)) continue;
                if (creeperClose && reported.containsKey(e.getId()) && tick - reported.get(e.getId()) < 60) continue;
                reported.put(e.getId(), tick);
                add((creeperClose ? "Creeper is close! " : "Hostile nearby: ") + Game.id(e.getType()) + " id=" + e.getId()
                        + " " + Game.round(d) + " blocks away.");
            }
        }
    }
}
