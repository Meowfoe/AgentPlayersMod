package dev.maddie.agents.game;

import dev.maddie.agents.agent.Agent;
import dev.maddie.agents.agent.AgentPlayer;
import dev.maddie.agents.agent.Body;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Carries out a {@link Pathfinder} path tick by tick (keys, digging, placing, doors), re-planning when needed. */
public final class Navigator {
    public enum Status {RUNNING, ARRIVED, FAILED}

    private static final int MAX_REPLANS = 10;

    private final Pathfinder.Goal goal;
    private final boolean sprint;
    private final Pathfinder.Options options;
    private List<Pathfinder.Step> steps;
    private boolean complete, everPlanned;
    private int index, replans, idleTicks, waitTicks;
    private Digger digger;
    private int blocksBroken, blocksPlaced;
    private String failure = "";

    public Navigator(Pathfinder.Goal goal, boolean sprint, Pathfinder.Options options) {
        this.goal = goal;
        this.sprint = sprint;
        this.options = options;
    }

    public Pathfinder.Goal goal() {
        return goal;
    }

    public String failure() {
        return failure;
    }

    /** e.g. " Dug 3 blocks, placed 2." or "" */
    public String workSummary() {
        if (blocksBroken == 0 && blocksPlaced == 0) return "";
        return " Dug " + blocksBroken + " block(s), placed " + blocksPlaced + ".";
    }

    public boolean atGoal(AgentPlayer p) {
        return goal.hdist(p.getX(), p.getZ()) <= goal.tolerance() + 0.25
                && (goal.y() == null || Math.abs(p.getY() - goal.y()) <= 1.2);
    }

    /** Pause (e.g. to fight): stop digging and release keys; the next tick re-plans from wherever we are. */
    public void pause(Agent a) {
        stop(a);
        steps = null;
    }

    public void stop(Agent a) {
        if (digger != null) digger.abort(a);
        digger = null;
        a.body().releaseKeys();
    }

    public Status tick(Agent a, AgentPlayer p) {
        Body body = a.body();
        if (digger == null && atGoal(p)) return Status.ARRIVED;
        if (steps == null && !plan(p)) return Status.FAILED;
        if (waitTicks > 0) {
            waitTicks--;
            return Status.RUNNING;
        }

        while (index < steps.size() && digger == null && reached(p, steps.get(index))) {
            index++;
            idleTicks = 0;
        }
        if (index >= steps.size()) {
            if (complete) return Status.ARRIVED;
            return replan(a, p, "Could not find a way to the target.") ? Status.RUNNING : Status.FAILED;
        }
        Pathfinder.Step s = steps.get(index);
        if (++idleTicks > (digger != null ? 1200 : 80)) {
            return replan(a, p, "Stuck: the path is blocked by something.") ? Status.RUNNING : Status.FAILED;
        }

        // 1. Dig blocks in the way.
        if (digger != null && Pathfinder.isOpen(p.level(), digger.pos())) { // the block we were digging is gone
            digger = null;
            blocksBroken++;
            idleTicks = 0;
        }
        for (BlockPos b : s.breaks()) {
            if (Pathfinder.isOpen(p.level(), b)) continue;
            body.releaseKeys();
            if (digger == null || !digger.pos().equals(b)) digger = new Digger(b, true);
            switch (digger.tick(a, p)) {
                case DONE -> {
                    digger = null;
                    blocksBroken++;
                    idleTicks = 0;
                }
                case FAILED -> {
                    String why = digger.message();
                    digger = null;
                    return replan(a, p, "Could not dig: " + why) ? Status.RUNNING : Status.FAILED;
                }
                case RUNNING -> {
                }
            }
            return Status.RUNNING;
        }

        // 2. Place a block to walk on (bridge) or to stand on (pillar).
        if (s.place() != null && Pathfinder.isOpen(p.level(), s.place())) {
            if (s.kind() == Pathfinder.Kind.BRIDGE) {
                body.releaseKeys();
                body.sneak = true; // sneak so we cannot slip off the edge
                Direction d = Direction.getNearest(s.pos().getX() - p.getBlockX(), 0, s.pos().getZ() - p.getBlockZ());
                if (!body.placeScaffold(s.place(), s.place().relative(d.getOpposite()), d)) {
                    return failNoBlocks(a);
                }
                blocksPlaced++;
                waitTicks = 2;
                return Status.RUNNING;
            }
            if (s.kind() == Pathfinder.Kind.PILLAR) {
                body.forward = false;
                body.jump = true;
                body.setRotation(p.getYRot(), 90f);
                if (p.getY() > s.place().getY() + 1.05) { // high enough to put a block under our feet
                    if (!body.placeScaffold(s.place(), s.place().below(), Direction.UP)) return failNoBlocks(a);
                    blocksPlaced++;
                    body.jump = false;
                }
                return Status.RUNNING;
            }
        }

        // 3. Open closed doors and gates on the way.
        for (BlockPos b : new BlockPos[]{s.pos(), s.pos().above()}) {
            if (Pathfinder.isClosedDoor(p.level(), b)) {
                body.releaseKeys();
                Vec3 hit = Vec3.atCenterOf(b);
                body.lookAt(hit);
                body.useItemOn(InteractionHand.MAIN_HAND, new BlockHitResult(hit, Game.faceToward(b, p.getEyePosition()), b, false));
                waitTicks = 4;
                return Status.RUNNING;
            }
        }

        // 4. Walk / climb / swim toward the step.
        BlockPos n = s.pos();
        double dx = n.getX() + 0.5 - p.getX(), dz = n.getZ() + 0.5 - p.getZ();
        double hd = Math.sqrt(dx * dx + dz * dz);
        if (hd > 3.5 || Math.abs(n.getY() - p.getY()) > 4.5) { // pushed or fell off the path
            return replan(a, p, "Kept getting knocked off the path.") ? Status.RUNNING : Status.FAILED;
        }
        if (hd > 0.15) body.setRotation((float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f, 0f);
        body.forward = hd > 0.15;
        body.sneak = false;
        boolean water = p.isInWater();
        body.sprint = sprint && !water && s.kind() == Pathfinder.Kind.WALK;
        boolean jump;
        if (s.kind() == Pathfinder.Kind.CLIMB) jump = n.getY() > p.getY() - 0.5; // climb up; let go to climb down
        else if (water) jump = n.getY() >= Math.floor(p.getY()) || p.horizontalCollision; // swim up / out
        else jump = p.onGround() && ((n.getY() > p.getY() + 0.5 && hd < 1.6) || p.horizontalCollision);
        body.jump = jump;
        return Status.RUNNING;
    }

    private Status failNoBlocks(Agent a) {
        stop(a);
        failure = "Ran out of blocks to build with (dirt, cobblestone, planks...).";
        return Status.FAILED;
    }

    private static boolean reached(AgentPlayer p, Pathfinder.Step s) {
        BlockPos n = s.pos();
        double dx = n.getX() + 0.5 - p.getX(), dz = n.getZ() + 0.5 - p.getZ();
        boolean onLevel = s.kind() == Pathfinder.Kind.PILLAR
                ? p.onGround() && p.getY() > n.getY() - 0.2
                : p.getY() > n.getY() - 1.0 && p.getY() < n.getY() + 1.0;
        return dx * dx + dz * dz < 0.4 * 0.4 && onLevel;
    }

    private boolean replan(Agent a, AgentPlayer p, String reason) {
        stop(a);
        if (++replans > MAX_REPLANS) {
            failure = reason;
            return false;
        }
        return plan(p);
    }

    private boolean plan(AgentPlayer p) {
        BlockPos start = p.blockPosition();
        if (!Pathfinder.isOpen(p.level(), start)) start = start.above(); // standing on a slab, path block, etc.
        Pathfinder.Options opts = options;
        if (opts.build() && Game.scaffoldCount(p) == 0) opts = new Pathfinder.Options(opts.dig(), false);
        Pathfinder.Path result = Pathfinder.find(p.level(), start, goal, opts);
        if (result.steps().isEmpty()) {
            failure = (everPlanned ? "Got as close as possible" : "No path toward the target from here")
                    + (opts.dig() || opts.build() ? "" : " on foot (blocked by walls, leaves, gaps or drops; try dig/build options)")
                    + ".";
            return false;
        }
        steps = result.steps();
        everPlanned = true;
        complete = result.reachesGoal();
        index = 0;
        idleTicks = 0;
        return true;
    }
}
