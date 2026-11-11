package dev.maddie.agents.game;

import dev.maddie.agents.agent.Agent;
import dev.maddie.agents.agent.AgentPlayer;
import dev.maddie.agents.mcp.ToolException;
import net.minecraft.world.entity.Entity;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs one multi-tick action (walking, mining, ...) at a time for one agent, driven by the agent's own tick.
 * Automatic behaviors (defending, eating) can briefly take over: the action is paused, the behavior runs, and the
 * action resumes. State is touched only on the server thread.
 */
public final class ActionRunner {

    public interface TickAction {
        String name();

        /** Called every tick before physics. Return null to keep going, or a result message when done. */
        String tick(Agent agent, AgentPlayer player) throws Exception;

        /** Called once when the action ends for any reason. */
        default void stop(Agent agent) {
            agent.body().releaseKeys();
        }

        /** Called when an automatic behavior takes over for a while; ticking resumes afterwards. */
        default void pause(Agent agent) {
            agent.body().releaseKeys();
        }

        /** Whether taking damage should abort this action so the AI can react. */
        default boolean interruptOnDamage() {
            return true;
        }
    }

    private final Agent agent;
    private TickAction current;
    private CompletableFuture<String> future;
    private TickAction interjection; // an automatic behavior that currently has control
    private float lastHealth = -1;

    public ActionRunner(Agent agent) {
        this.agent = agent;
    }

    public synchronized String currentName() {
        if (interjection != null) return interjection.name() + (current == null ? "" : " (then " + current.name() + ")");
        return current == null ? null : current.name();
    }

    public synchronized boolean busy() {
        return current != null || interjection != null;
    }

    synchronized CompletableFuture<String> start(TickAction action) {
        cancel("Interrupted because a new action started.");
        current = action;
        future = new CompletableFuture<>();
        return future;
    }

    /** Cancels the running action and any automatic behavior. */
    public synchronized boolean cancel(String reason) {
        boolean had = current != null || interjection != null;
        if (interjection != null) {
            stopQuietly(interjection);
            interjection = null;
        }
        if (current != null) finish(reason, null);
        return had;
    }

    /** Lets an automatic behavior take over now (no-op if one is already running). */
    synchronized void interject(TickAction behavior) {
        if (interjection != null) return;
        if (current != null) {
            try {
                current.pause(agent);
            } catch (Exception ignored) {
            }
        }
        agent.body().releaseKeys();
        interjection = behavior;
    }

    public synchronized void tick(AgentPlayer p) {
        if (p.isDeadOrDying()) {
            lastHealth = -1;
            if (interjection != null) {
                stopQuietly(interjection);
                interjection = null;
            }
            if (current != null) finish(null, new ToolException(current.name() + " ended: you died. Call respawn."));
            return;
        }

        float health = p.getHealth();
        if (lastHealth >= 0 && health < lastHealth - 0.01f && interjection == null) {
            Behaviors b = agent.behaviors();
            Entity attacker = b.autoDefend ? Combat.nearestHostile(p, 6) : null;
            if (attacker != null) {
                agent.events().add("Auto-defend: fighting " + Game.id(attacker.getType()) + " id=" + attacker.getId() + ".");
                interject(new Combat.Fight(attacker, 20 * 30, b.fleeHealth));
            } else if (current != null && current.interruptOnDamage()) {
                finish(null, new ToolException(current.name() + " was interrupted: you took " + Game.round(lastHealth - health)
                        + " damage (health now " + Game.round(health) + "/" + Game.round(p.getMaxHealth()) + "). "
                        + Game.dangerReport(p)));
            }
        }
        lastHealth = health;

        if (interjection == null) {
            TickAction auto = agent.behaviors().maybeStart(agent, p);
            if (auto != null) interject(auto);
        }

        if (interjection != null) {
            String result;
            try {
                result = interjection.tick(agent, p);
            } catch (Exception e) {
                result = interjection.name() + " stopped: " + e.getMessage();
            }
            if (result != null) {
                agent.events().add(result);
                stopQuietly(interjection);
                interjection = null;
            }
            return; // the paused action continues next tick
        }

        if (current == null) return;
        try {
            String result = current.tick(agent, p);
            if (result != null) finish(result, null);
        } catch (Exception e) {
            finish(null, e);
        }
    }

    private void stopQuietly(TickAction a) {
        try {
            a.stop(agent);
        } catch (Exception ignored) {
        }
    }

    private void finish(String result, Exception error) {
        TickAction a = current;
        CompletableFuture<String> f = future;
        current = null;
        future = null;
        stopQuietly(a);
        if (error != null) f.completeExceptionally(error);
        else f.complete(result);
    }

    /** Starts {@code action} from any thread and blocks until it finishes (or {@code maxSeconds} pass). */
    public String run(TickAction action, int maxSeconds) throws Exception {
        CompletableFuture<String> f = Game.call(() -> {
            Game.player(agent);
            return start(action);
        });
        try {
            return f.get(maxSeconds + 35L, TimeUnit.SECONDS); // slack for automatic fights/eating in between
        } catch (ExecutionException e) {
            throw Game.unwrap(e);
        } catch (TimeoutException e) {
            Game.call(() -> {
                synchronized (this) {
                    return current == action && cancel("Timed out.");
                }
            });
            throw new ToolException(action.name() + " timed out after " + maxSeconds + "s (is the server lagging?).");
        }
    }
}
