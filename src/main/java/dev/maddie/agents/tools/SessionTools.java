package dev.maddie.agents.tools;

import dev.maddie.agents.agent.Agent;
import dev.maddie.agents.agent.AgentPlayer;
import dev.maddie.agents.agent.Agents;
import dev.maddie.agents.game.Game;
import dev.maddie.agents.mcp.Tool;
import dev.maddie.agents.mcp.Tool.Schema;
import dev.maddie.agents.mcp.ToolException;
import dev.maddie.agents.mcp.ToolResult;

import java.util.List;

/** Entering and leaving the game. These work while the agent is offline. */
final class SessionTools {
    private SessionTools() {
    }

    static void register(List<Tool> out) {
        out.add(new Tool("join",
                "Enter the game as your own player. You appear where you last left (or at spawn the first time), "
                        + "with the inventory you had. Other players see you join.",
                Schema.of().build(), false, false, (agent, a) -> Game.call(() -> {
            if (agent.online()) return ToolResult.text("You are already in the game. " + Game.where(agent.player()));
            if (!agent.entry().allow_self_join) {
                throw new ToolException("You cannot join on your own; ask " + owner(agent) + " to bring you in with /agent spawn.");
            }
            AgentPlayer p;
            try {
                p = Agents.spawn(Game.server(), agent);
            } catch (IllegalStateException e) {
                throw new ToolException(e.getMessage());
            }
            return ToolResult.text("Joined as " + agent.name() + " in " + p.level().dimension().location() + ". "
                    + Game.where(p) + " Health " + Game.round(p.getHealth()) + ", food " + p.getFoodData().getFoodLevel() + "/20.");
        })));

        out.add(new Tool("leave", "Leave the game (log out). Your inventory and position are saved.",
                Schema.of().build(), false, false, (agent, a) -> Game.call(() -> {
            if (!agent.online()) return ToolResult.text("You are not in the game.");
            Agents.despawn(agent, "Left the game.");
            return ToolResult.text("Left the game.");
        })));
    }

    private static String owner(Agent a) {
        return a.entry().owner.isBlank() ? "a server admin" : a.entry().owner;
    }
}
