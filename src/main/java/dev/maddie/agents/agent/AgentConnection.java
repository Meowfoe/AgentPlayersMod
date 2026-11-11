package dev.maddie.agents.agent;

import dev.maddie.agents.AgentPlayersMod;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.ChannelAttributes;
import net.neoforged.neoforge.network.registration.NetworkPayloadSetup;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.jetbrains.annotations.Nullable;


/**
 * The agent's "network connection". There is no socket: everything the server sends the agent goes to
 * {@link Agent#onPacket} (chat, opened screens, sign editors...), and the agent's actions are fed straight into
 * its {@code ServerGamePacketListenerImpl}, exactly like packets from a real client.
 */
public final class AgentConnection extends Connection {
    private final Agent agent;
    private final EmbeddedChannel embedded;

    public AgentConnection(Agent agent) {
        super(PacketFlow.SERVERBOUND);
        this.agent = agent;
        // An open channel makes isConnected() true and gives NeoForge somewhere to keep its channel attributes.
        this.embedded = new EmbeddedChannel(this);
        negotiateEverything();
    }

    @Override
    public void send(Packet<?> packet, @Nullable PacketSendListener listener, boolean flush) {
        try {
            if (packet instanceof ClientboundBundlePacket bundle) {
                for (Packet<?> sub : bundle.subPackets()) agent.onPacket(sub);
            } else {
                agent.onPacket(packet);
            }
        } catch (Throwable t) {
            AgentPlayersMod.LOG.debug("Agent {} could not observe {}", agent.name(), packet.getClass().getSimpleName(), t);
        }
        if (listener != null) listener.onSuccess();
    }

    @Override
    public void flushChannel() {
    }

    /** Drops anything that still ended up in the embedded channel (nothing should), so it can't pile up. */
    void drain() {
        embedded.releaseOutbound();
        embedded.releaseInbound();
    }

    /**
     * Marks every registered mod payload as negotiated, like a NeoForge client with the full modpack. Without
     * this, a mod sending its packet to the agent throws ("may not be sent to the client"), and an agent pressing
     * a mod's button would be disconnected for using an unknown channel. NeoForge's own game-test helper does
     * exactly this for mock connections.
     */
    private void negotiateEverything() {
        try {
            NetworkRegistry.configureMockConnection(this);
        } catch (RuntimeException e) {
            AgentPlayersMod.LOG.error("Could not set up mod channels for agent {}; mod packets to it may fail", agent.name(), e);
            ChannelAttributes.setPayloadSetup(this, NetworkPayloadSetup.empty());
            ChannelAttributes.setConnectionType(this, ConnectionType.NEOFORGE);
        }
    }
}
