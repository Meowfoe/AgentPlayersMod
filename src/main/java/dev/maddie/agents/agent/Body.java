package dev.maddie.agents.agent;

import dev.maddie.agents.game.Game;
import dev.maddie.agents.mcp.ToolException;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.CommonHooks;

import java.util.List;
import java.util.Optional;

/**
 * The agent's hands and feet. Movement is held keys (applied every tick before physics, the way LocalPlayer
 * turns key presses into movement). Every other action is the packet a real client would send, handed to the
 * agent's own packet listener: reach checks, claim protection and every mod's event hooks treat it like a player.
 * Server thread only.
 */
public final class Body {
    private final Agent agent;
    public boolean forward, back, left, right, jump, sprint, sneak;
    private int sequence = 1;

    Body(Agent agent) {
        this.agent = agent;
    }

    private AgentPlayer p() {
        return agent.player();
    }

    private ServerGamePacketListenerImpl net() {
        return p().connection;
    }

    public void releaseKeys() {
        forward = back = left = right = jump = sprint = sneak = false;
    }

    /** Turns held keys into movement input, like LocalPlayer.aiStep. Called every tick before physics. */
    void apply(AgentPlayer p) {
        float fwd = (forward ? 1 : 0) - (back ? 1 : 0);
        float side = (left ? 1 : 0) - (right ? 1 : 0);
        if (sneak) {
            float k = (float) Mth.clamp(p.getAttributeValue(Attributes.SNEAKING_SPEED), 0, 1);
            fwd *= k;
            side *= k;
        }
        if (p.isUsingItem() && !p.isPassenger()) {
            fwd *= 0.2f;
            side *= 0.2f;
        }
        p.zza = fwd;
        p.xxa = side;
        p.setJumping(jump);
        if (p.isShiftKeyDown() != sneak) p.setShiftKeyDown(sneak);
        boolean canSprint = forward && !back && !sneak && !p.isUsingItem() && !p.hasEffect(MobEffects.BLINDNESS)
                && (p.getFoodData().getFoodLevel() > 6 || p.getAbilities().mayfly);
        boolean wantSprint = sprint && canSprint && !(p.horizontalCollision && !p.minorHorizontalCollision);
        if (p.isSprinting() != wantSprint) p.setSprinting(wantSprint);
        if (forward || back || left || right || jump) p.resetLastActionTime();
    }

    // ---- looking ----

    public void lookAt(Vec3 target) {
        AgentPlayer p = p();
        Vec3 eye = p.getEyePosition();
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f;
        float pitch = (float) -(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * Mth.RAD_TO_DEG);
        setRotation(yaw, pitch);
    }

    public void setRotation(float yaw, float pitch) {
        AgentPlayer p = p();
        // Turn the short way round instead of snapping, so the head does not spin for watchers.
        float y = p.getYRot() + Mth.wrapDegrees(yaw - p.getYRot());
        p.setYRot(y);
        p.setYHeadRot(y);
        p.setYBodyRot(y);
        p.setXRot(Mth.clamp(pitch, -90f, 90f));
    }

    // ---- the packets a client would send ----

    public void dig(ServerboundPlayerActionPacket.Action action, BlockPos pos, Direction face) {
        net().handlePlayerAction(new ServerboundPlayerActionPacket(action, pos, face, sequence++));
    }

    public void useItemOn(InteractionHand hand, BlockHitResult hit) {
        net().handleUseItemOn(new ServerboundUseItemOnPacket(hand, hit, sequence++));
    }

    public void useItem(InteractionHand hand) {
        net().handleUseItem(new ServerboundUseItemPacket(hand, sequence++, p().getYRot(), p().getXRot()));
    }

    public void releaseUsingItem() {
        if (p().isUsingItem()) {
            net().handlePlayerAction(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM,
                    BlockPos.ZERO, Direction.DOWN));
        }
    }

    public void attack(Entity target) {
        net().handleInteract(ServerboundInteractPacket.createAttackPacket(target, sneak));
    }

    public void interact(Entity target, InteractionHand hand) {
        if (target instanceof ArmorStand) { // armor stands only answer the "interact at" variant
            Vec3 at = target.getBoundingBox().getCenter().subtract(target.position());
            net().handleInteract(ServerboundInteractPacket.createInteractionPacket(target, sneak, hand, at));
            return;
        }
        net().handleInteract(ServerboundInteractPacket.createInteractionPacket(target, sneak, hand));
    }

    public void swing(InteractionHand hand) {
        net().handleAnimate(new ServerboundSwingPacket(hand));
    }

    public void selectHotbar(int slot) {
        net().handleSetCarriedItem(new ServerboundSetCarriedItemPacket(slot));
    }

    public void click(int slot, int button, ClickType type) {
        AbstractContainerMenu m = p().containerMenu;
        net().handleContainerClick(new ServerboundContainerClickPacket(m.containerId, m.getStateId(), slot, button, type,
                m.getCarried().copy(), new Int2ObjectOpenHashMap<>()));
    }

    public void menuButton(int id) {
        net().handleContainerButtonClick(new ServerboundContainerButtonClickPacket(p().containerMenu.containerId, id));
    }

    public void closeContainer() {
        net().handleContainerClose(new ServerboundContainerClosePacket(p().containerMenu.containerId));
    }

    public void drop(boolean all) {
        net().handlePlayerAction(new ServerboundPlayerActionPacket(all ? ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS
                : ServerboundPlayerActionPacket.Action.DROP_ITEM, BlockPos.ZERO, Direction.DOWN));
    }

    public void swapHands() {
        net().handlePlayerAction(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                BlockPos.ZERO, Direction.DOWN));
    }

    public void writeSign(BlockPos pos, boolean front, List<String> lines) {
        String[] l = new String[4];
        for (int i = 0; i < 4; i++) l[i] = i < lines.size() ? lines.get(i) : "";
        net().handleSignUpdate(new ServerboundSignUpdatePacket(pos, front, l[0], l[1], l[2], l[3]));
    }

    public void renameItem(String name) {
        net().handleRenameItem(new ServerboundRenameItemPacket(name));
    }

    public void selectTrade(int index) {
        net().handleSelectTrade(new ServerboundSelectTradePacket(index));
    }

    public void editBook(int slot, List<String> pages, Optional<String> title) {
        net().handleEditBook(new ServerboundEditBookPacket(slot, pages, title));
    }

    public void respawn() {
        net().handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
    }

    /** A mod's serverbound payload, exactly as its client-side button would send it. */
    public void sendPayload(CustomPacketPayload payload) {
        net().handleCustomPayload(new ServerboundCustomPayloadPacket(payload));
    }

    /**
     * Chat as the agent. Goes through NeoForge's chat decorator, so ServerChatEvent fires (the Discord bridge
     * relays it) and chat mods can decorate it. Sent as an unsigned "disguised" message: there is no client to
     * sign it, and other clients then show it like normal chat without a warning marker.
     */
    public void chat(String text) {
        AgentPlayer p = p();
        Component decorated = CommonHooks.getServerChatSubmittedDecorator().decorate(p, Component.literal(text));
        if (decorated == null) return; // cancelled by a mod
        p.server.getPlayerList().broadcastChatMessage(PlayerChatMessage.system(text).withUnsignedContent(decorated), p,
                ChatType.bind(ChatType.CHAT, p));
        p.resetLastActionTime();
    }

    public void command(String command) {
        AgentPlayer p = p();
        p.server.getCommands().performPrefixedCommand(p.createCommandSourceStack(), command);
        p.resetLastActionTime();
    }

    // ---- inventory helpers ----

    /** Puts the item at inventory index {@code invIndex} into the main hand. */
    public void equip(int invIndex) throws ToolException {
        AgentPlayer p = p();
        Inventory inv = p.getInventory();
        if (invIndex < 9) {
            selectHotbar(invIndex);
            return;
        }
        for (Slot s : p.containerMenu.slots) {
            if (s.container == inv && s.getContainerSlot() == invIndex) {
                click(s.index, inv.selected, ClickType.SWAP);
                return;
            }
        }
        throw new ToolException("Could not reach that inventory slot from the open screen. Close it first.");
    }

    public void equipItem(String itemName) throws ToolException {
        var item = Game.resolveItem(itemName);
        AgentPlayer p = p();
        if (p.getMainHandItem().is(item)) return;
        int idx = Game.findItem(p.getInventory(), item);
        if (idx < 0) throw new ToolException("You have no " + Game.id(item) + " in your inventory.");
        equip(idx);
    }

    /** Places a scaffold block at {@code target} against {@code face} of {@code support}. Returns false if impossible. */
    public boolean placeScaffold(BlockPos target, BlockPos support, Direction face) {
        AgentPlayer p = p();
        if (!Game.isScaffold(p.getMainHandItem())) {
            int idx = Game.findScaffold(p.getInventory());
            if (idx < 0) return false;
            try {
                equip(idx);
            } catch (ToolException e) {
                return false;
            }
        }
        Vec3 hit = Vec3.atCenterOf(support).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5));
        lookAt(hit);
        ItemStack before = p.getMainHandItem().copy();
        useItemOn(InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, support, false));
        swing(InteractionHand.MAIN_HAND);
        return !p.level().getBlockState(target).canBeReplaced() || p.getMainHandItem().getCount() < before.getCount();
    }
}
