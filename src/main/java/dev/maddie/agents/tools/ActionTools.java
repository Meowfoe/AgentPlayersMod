package dev.maddie.agents.tools;

import dev.maddie.agents.agent.Agent;
import dev.maddie.agents.agent.AgentPlayer;
import dev.maddie.agents.agent.Body;
import dev.maddie.agents.game.ActionRunner.TickAction;
import dev.maddie.agents.game.Behaviors;
import dev.maddie.agents.game.Combat;
import dev.maddie.agents.game.Digger;
import dev.maddie.agents.game.Game;
import dev.maddie.agents.game.Navigator;
import dev.maddie.agents.game.Pathfinder;
import dev.maddie.agents.mcp.Args;
import dev.maddie.agents.mcp.Tool;
import dev.maddie.agents.mcp.Tool.Schema;
import dev.maddie.agents.mcp.ToolException;
import dev.maddie.agents.mcp.ToolResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.StringUtil;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Tools that make the player do things. */
final class ActionTools {
    private ActionTools() {
    }

    static void register(List<Tool> out) {
        out.add(new Tool("look",
                "Turn your head. Give x,y,z to look at the center of that block, or yaw/pitch in degrees "
                        + "(yaw: 0=south, 90=west, 180=north, -90=east; pitch: -90 up, 90 down).",
                Schema.of()
                        .number("x", "Block X to look at", false).number("y", "Block Y to look at", false)
                        .number("z", "Block Z to look at", false)
                        .number("yaw", "Yaw in degrees", false).number("pitch", "Pitch in degrees", false)
                        .build(),
                false, ActionTools::look));

        out.add(new Tool("move_to",
                "Walk to the block at x,z (and optionally y) using pathfinding: walks around obstacles, jumps up 1-block "
                        + "steps, drops down up to 3 blocks, swims, climbs ladders/vines, opens doors and gates, avoids "
                        + "lava/fire/cactus and keeps away from hostile mobs. With dig=true it tunnels through blocks; with "
                        + "build=true it pillars up and bridges gaps using dirt/cobblestone/planks from your inventory. "
                        + "If no path exists it gets as close as it can and says so. Long trips are planned in segments. "
                        + "Stops early if you take damage, unless continue_when_hurt is true (use that to flee).",
                Schema.of()
                        .number("x", "Target block X", true).number("z", "Target block Z", true)
                        .number("y", "Target feet Y (optional; when given, you must end within 1 block of it)", false)
                        .number("tolerance", "How close to the target block's center, in blocks (default 1.0, min 0.5)", false)
                        .bool("dig", "Allow mining through blocks in the way (default false)", false)
                        .bool("build", "Allow placing blocks to climb up and cross gaps (default false)", false)
                        .bool("sprint", "Sprint while walking (default false)", false)
                        .bool("continue_when_hurt", "Keep going when taking damage (default false)", false)
                        .integer("timeout_seconds", "Give up after this long, 1-120 (default 60)", false)
                        .build(),
                false, ActionTools::moveTo));

        out.add(new Tool("collect_items",
                "Walk to dropped items nearby (nearest first) to pick them up, using pathfinding. "
                        + "Reports what ended up in your inventory.",
                Schema.of()
                        .integer("radius", "Search radius around you, 1-32 (default 16)", false)
                        .string("item", "Only collect this item id (optional)", false)
                        .integer("timeout_seconds", "Give up after this long, 1-120 (default 60)", false)
                        .build(),
                false, ActionTools::collect));

        out.add(new Tool("walk",
                "Hold movement keys for a number of ticks (20 ticks = 1 second). Use for small adjustments, jumping, "
                        + "sneaking to the edge of a block, or swimming up.",
                Schema.of()
                        .string("direction", "forward, back, left, right, or none (default forward)", false)
                        .integer("ticks", "How long, 1-200 (default 10)", false)
                        .bool("jump", "Hold jump", false).bool("sprint", "Hold sprint", false)
                        .bool("sneak", "Hold sneak (prevents falling off edges)", false)
                        .build(),
                false, ActionTools::walk));

        out.add(new Tool("mine_block",
                "Break the block at x,y,z (must be within reach). Waits until it breaks. By default switches to the "
                        + "best tool in your inventory first.",
                Schema.of().xyz("the block to break")
                        .bool("auto_tool", "Switch to the best tool first (default true)", false)
                        .build(),
                false, ActionTools::mine));

        out.add(new Tool("place_block",
                "Place a block at x,y,z (an empty or replaceable spot within reach, next to an existing solid block). "
                        + "Uses the held item unless 'item' is given.",
                Schema.of().xyz("where the new block goes")
                        .string("item", "Block item id to place, e.g. 'cobblestone' (optional)", false)
                        .build(),
                false, ActionTools::place));

        out.add(new Tool("use_item",
                "Right-click with the held item in the air: eat/drink (ticks=40), draw and release a bow (ticks=20), "
                        + "throw, equip armor, etc.",
                Schema.of()
                        .string("item", "Item to equip first (optional)", false)
                        .string("hand", "main or off (default main)", false)
                        .integer("ticks", "Hold right-click for this many ticks, 0-200 (default 0 = single click)", false)
                        .build(),
                false, ActionTools::useItem));

        out.add(new Tool("interact_block",
                "Right-click a block: open chests/furnaces/crafting tables, press buttons, flip levers, open doors, sleep in beds. "
                        + "Uses the held item on the block if it has a use (e.g. bucket, flint and steel, hoe).",
                Schema.of().xyz("the block")
                        .bool("sneak", "Sneak while clicking (places items against chests instead of opening them)", false)
                        .build(),
                false, ActionTools::interactBlock));

        out.add(new Tool("attack_entity",
                "Hit an entity (id from get_nearby_entities) once with the held item. Must be within reach (~3 blocks). "
                        + "Hits are weak until the attack cooldown refills (about 0.6s with a sword).",
                Schema.of().integer("entity_id", "Entity id", true).build(),
                false, ActionTools::attack));

        out.add(new Tool("fight",
                "Fight until the target dies: chases it (pathfinding), picks your best weapon, attacks only at full "
                        + "strength, and raises a shield in your offhand between hits. Stops if your health drops to flee_health.",
                Schema.of()
                        .string("target", "Entity id, or a mob type like 'zombie' to fight the nearest one within 16 blocks", true)
                        .number("flee_health", "Give up at or below this health (default 6)", false)
                        .integer("timeout_seconds", "Give up after this long, 1-120 (default 30)", false)
                        .build(),
                false, ActionTools::fight));

        out.add(new Tool("set_behavior",
                "Switch automatic behaviors on or off. auto_defend: when a hostile mob hurts you, fight it, then continue "
                        + "what you were doing. auto_eat: eat the best safe food when hungry, or when hurt and not "
                        + "regenerating. Both are off by default. Returns the current settings.",
                Schema.of()
                        .bool("auto_defend", "Fight back automatically", false)
                        .bool("auto_eat", "Eat automatically", false)
                        .number("flee_health", "auto_defend gives up at or below this health (default 6)", false)
                        .build(),
                false, false, (agent, a) -> {
            Behaviors b = agent.behaviors();
            if (a.has("auto_defend")) b.autoDefend = a.bool("auto_defend", false);
            if (a.has("auto_eat")) b.autoEat = a.bool("auto_eat", false);
            if (a.has("flee_health")) b.fleeHealth = (float) Mth.clamp(a.num("flee_health"), 0, 20);
            return ToolResult.text("auto_defend=" + b.autoDefend + ", auto_eat=" + b.autoEat + ", flee_health=" + b.fleeHealth);
        }));

        out.add(new Tool("interact_entity",
                "Right-click an entity: trade with villagers, feed/breed animals, shear sheep, mount boats/horses.",
                Schema.of().integer("entity_id", "Entity id", true)
                        .string("hand", "main or off (default main)", false)
                        .build(),
                false, ActionTools::interactEntity));

        out.add(new Tool("send_chat",
                "Send a chat message to everyone on the server (it also shows on the server's Discord). "
                        + "Commands starting with '/' only work if the server owner allowed them for you.",
                Schema.of().string("message", "Text to send (max 256 chars)", true).build(),
                false, ActionTools::chat));

        out.add(new Tool("respawn", "Respawn after dying.", Schema.of().build(), false, (agent, a) -> Game.call(() -> {
            AgentPlayer p = Game.player(agent);
            if (!p.isDeadOrDying()) throw new ToolException("You are not dead.");
            agent.body().respawn();
            AgentPlayer now = Game.player(agent);
            return ToolResult.text("Respawned in " + now.level().dimension().location() + ". " + Game.where(now)
                    + " Your items stayed where you died (look for your grave/corpse; list_buttons shows its buttons when you open it).");
        })));

        out.add(new Tool("stop", "Cancel the running action and release all keys.", Schema.of().build(), false,
                (agent, a) -> Game.call(() -> {
                    boolean had = agent.runner().cancel("Stopped by request.");
                    if (agent.player() != null) {
                        agent.body().releaseKeys();
                        agent.body().releaseUsingItem();
                    }
                    return ToolResult.text(had ? "Stopped." : "Nothing was running.");
                })));
    }

    // ---------------------------------------------------------------- look

    private static ToolResult look(Agent agent, Args a) throws Exception {
        return Game.call(() -> {
            AgentPlayer p = Game.player(agent);
            if (a.has("x") || a.has("y") || a.has("z")) {
                agent.body().lookAt(new Vec3(Math.floor(a.num("x")) + 0.5, Math.floor(a.num("y")) + 0.5, Math.floor(a.num("z")) + 0.5));
            } else if (a.has("yaw") || a.has("pitch")) {
                agent.body().setRotation((float) a.num("yaw", p.getYRot()), (float) a.num("pitch", p.getXRot()));
            } else {
                throw new ToolException("Give either x,y,z or yaw/pitch.");
            }
            return ToolResult.text("Now yaw " + Game.round(Mth.wrapDegrees(p.getYRot())) + ", pitch " + Game.round(p.getXRot())
                    + ". Crosshair: " + InfoTools.describeHit(p.level(), InfoTools.crosshair(p)));
        });
    }

    // ---------------------------------------------------------------- move_to

    private static ToolResult moveTo(Agent agent, Args a) throws Exception {
        Integer y = a.has("y") ? (int) Math.floor(a.num("y")) : null;
        Pathfinder.Goal goal = new Pathfinder.Goal(Math.floor(a.num("x")) + 0.5, y, Math.floor(a.num("z")) + 0.5,
                Math.max(0.5, a.num("tolerance", 1.0)));
        Navigator nav = new Navigator(goal, a.bool("sprint", false),
                new Pathfinder.Options(a.bool("dig", false), a.bool("build", false)));
        boolean hurtOk = a.bool("continue_when_hurt", false);
        int timeout = Mth.clamp(a.integer("timeout_seconds", 60), 1, 120);
        TickAction action = new TickAction() {
            int ticks;

            @Override
            public String name() {
                return "move_to " + (int) Math.floor(goal.x()) + "," + (y == null ? "" : y + ",") + (int) Math.floor(goal.z());
            }

            @Override
            public boolean interruptOnDamage() {
                return !hurtOk;
            }

            @Override
            public void pause(Agent ag) {
                nav.pause(ag);
            }

            @Override
            public void stop(Agent ag) {
                nav.stop(ag);
            }

            @Override
            public String tick(Agent ag, AgentPlayer p) throws ToolException {
                if (++ticks > timeout * 20) throw new ToolException("Timed out. " + Game.where(p) + nav.workSummary());
                return switch (nav.tick(ag, p)) {
                    case ARRIVED -> "Arrived. " + Game.where(p) + nav.workSummary();
                    case FAILED -> throw new ToolException(nav.failure() + " " + Game.where(p) + " "
                            + Game.round(goal.hdist(p.getX(), p.getZ())) + " blocks from the target." + nav.workSummary());
                    case RUNNING -> null;
                };
            }
        };
        return ToolResult.text(agent.runner().run(action, timeout));
    }

    // ---------------------------------------------------------------- collect_items

    private static ToolResult collect(Agent agent, Args a) throws Exception {
        int radius = Mth.clamp(a.integer("radius", 16), 1, 32);
        Item filter = a.has("item") ? Game.resolveItem(a.str("item")) : null;
        int timeout = Mth.clamp(a.integer("timeout_seconds", 60), 1, 120);
        return ToolResult.text(agent.runner().run(new Collect(radius, filter, timeout * 20), timeout));
    }

    static final class Collect implements TickAction {
        private final int radius, maxTicks;
        private final Item filter;
        private final Set<Integer> skipped = new HashSet<>();
        private Map<Item, Integer> before;
        private Vec3 origin; // the search area stays centered where collecting started
        private ItemEntity target;
        private Navigator nav;
        private int ticks, waitTicks;

        Collect(int radius, Item filter, int maxTicks) {
            this(radius, filter, maxTicks, null);
        }

        /** @param origin center of the search area, or null for where the player is when collecting starts */
        Collect(int radius, Item filter, int maxTicks, Vec3 origin) {
            this.radius = radius;
            this.filter = filter;
            this.maxTicks = maxTicks;
            this.origin = origin;
        }

        @Override
        public String name() {
            return "collect_items";
        }

        @Override
        public void pause(Agent a) {
            if (nav != null) nav.pause(a);
            a.body().releaseKeys();
        }

        @Override
        public void stop(Agent a) {
            if (nav != null) nav.stop(a);
            a.body().releaseKeys();
        }

        @Override
        public String tick(Agent a, AgentPlayer p) {
            Body body = a.body();
            if (before == null) {
                before = countItems(p);
                if (origin == null) origin = p.position();
            }
            if (++ticks > maxTicks) return summary(p, "Timed out.");
            if (target != null && (!target.isAlive() || target.position().distanceTo(navTarget()) > 1.5)) {
                target = null; // picked up, despawned, merged or moved: choose again
            }
            if (target == null) {
                body.releaseKeys();
                target = nearestItem(p);
                if (target == null) return summary(p, skipped.isEmpty() ? "No more items nearby." : "Done; some items were unreachable.");
                // Path to near the item, then walk straight at it: items often lie where you cannot stand
                // (e.g. a tree trunk surrounded by leaves) but are still within pickup range from the edge.
                nav = new Navigator(new Pathfinder.Goal(target.getX(), (int) Math.floor(target.getY()), target.getZ(), 1.5),
                        false, Pathfinder.Options.WALK);
                waitTicks = 0;
            }
            if (waitTicks > 0 || nav.atGoal(p)) {
                double dx = target.getX() - p.getX(), dz = target.getZ() - p.getZ();
                body.setRotation((float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f, 0f);
                body.forward = dx * dx + dz * dz > 0.3 * 0.3;
                if (++waitTicks > 30) skip(); // still not picked up: unreachable, inventory full, or pickup delay
                return null;
            }
            switch (nav.tick(a, p)) {
                case ARRIVED -> waitTicks = 1; // finish the approach on foot next tick
                case FAILED -> skip();
                case RUNNING -> {
                }
            }
            return null;
        }

        private Vec3 navTarget() {
            return new Vec3(nav.goal().x(), target.getY(), nav.goal().z());
        }

        private void skip() {
            skipped.add(target.getId());
            target = null;
        }

        private ItemEntity nearestItem(Player p) {
            ItemEntity best = null;
            for (ItemEntity e : p.level().getEntitiesOfClass(ItemEntity.class, new AABB(origin, origin).inflate(radius))) {
                if (!e.isAlive() || skipped.contains(e.getId()) || e.position().distanceTo(origin) > radius) continue;
                if (filter != null && !e.getItem().is(filter)) continue;
                if (best == null || e.distanceToSqr(p) < best.distanceToSqr(p)) best = e;
            }
            return best;
        }

        static Map<Item, Integer> countItems(Player p) {
            Map<Item, Integer> m = new HashMap<>();
            for (ItemStack s : p.getInventory().items) if (!s.isEmpty()) m.merge(s.getItem(), s.getCount(), Integer::sum);
            return m;
        }

        static String gained(Map<Item, Integer> before, Player p) {
            Map<Item, Integer> after = countItems(p);
            StringBuilder sb = new StringBuilder();
            after.forEach((item, n) -> {
                int d = n - before.getOrDefault(item, 0);
                if (d > 0) sb.append(sb.isEmpty() ? "" : ", ").append(d).append(' ').append(Game.id(item));
            });
            return sb.isEmpty() ? "nothing" : sb.toString();
        }

        private String summary(Player p, String reason) {
            return reason + " Picked up: " + gained(before, p) + ". " + Game.where(p);
        }
    }

    // ---------------------------------------------------------------- walk

    private static ToolResult walk(Agent agent, Args a) throws Exception {
        String dir = a.str("direction", "forward").toLowerCase(Locale.ROOT);
        if (!List.of("forward", "back", "left", "right", "none").contains(dir)) {
            throw new ToolException("direction must be forward, back, left, right or none.");
        }
        int ticks = Mth.clamp(a.integer("ticks", 10), 1, 200);
        boolean jump = a.bool("jump", false), sprint = a.bool("sprint", false), sneak = a.bool("sneak", false);
        TickAction action = new TickAction() {
            int n;

            @Override
            public String name() {
                return "walk " + dir;
            }

            @Override
            public String tick(Agent ag, AgentPlayer p) {
                if (n++ >= ticks) return "Done. " + Game.where(p);
                Body b = ag.body();
                b.forward = dir.equals("forward");
                b.back = dir.equals("back");
                b.left = dir.equals("left");
                b.right = dir.equals("right");
                b.jump = jump;
                b.sprint = sprint;
                b.sneak = sneak;
                return null;
            }
        };
        return ToolResult.text(agent.runner().run(action, ticks / 20 + 2));
    }

    // ---------------------------------------------------------------- mine_block

    private static ToolResult mine(Agent agent, Args a) throws Exception {
        BlockPos pos = new BlockPos(a.integer("x"), a.integer("y"), a.integer("z"));
        return ToolResult.text(agent.runner().run(new Mine(pos, a.bool("auto_tool", true)), 60));
    }

    private static final class Mine implements TickAction {
        private final BlockPos pos;
        private final boolean autoTool;
        private Digger digger;

        Mine(BlockPos pos, boolean autoTool) {
            this.pos = pos;
            this.autoTool = autoTool;
        }

        @Override
        public String name() {
            return "mine_block " + Game.pos(pos);
        }

        @Override
        public String tick(Agent a, AgentPlayer p) throws ToolException {
            if (digger == null) digger = new Digger(pos, autoTool);
            return switch (digger.tick(a, p)) {
                case DONE -> "Broke " + digger.blockId() + " at " + Game.pos(pos) + "."
                        + (digger.noDrop() ? " WARNING: it dropped nothing - it needs a better tool (e.g. a pickaxe of the right tier)."
                        : " Use collect_items to pick up the drop.");
                case FAILED -> throw new ToolException(digger.message());
                case RUNNING -> null;
            };
        }

        @Override
        public void pause(Agent a) {
            if (digger != null) digger.abort(a);
            digger = null; // start over after the interruption
            a.body().releaseKeys();
        }

        @Override
        public void stop(Agent a) {
            pause(a);
        }
    }

    // ---------------------------------------------------------------- place_block

    private static ToolResult place(Agent agent, Args a) throws Exception {
        BlockPos target = new BlockPos(a.integer("x"), a.integer("y"), a.integer("z"));
        String item = a.str("item", null);
        TickAction action = new TickAction() {
            int wait = -1;
            String placing;

            @Override
            public String name() {
                return "place_block " + Game.pos(target);
            }

            @Override
            public String tick(Agent ag, AgentPlayer p) throws ToolException {
                Level level = p.level();
                if (wait >= 0) {
                    BlockState now = level.getBlockState(target);
                    if (!now.canBeReplaced()) {
                        String sign = ag.signPos() != null ? " A sign editor opened: write on it with edit_sign." : "";
                        return "Placed " + Game.id(now.getBlock()) + " at " + Game.pos(target) + "." + sign;
                    }
                    if (++wait > 6) throw new ToolException("The block did not appear (something in the way, e.g. you or a mob, "
                            + "or the area is protected).");
                    return null;
                }
                if (!level.hasChunkAt(target)) throw new ToolException(Game.pos(target) + " is far away.");
                BlockState current = level.getBlockState(target);
                if (!current.canBeReplaced()) {
                    throw new ToolException(Game.pos(target) + " is occupied by " + Game.id(current.getBlock()) + ".");
                }
                if (item != null) ag.body().equipItem(item);
                if (p.getMainHandItem().isEmpty()) throw new ToolException("Your hand is empty; pass 'item'.");
                placing = Game.describe(p.getMainHandItem());

                BlockHitResult best = null;
                double bestScore = Double.MAX_VALUE;
                for (Direction d : Direction.values()) {
                    BlockPos support = target.relative(d);
                    BlockState s = level.getBlockState(support);
                    if (s.canBeReplaced() || !p.canInteractWithBlock(support, 0)) continue;
                    Direction face = d.getOpposite();
                    Vec3 hit = Vec3.atCenterOf(support).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5));
                    double score = p.getEyePosition().distanceTo(hit);
                    // Right-clicking a chest, crafting table etc. would open it instead of placing.
                    if (s.hasBlockEntity() || s.getMenuProvider(level, support) != null) score += 100;
                    if (score < bestScore) {
                        bestScore = score;
                        best = new BlockHitResult(hit, face, support, false);
                    }
                }
                if (best == null) {
                    throw new ToolException("No solid block next to " + Game.pos(target) + " within reach to place against.");
                }
                ag.body().lookAt(best.getLocation());
                ag.clearSign();
                ag.body().useItemOn(InteractionHand.MAIN_HAND, best);
                wait = 0;
                return null;
            }
        };
        return ToolResult.text(agent.runner().run(action, 5));
    }

    // ---------------------------------------------------------------- use_item

    private static ToolResult useItem(Agent agent, Args a) throws Exception {
        String item = a.str("item", null);
        InteractionHand hand = a.str("hand", "main").toLowerCase(Locale.ROOT).startsWith("off")
                ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        int ticks = Mth.clamp(a.integer("ticks", 0), 0, 200);
        TickAction action = new TickAction() {
            int held = -1;
            String used;

            @Override
            public String name() {
                return "use_item";
            }

            @Override
            public String tick(Agent ag, AgentPlayer p) throws ToolException {
                if (held < 0) {
                    if (item != null) ag.body().equipItem(item);
                    ItemStack stack = p.getItemInHand(hand);
                    if (stack.isEmpty()) throw new ToolException("Nothing in that hand.");
                    used = Game.describe(stack);
                    ItemStack before = stack.copy();
                    ag.body().useItem(hand);
                    if (ticks == 0 || !p.isUsingItem()) {
                        ag.body().releaseUsingItem();
                        ItemStack now = p.getItemInHand(hand);
                        String change = ItemStack.matches(before, now) ? "" : " Your hand now holds " + Game.describe(now) + ".";
                        return "Used " + used + "." + change + (ticks > 0 ? " It is not a hold-to-use item." : "");
                    }
                    held = 0;
                }
                if (!p.isUsingItem()) return "Finished using " + used + " after " + held + " ticks.";
                if (held++ >= ticks) {
                    ag.body().releaseUsingItem();
                    return "Released " + used + " after " + ticks + " ticks.";
                }
                return null; // the server keeps using the item until it is released or done
            }

            @Override
            public void stop(Agent ag) {
                if (ag.player() != null) ag.body().releaseUsingItem();
                ag.body().releaseKeys();
            }
        };
        return ToolResult.text(agent.runner().run(action, ticks / 20 + 3));
    }

    // ---------------------------------------------------------------- interact_block

    private static ToolResult interactBlock(Agent agent, Args a) throws Exception {
        BlockPos pos = new BlockPos(a.integer("x"), a.integer("y"), a.integer("z"));
        boolean sneak = a.bool("sneak", false);
        TickAction action = new TickAction() {
            int wait = -1;
            String result;

            @Override
            public String name() {
                return "interact_block " + Game.pos(pos);
            }

            @Override
            public String tick(Agent ag, AgentPlayer p) throws ToolException {
                if (wait < 0) {
                    if (!p.level().hasChunkAt(pos)) throw new ToolException(Game.pos(pos) + " is far away.");
                    BlockState s = p.level().getBlockState(pos);
                    if (s.isAir()) throw new ToolException("There is no block at " + Game.pos(pos) + ".");
                    if (!p.canInteractWithBlock(pos, 0)) throw new ToolException("Out of reach. Move closer first.");
                    if (sneak) {
                        ag.body().sneak = true;
                        p.setShiftKeyDown(true); // the server reads sneaking from the entity right away
                    }
                    Direction face = Game.faceToward(pos, p.getEyePosition());
                    Vec3 hit = Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5));
                    ag.body().lookAt(hit);
                    ag.clearSign();
                    ag.body().useItemOn(InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false));
                    result = "Right-clicked " + Game.id(s.getBlock()) + ".";
                    wait = 0;
                }
                if (++wait < 3) return null; // let block entities and menus settle
                String title = ag.screenTitle();
                String screen = title != null ? title + " (" + ag.screenType() + "): use get_open_container"
                        : ag.signPos() != null ? "a sign editor (use edit_sign)" : "none";
                String sleeping = p.isSleeping() ? " You are in bed." : "";
                String bar = ag.actionBar().isEmpty() ? "" : " Message: " + ag.actionBar();
                return result + " Block is now " + Game.id(p.level().getBlockState(pos).getBlock())
                        + ". Open screen: " + screen + "." + sleeping + bar;
            }

            @Override
            public void stop(Agent ag) {
                ag.body().releaseKeys();
            }
        };
        return ToolResult.text(agent.runner().run(action, 3));
    }

    // ---------------------------------------------------------------- entities

    static Entity entityInReach(Agent agent, AgentPlayer p, int id) throws ToolException {
        Entity e = p.serverLevel().getEntity(id);
        if (e == null || !e.isAlive()) throw new ToolException("No living entity with id " + id + " nearby.");
        if (!p.canInteractWithEntity(e, 0)) {
            throw new ToolException("Entity is " + Game.round(e.distanceTo(p)) + " blocks away; reach is "
                    + Game.round(p.entityInteractionRange()) + ". Move closer first.");
        }
        agent.body().lookAt(e.getBoundingBox().getCenter());
        return e;
    }

    private static ToolResult attack(Agent agent, Args a) throws Exception {
        int id = a.integer("entity_id");
        return Game.call(() -> {
            AgentPlayer p = Game.player(agent);
            Entity e = entityInReach(agent, p, id);
            float strength = p.getAttackStrengthScale(0.5f);
            String health = e instanceof LivingEntity l ? " Its health before the hit: " + Game.round(l.getHealth()) + "." : "";
            agent.body().attack(e);
            agent.body().swing(InteractionHand.MAIN_HAND);
            String after = e instanceof LivingEntity l ? " After: " + Game.round(l.getHealth()) + "." : "";
            return ToolResult.text("Attacked " + Game.id(e.getType()) + " with " + Game.describe(p.getMainHandItem())
                    + " at " + Math.round(strength * 100) + "% strength." + health + after);
        });
    }

    private static ToolResult fight(Agent agent, Args a) throws Exception {
        String target = a.str("target").trim();
        float flee = (float) a.num("flee_health", agent.behaviors().fleeHealth);
        int timeout = Mth.clamp(a.integer("timeout_seconds", 30), 1, 120);
        Entity e = Game.call(() -> {
            AgentPlayer p = Game.player(agent);
            if (target.matches("\\d+")) {
                Entity found = p.serverLevel().getEntity(Integer.parseInt(target));
                if (found == null || !found.isAlive()) throw new ToolException("No living entity with id " + target + " nearby.");
                return found;
            }
            String type = target.contains(":") ? target : "minecraft:" + target;
            return p.level().getEntities(p, p.getBoundingBox().inflate(16),
                            x -> x.isAlive() && Game.id(x.getType()).equals(type) && x.distanceTo(p) <= 16).stream()
                    .min(java.util.Comparator.comparingDouble(x -> x.distanceToSqr(p)))
                    .orElseThrow(() -> new ToolException("No " + type + " within 16 blocks."));
        });
        return ToolResult.text(agent.runner().run(new Combat.Fight(e, timeout * 20, flee), timeout));
    }

    private static ToolResult interactEntity(Agent agent, Args a) throws Exception {
        int id = a.integer("entity_id");
        InteractionHand hand = a.str("hand", "main").toLowerCase(Locale.ROOT).startsWith("off")
                ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        return ToolResult.text(agent.runner().run(new TickAction() {
            int wait = -1;
            String what;
            ItemStack before;

            @Override
            public String name() {
                return "interact_entity " + id;
            }

            @Override
            public String tick(Agent ag, AgentPlayer p) throws ToolException {
                if (wait < 0) {
                    Entity e = entityInReach(ag, p, id);
                    what = Game.id(e.getType());
                    before = p.getItemInHand(hand).copy();
                    ag.body().interact(e, hand);
                    wait = 0;
                }
                if (++wait < 3) return null;
                StringBuilder sb = new StringBuilder("Interacted with " + what + ".");
                ItemStack now = p.getItemInHand(hand);
                if (!ItemStack.matches(before, now)) sb.append(" Your hand now holds ").append(Game.describe(now)).append('.');
                if (p.isPassenger()) sb.append(" You are riding ").append(Game.id(p.getVehicle().getType())).append('.');
                String title = ag.screenTitle();
                if (title != null) sb.append(" Open screen: ").append(title).append(" (use get_open_container).");
                return sb.toString();
            }
        }, 3));
    }

    // ---------------------------------------------------------------- chat

    private static ToolResult chat(Agent agent, Args a) throws Exception {
        String msg = StringUtil.filterText(a.str("message")).trim();
        if (msg.isEmpty()) throw new ToolException("Message is empty.");
        if (msg.length() > 256) msg = msg.substring(0, 256);
        String m = msg;
        return Game.call(() -> {
            Game.player(agent);
            if (m.startsWith("/")) {
                if (!agent.entry().allow_commands) {
                    throw new ToolException("Commands are turned off for you. Plain chat works.");
                }
                agent.body().command(m.substring(1));
                return ToolResult.text("Ran the command. Its output (if any) shows up in get_chat.");
            }
            agent.body().chat(m);
            return ToolResult.text("Sent.");
        });
    }
}
