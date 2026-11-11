package dev.maddie.agents.game;

import dev.maddie.agents.agent.Agent;
import dev.maddie.agents.agent.AgentPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Breaks one block over several ticks the way a client does: a start packet, the client-side progress count,
 * then a stop packet once the block should be broken. The server checks the timing itself, so this cannot
 * mine faster than a player; claims, spawn protection and break events all apply.
 */
public final class Digger {
    public enum Status {RUNNING, DONE, FAILED}

    private final BlockPos pos;
    private final boolean autoTool;
    private Block block;
    private Direction face;
    private float progress;
    private int ticks;
    private int confirmTicks = -1; // >= 0 once we are waiting for the server to remove the block
    private boolean noDrop;
    private BlockPos blockedBy;
    private String message = "";

    public Digger(BlockPos pos, boolean autoTool) {
        this.pos = pos;
        this.autoTool = autoTool;
    }

    public BlockPos pos() {
        return pos;
    }

    /** Block id that was broken (or attempted). */
    public String blockId() {
        return block == null ? "?" : Game.id(block);
    }

    /** True if the held tool could not harvest the block, so it dropped nothing. */
    public boolean noDrop() {
        return noDrop;
    }

    public String message() {
        return message;
    }

    /** If the dig failed because another block hides the target, that block. */
    public BlockPos blockedBy() {
        return blockedBy;
    }

    public Status tick(Agent a, AgentPlayer p) {
        Level level = p.level();
        if (!level.hasChunkAt(pos)) return fail(Game.pos(pos) + " is far away (not loaded).");
        BlockState state = level.getBlockState(pos);
        if (block == null) {
            if (state.isAir() || !state.getFluidState().isEmpty() && state.getCollisionShape(level, pos).isEmpty()) {
                return fail("There is no solid block at " + Game.pos(pos) + ".");
            }
            if (state.getDestroySpeed(level, pos) < 0) return fail(Game.id(state.getBlock()) + " is unbreakable.");
            if (!p.canInteractWithBlock(pos, 0)) {
                return fail("Out of reach: " + Game.round(Math.sqrt(p.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos))))
                        + " blocks away (reach " + Game.round(p.blockInteractionRange()) + "). Move closer first.");
            }
            BlockPos obstacle = Game.obstruction(p, pos);
            if (obstacle != null) {
                blockedBy = obstacle;
                return fail("Cannot see it: " + Game.id(level.getBlockState(obstacle).getBlock()) + " at " + Game.pos(obstacle)
                        + " is in the way. Mine that first or move.");
            }
            block = state.getBlock();
            face = Game.faceToward(pos, p.getEyePosition());
            if (autoTool) equipBestTool(a, p, state);
            noDrop = state.requiresCorrectToolForDrops() && !p.getMainHandItem().isCorrectToolForDrops(state);
            a.body().lookAt(Vec3.atCenterOf(pos));
            boolean instant = p.getAbilities().instabuild || state.getDestroyProgress(p, level, pos) >= 1f;
            a.body().dig(Action.START_DESTROY_BLOCK, pos, face);
            a.body().swing(InteractionHand.MAIN_HAND);
            if (instant) confirmTicks = 0;
            return level.getBlockState(pos).is(block) ? Status.RUNNING : Status.DONE;
        }
        if (!state.is(block)) return Status.DONE;
        if (confirmTicks >= 0) {
            if (++confirmTicks > 20) return fail("The server did not break the block (protected area, or not allowed?).");
            return Status.RUNNING;
        }
        if (++ticks > 20 * 55) return fail("Mining is taking too long; use a better tool.");
        a.body().lookAt(Vec3.atCenterOf(pos));
        progress += state.getDestroyProgress(p, level, pos);
        a.body().swing(InteractionHand.MAIN_HAND);
        if (progress >= 1f) {
            a.body().dig(Action.STOP_DESTROY_BLOCK, pos, face);
            confirmTicks = 0;
            if (!level.getBlockState(pos).is(block)) return Status.DONE;
        }
        return Status.RUNNING;
    }

    /** Stops an unfinished dig on the server (which also clears the crack overlay for watchers). */
    public void abort(Agent a) {
        if (block == null || confirmTicks >= 0 || a.player() == null) return;
        a.body().dig(Action.ABORT_DESTROY_BLOCK, pos, face);
    }

    private Status fail(String msg) {
        message = msg;
        return Status.FAILED;
    }

    private static void equipBestTool(Agent a, AgentPlayer p, BlockState state) {
        Inventory inv = p.getInventory();
        int best = -1;
        float bestScore = toolScore(inv.getSelected(), state);
        for (int i = 0; i < 36; i++) {
            float s = toolScore(inv.items.get(i), state);
            if (s > bestScore + 0.001f) {
                best = i;
                bestScore = s;
            }
        }
        if (best >= 0) {
            try {
                a.body().equip(best);
            } catch (Exception ignored) {
                // a container screen hides that slot; mine with what we hold
            }
        }
    }

    private static float toolScore(ItemStack stack, BlockState state) {
        float speed = stack.getDestroySpeed(state);
        return state.requiresCorrectToolForDrops() && stack.isCorrectToolForDrops(state) ? speed + 1000 : speed;
    }
}
