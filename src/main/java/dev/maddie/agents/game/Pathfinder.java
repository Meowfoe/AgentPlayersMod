package dev.maddie.agents.game;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * A* over standing positions ("feet" blocks). Always: walk (incl. diagonals without corner cutting), step up
 * 1 block, drop up to 3, swim, climb ladders/vines, pass doors and fence gates. Optionally: dig through blocks,
 * dig straight down, pillar up and bridge over gaps. Paths avoid hazards and keep away from hostile mobs.
 */
public final class Pathfinder {
    private static final int MAX_NODES = 20000;
    private static final int MAX_FALL = 3;
    private static final double HEURISTIC_WEIGHT = 1.5; // >1 trades a little optimality for much faster searches
    private static final Set<Block> DANGER = Set.of(Blocks.LAVA, Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.MAGMA_BLOCK,
            Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE, Blocks.SWEET_BERRY_BUSH, Blocks.COBWEB, Blocks.POWDER_SNOW,
            Blocks.CACTUS, Blocks.WITHER_ROSE);

    private static final int OPEN = 1, FLOOR = 2, WATER = 4, CLIMB = 8, DOOR = 16, BREAKABLE = 32;

    /** Where to go: x/z point, optional feet y (±1), and how close counts as arrived. */
    public record Goal(double x, Integer y, double z, double tolerance) {
        public double hdist(double px, double pz) {
            double dx = px - x, dz = pz - z;
            return Math.sqrt(dx * dx + dz * dz);
        }

        boolean reached(BlockPos p) {
            return hdist(p.getX() + 0.5, p.getZ() + 0.5) <= tolerance && (y == null || Math.abs(p.getY() - y) <= 1);
        }

        double estimate(BlockPos p) {
            return hdist(p.getX() + 0.5, p.getZ() + 0.5) + (y == null ? 0 : Math.abs(p.getY() - y));
        }
    }

    /** What the path may do besides walking. */
    public record Options(boolean dig, boolean build) {
        public static final Options WALK = new Options(false, false);
    }

    public enum Kind {WALK, JUMP_UP, FALL, SWIM, CLIMB, DIG_DOWN, PILLAR, BRIDGE}

    /**
     * One move: end up standing at {@code pos}. First break {@code breaks} (in order), then for BRIDGE place a
     * block at {@code place} against the side of the block you stand on; for PILLAR jump and place at {@code place}.
     */
    public record Step(BlockPos pos, Kind kind, List<BlockPos> breaks, BlockPos place) {
    }

    /** Steps to take; {@code reachesGoal} is false for a best-effort partial path. */
    public record Path(List<Step> steps, boolean reachesGoal) {
    }

    private record Info(int flags, double digCost) {
    }

    private static final class Node {
        final BlockPos pos;
        final double h;
        double g = Double.MAX_VALUE;
        Node parent;
        Step via;
        boolean closed;

        Node(BlockPos pos, double h) {
            this.pos = pos;
            this.h = h;
        }
    }

    private record Entry(Node node, double f) {
    }

    private final Level level;
    private final Options options;
    private final Map<Long, Info> cache = new HashMap<>();
    private final List<Vec3> creepers = new ArrayList<>();
    private final List<Vec3> hostiles = new ArrayList<>();

    private Pathfinder(Level level, Options options) {
        this.level = level;
        this.options = options;
    }

    public static Path find(Level level, BlockPos start, Goal goal, Options options) {
        Pathfinder pf = new Pathfinder(level, options);
        for (Entity e : level.getEntities((Entity) null, new AABB(start).inflate(48), e -> e instanceof Enemy && e.isAlive())) {
            (e instanceof Creeper ? pf.creepers : pf.hostiles).add(e.position());
        }
        return pf.search(start, goal);
    }

    /** True if a player's body can occupy this block (no collision, not dangerous; ladders and doors count). */
    public static boolean isOpen(Level level, BlockPos pos) {
        return (new Pathfinder(level, Options.WALK).info(pos).flags & OPEN) != 0;
    }

    /** True if this is a closed door or fence gate that can be opened by hand. */
    public static boolean isClosedDoor(Level level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return isOpenableDoor(s) && s.hasProperty(DoorBlock.OPEN) && !s.getValue(DoorBlock.OPEN);
    }

    private static boolean isOpenableDoor(BlockState s) {
        return (s.getBlock() instanceof DoorBlock d && d.type().canOpenByHand()) || s.getBlock() instanceof FenceGateBlock;
    }

    private Path search(BlockPos start, Goal goal) {
        Map<Long, Node> nodes = new HashMap<>();
        PriorityQueue<Entry> open = new PriorityQueue<>(Comparator.comparingDouble(Entry::f));
        Node s = new Node(start, goal.estimate(start));
        s.g = 0;
        nodes.put(start.asLong(), s);
        open.add(new Entry(s, s.h));
        Node best = s;
        int expanded = 0;
        while (!open.isEmpty() && expanded < MAX_NODES) {
            Node cur = open.poll().node();
            if (cur.closed) continue;
            cur.closed = true;
            expanded++;
            if (goal.reached(cur.pos)) return new Path(trace(cur), true);
            if (cur.h < best.h - 1e-6) best = cur;
            // A block we place along the path is not in the world yet; remember it to keep bridging/pillaring.
            boolean onPlaced = cur.via != null && cur.via.place() != null;
            for (Step step : neighbors(cur.pos, onPlaced)) {
                double g = cur.g + cost(cur.pos, step);
                Node n = nodes.computeIfAbsent(step.pos().asLong(), k -> new Node(step.pos(), goal.estimate(step.pos())));
                if (n.closed || g >= n.g) continue;
                n.g = g;
                n.parent = cur;
                n.via = step;
                open.add(new Entry(n, g + HEURISTIC_WEIGHT * n.h));
            }
        }
        return new Path(best == s ? List.of() : trace(best), false);
    }

    private static List<Step> trace(Node end) {
        List<Step> out = new ArrayList<>();
        for (Node n = end; n.parent != null; n = n.parent) out.add(n.via);
        Collections.reverse(out);
        return out;
    }

    private double cost(BlockPos from, Step s) {
        boolean diagonal = from.getX() != s.pos().getX() && from.getZ() != s.pos().getZ();
        double c = switch (s.kind()) {
            case WALK -> diagonal ? 1.42 : 1;
            case JUMP_UP -> 2.5;
            case FALL -> 2;
            case SWIM -> 2;
            case CLIMB -> 1.5;
            case DIG_DOWN -> 2;
            case PILLAR -> 6;
            case BRIDGE -> 5;
        };
        for (BlockPos b : s.breaks()) c += info(b).digCost;
        if (has(s.pos(), WATER)) c += 1;
        if (has(s.pos(), DOOR) || has(s.pos().above(), DOOR)) c += 1;
        Vec3 center = Vec3.atBottomCenterOf(s.pos());
        for (Vec3 cp : creepers) if (cp.distanceToSqr(center) < 36) c += 15;
        for (Vec3 hp : hostiles) if (hp.distanceToSqr(center) < 16) c += 5;
        return c;
    }

    /** @param onPlaced the block under {@code p} will have been placed by the previous step */
    private List<Step> neighbors(BlockPos p, boolean onPlaced) {
        List<Step> out = new ArrayList<>(16);
        boolean solidBelow = onPlaced || has(p.below(), FLOOR);
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos q = p.relative(d);
            BlockPos up = q.above();
            if (canStand(q)) {
                out.add(new Step(q, has(q, WATER) ? Kind.SWIM : Kind.WALK, List.of(), null));
            } else if (options.dig() && clearable(q) && clearable(up) && supported(q)) {
                out.add(new Step(q, Kind.WALK, toBreak(up, q), null)); // tunnel
            } else if (options.build() && has(q, OPEN) && has(up, OPEN) && !has(q.below(), FLOOR)
                    && canPlaceInto(q.below()) && solidBelow && !has(p, WATER)) {
                out.add(new Step(q, Kind.BRIDGE, List.of(), q.below()));
            }

            if (canStand(up) && has(p.above(2), OPEN)) {
                out.add(new Step(up, Kind.JUMP_UP, List.of(), null));
            } else if (options.dig() && has(q, FLOOR) && clearable(up) && clearable(up.above()) && clearable(p.above(2))) {
                out.add(new Step(up, Kind.JUMP_UP, toBreak(p.above(2), up.above(), up), null));
            }

            if (has(q, OPEN) && has(up, OPEN)) { // walk off the edge and fall
                for (int k = 1; k <= MAX_FALL; k++) {
                    BlockPos f = q.below(k);
                    if (canStand(f)) {
                        out.add(new Step(f, Kind.FALL, List.of(), null));
                        break;
                    }
                    if (!has(f, OPEN)) break;
                }
            } else if (options.dig() && clearable(up) && clearable(q) && clearable(q.below()) && has(q.below(2), FLOOR)) {
                out.add(new Step(q.below(), Kind.FALL, toBreak(up, q, q.below()), null)); // dig a step down
            }
        }
        for (Direction ns : new Direction[]{Direction.NORTH, Direction.SOUTH}) {
            for (Direction ew : new Direction[]{Direction.EAST, Direction.WEST}) {
                BlockPos a = p.relative(ns), b = p.relative(ew), q = a.relative(ew);
                if (canStand(q) && !has(q, WATER) && has(a, OPEN) && has(a.above(), OPEN) && has(b, OPEN) && has(b.above(), OPEN)
                        && !has(a, DOOR) && !has(b, DOOR) && !has(q, DOOR)) {
                    out.add(new Step(q, Kind.WALK, List.of(), null));
                }
            }
        }
        BlockPos above = p.above(), below = p.below();
        if (has(p, WATER)) {
            if (canStand(above)) out.add(new Step(above, Kind.SWIM, List.of(), null));
            if (has(below, WATER) && canStand(below)) out.add(new Step(below, Kind.SWIM, List.of(), null));
        }
        if ((has(p, CLIMB) || has(above, CLIMB)) && has(above, OPEN) && has(p.above(2), OPEN)) {
            out.add(new Step(above, Kind.CLIMB, List.of(), null));
        }
        if (has(below, CLIMB) && has(below, OPEN)) out.add(new Step(below, Kind.CLIMB, List.of(), null));
        if (options.build() && solidBelow && !has(p, WATER) && !has(p, CLIMB) && clearable(p.above(2))) {
            out.add(new Step(above, Kind.PILLAR, toBreak(p.above(2)), p));
        }
        if (options.dig() && has(below, BREAKABLE) && has(below.below(), FLOOR) && !has(p, CLIMB)) {
            out.add(new Step(below, Kind.DIG_DOWN, List.of(below), null));
        }
        return out;
    }

    /** The subset of {@code blocks} that is not already open, in order. */
    private List<BlockPos> toBreak(BlockPos... blocks) {
        List<BlockPos> out = new ArrayList<>(blocks.length);
        for (BlockPos b : blocks) if (!has(b, OPEN)) out.add(b);
        return out;
    }

    private boolean canStand(BlockPos p) {
        return has(p, OPEN) && has(p.above(), OPEN) && (has(p, WATER) || has(p, CLIMB) || has(p.below(), FLOOR));
    }

    private boolean supported(BlockPos p) {
        return has(p.below(), FLOOR) || has(p, WATER) || has(p, CLIMB);
    }

    private boolean clearable(BlockPos p) {
        return has(p, OPEN) || has(p, BREAKABLE);
    }

    private boolean canPlaceInto(BlockPos p) {
        Info i = info(p);
        return (i.flags & OPEN) != 0 && (i.flags & (CLIMB | DOOR)) == 0;
    }

    private boolean has(BlockPos p, int flag) {
        return (info(p).flags & flag) != 0;
    }

    private Info info(BlockPos p) {
        return cache.computeIfAbsent(p.asLong(), k -> compute(p));
    }

    private Info compute(BlockPos p) {
        if (!level.hasChunkAt(p)) return new Info(0, 0); // unloaded: treat as a wall
        BlockState s = level.getBlockState(p);
        boolean danger = DANGER.contains(s.getBlock()) || s.getFluidState().is(FluidTags.LAVA);
        if (danger) return new Info(0, 0);
        VoxelShape shape = s.getCollisionShape(level, p);
        int f = 0;
        if (isOpenableDoor(s)) {
            f |= OPEN | DOOR;
        } else if (s.is(BlockTags.CLIMBABLE)) {
            f |= OPEN | CLIMB;
        } else if (shape.isEmpty()) {
            f |= OPEN;
        } else if (shape.max(Direction.Axis.Y) <= 1.0) {
            f |= FLOOR; // not fences/walls: too tall to jump onto
        }
        if (s.getFluidState().is(FluidTags.WATER)) f |= WATER;
        double digCost = 0;
        float hardness = s.getDestroySpeed(level, p);
        if ((f & OPEN) == 0 && !shape.isEmpty() && hardness >= 0 && hardness < 20 && !s.hasBlockEntity()
                && s.getFluidState().isEmpty() && !nextToLava(p)) {
            f |= BREAKABLE;
            digCost = 1 + hardness * 4;
        }
        return new Info(f, digCost);
    }

    private boolean nextToLava(BlockPos p) {
        for (Direction d : Direction.values()) {
            if (level.getFluidState(p.relative(d)).is(FluidTags.LAVA)) return true;
        }
        return false;
    }
}
