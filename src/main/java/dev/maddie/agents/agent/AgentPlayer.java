package dev.maddie.agents.agent;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * A server player with no client. A real player's movement comes from the client's position packets; an agent's
 * comes from the server simulating it, the way the client would: we set the movement inputs (Body), then run
 * the same physics a client runs (doTick: LivingEntity.aiStep and travel). The connection is never ticked,
 * because its tick would snap the player back to the last position a client reported.
 */
public final class AgentPlayer extends ServerPlayer {
    public final Agent agent;

    public AgentPlayer(MinecraftServer server, ServerLevel level, GameProfile profile, ClientInformation info, Agent agent) {
        super(server, level, profile, info);
        this.agent = agent;
    }

    @Override
    public void tick() {
        agent.beforeTick(this);
        if (this.tickCount % 10 == 0) {
            // The packet listener's anti-cheat baseline; nothing else moves it for us.
            this.connection.resetPosition();
        }
        super.tick();
        this.doTick();
        this.serverLevel().getChunkSource().move(this);
    }
}
