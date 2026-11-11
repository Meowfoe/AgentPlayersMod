package dev.maddie.agents.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.maddie.agents.agent.Agent;
import dev.maddie.agents.agent.AgentPlayer;
import dev.maddie.agents.game.ChatLog;
import dev.maddie.agents.game.Game;
import dev.maddie.agents.game.Pathfinder;
import dev.maddie.agents.mcp.Args;
import dev.maddie.agents.mcp.Tool;
import dev.maddie.agents.mcp.Tool.Schema;
import dev.maddie.agents.mcp.ToolResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Read-only tools: let the AI observe the world. */
final class InfoTools {
    private InfoTools() {
    }

    static void register(List<Tool> out) {
        out.add(new Tool("get_status",
                "Player state: position, rotation, health, food, dimension, biome, time, weather, held item, "
                        + "what you are looking at, open screen and the running action.",
                Schema.of().build(), true, (agent, a) -> Game.call(() -> status(agent))));

        out.add(new Tool("get_inventory",
                "List inventory contents. Slots 0-8 are the hotbar, 9-35 the main inventory; also armor and offhand.",
                Schema.of().build(), true, (agent, a) -> Game.call(() -> inventory(Game.player(agent)))));

        out.add(new Tool("get_block", "Describe the block at a position (id, block-state properties, hardness).",
                Schema.of().xyz("the block").build(), true, (agent, a) -> Game.call(() -> block(Game.player(agent), a))));

        out.add(new Tool("get_view",
                "Describe in words what is in front of you, like a screenshot would show: the first block in "
                        + "each direction of a 5x3 grid of view rays (left to right, up to down), plus the "
                        + "creatures and players in your field of view.",
                Schema.of().integer("distance", "How far to look, 8-64 blocks (default 32)", false).build(),
                true, (agent, a) -> Game.call(() -> view(Game.player(agent), a))));

        out.add(new Tool("scan_area",
                "List visible (air-exposed) blocks around the player, grouped by block id, nearest first. "
                        + "Good for understanding the surroundings.",
                Schema.of().integer("radius", "Cube radius in blocks, 1-10 (default 6).", false).build(),
                true, (agent, a) -> Game.call(() -> scan(Game.player(agent), a))));

        out.add(new Tool("find_blocks",
                "Find the nearest blocks of the given type(s), e.g. 'oak_log' or 'iron_ore,deepslate_iron_ore'.",
                Schema.of()
                        .string("block", "Block id, or comma-separated ids. 'minecraft:' prefix optional.", true)
                        .integer("radius", "Search radius, 1-48 (default 32).", false)
                        .integer("limit", "Max results, 1-50 (default 10).", false)
                        .bool("exposed_only", "Only blocks touching air/water, i.e. visible somewhere (default true; "
                                + "seeing ores through walls is x-ray, which players cannot do)", false)
                        .build(),
                true, (agent, a) -> Game.call(() -> find(Game.player(agent), a))));

        out.add(new Tool("get_map",
                "Top-down text map around you, north at the top (x grows to the right, z grows downward). "
                        + "mode 'surface': terrain type plus a height grid relative to your feet. mode 'level': a slice "
                        + "at your feet height showing walls, floor, holes, water and lava (best in caves).",
                Schema.of()
                        .integer("radius", "Blocks in each direction, 4-48 (default 24)", false)
                        .string("mode", "surface or level (default surface)", false)
                        .build(),
                true, (agent, a) -> Game.call(() -> map(Game.player(agent), a))));

        out.add(new Tool("get_nearby_entities",
                "List nearby mobs, players, animals and dropped items with their entity ids (for attack/interact), "
                        + "positions and health, nearest first.",
                Schema.of().integer("radius", "Radius in blocks, 1-64 (default 24).", false).build(),
                true, (agent, a) -> Game.call(() -> entities(Game.player(agent), a))));

        out.add(new Tool("get_chat",
                "Recent chat and system messages you received. Pass the last id you saw as 'since' to get only new ones. "
                        + "Works while you are offline too (it shows what you saw while online).",
                Schema.of()
                        .integer("since", "Only return messages with id greater than this (default 0).", false)
                        .integer("limit", "Max messages, 1-100 (default 30).", false)
                        .build(),
                true, false, InfoTools::chat));
    }

    private static JsonArray arr(double... values) {
        JsonArray a = new JsonArray();
        for (double v : values) a.add(Game.round(v));
        return a;
    }

    /** What the crosshair would be on: the nearer of the block and entity within reach. */
    static HitResult crosshair(Player p) {
        double reach = p.blockInteractionRange();
        HitResult block = p.pick(reach, 1f, false);
        Vec3 eye = p.getEyePosition();
        double entityReach = p.entityInteractionRange();
        Vec3 look = p.getViewVector(1f);
        double maxDist = Math.max(reach, entityReach);
        if (block.getType() != HitResult.Type.MISS) maxDist = Math.min(maxDist, block.getLocation().distanceTo(eye));
        Vec3 end = eye.add(look.scale(maxDist));
        EntityHitResult ent = ProjectileUtil.getEntityHitResult(p, eye, end,
                p.getBoundingBox().expandTowards(look.scale(maxDist)).inflate(1), e -> !e.isSpectator() && e.isPickable(),
                maxDist * maxDist);
        if (ent != null && ent.getLocation().distanceTo(eye) <= entityReach) return ent;
        return block;
    }

    static String describeHit(Level level, HitResult hr) {
        if (hr instanceof BlockHitResult b && hr.getType() == HitResult.Type.BLOCK) {
            return "block " + Game.id(level.getBlockState(b.getBlockPos()).getBlock()) + " at " + Game.pos(b.getBlockPos())
                    + " (face " + b.getDirection().getName() + ")";
        }
        if (hr instanceof EntityHitResult e) {
            return "entity " + Game.id(e.getEntity().getType()) + " id=" + e.getEntity().getId();
        }
        return "nothing within reach";
    }

    private static ToolResult status(Agent agent) throws Exception {
        AgentPlayer p = Game.player(agent);
        Level level = p.level();
        BlockPos bp = p.blockPosition();
        JsonObject o = new JsonObject();
        o.addProperty("name", p.getGameProfile().getName());
        o.add("position", arr(p.getX(), p.getY(), p.getZ()));
        o.addProperty("block_position", Game.pos(bp));
        o.addProperty("yaw", Game.round(Mth.wrapDegrees(p.getYRot())));
        o.addProperty("pitch", Game.round(p.getXRot()));
        o.addProperty("facing", p.getDirection().getName());
        o.addProperty("health", Game.round(p.getHealth()) + "/" + Game.round(p.getMaxHealth()));
        o.addProperty("food", p.getFoodData().getFoodLevel() + "/20");
        o.addProperty("saturation", Game.round(p.getFoodData().getSaturationLevel()));
        o.addProperty("armor", p.getArmorValue());
        o.addProperty("air", p.getAirSupply() + "/" + p.getMaxAirSupply());
        o.addProperty("xp_level", p.experienceLevel);
        o.addProperty("dead", p.isDeadOrDying());
        o.addProperty("game_mode", p.gameMode.getGameModeForPlayer().getName());
        o.addProperty("dimension", level.dimension().location().toString());
        o.addProperty("biome", level.getBiome(bp).unwrapKey().map(k -> k.location().toString()).orElse("unknown"));
        long t = level.getDayTime() % 24000;
        o.addProperty("time_of_day", t + " (" + (t < 12300 || t > 23850 ? "day" : "night") + ")");
        o.addProperty("weather", level.isThundering() ? "thunder" : level.isRaining() ? "rain" : "clear");
        o.addProperty("light_level", level.getMaxLocalRawBrightness(bp));
        o.addProperty("on_ground", p.onGround());
        o.addProperty("in_water", p.isInWater());
        if (p.isPassenger()) o.addProperty("riding", Game.id(p.getVehicle().getType()) + " id=" + p.getVehicle().getId());
        o.addProperty("selected_hotbar_slot", p.getInventory().selected);
        o.addProperty("main_hand", Game.describe(p.getMainHandItem()));
        o.addProperty("off_hand", Game.describe(p.getOffhandItem()));
        o.addProperty("looking_at", describeHit(level, crosshair(p)));
        String title = agent.screenTitle();
        o.addProperty("open_screen", title != null ? agent.screenType() + " \"" + title + "\""
                : agent.signPos() != null ? "sign editor at " + Game.pos(agent.signPos()) + " (use edit_sign)" : "none");
        if (!agent.actionBar().isEmpty()) o.addProperty("action_bar", agent.actionBar());
        String action = agent.runner().currentName();
        o.addProperty("current_action", action == null ? "none" : action);
        return ToolResult.json(o);
    }

    static ToolResult inventory(Player p) {
        Inventory inv = p.getInventory();
        JsonObject o = new JsonObject();
        JsonArray items = new JsonArray();
        for (int i = 0; i < inv.items.size(); i++) {
            if (!inv.items.get(i).isEmpty()) items.add(i + ": " + Game.describe(inv.items.get(i)));
        }
        o.addProperty("selected_hotbar_slot", inv.selected);
        o.add("slots", items);
        String[] armorNames = {"feet", "legs", "chest", "head"};
        JsonObject armor = new JsonObject();
        for (int i = 0; i < inv.armor.size(); i++) armor.addProperty(armorNames[i], Game.describe(inv.armor.get(i)));
        o.add("armor", armor);
        o.addProperty("offhand", Game.describe(inv.offhand.get(0)));
        if (!p.containerMenu.getCarried().isEmpty()) {
            o.addProperty("held_by_cursor", Game.describe(p.containerMenu.getCarried()));
        }
        return ToolResult.json(o);
    }

    private static ToolResult block(Player p, Args a) throws Exception {
        Level level = p.level();
        BlockPos pos = new BlockPos(a.integer("x"), a.integer("y"), a.integer("z"));
        JsonObject o = new JsonObject();
        o.addProperty("position", Game.pos(pos));
        if (!level.hasChunkAt(pos)) {
            o.addProperty("id", "unknown");
            o.addProperty("note", "too far away: that area is not loaded around you");
            return ToolResult.json(o);
        }
        BlockState s = level.getBlockState(pos);
        o.addProperty("id", Game.id(s.getBlock()));
        JsonObject props = new JsonObject();
        s.getValues().forEach((prop, value) -> props.addProperty(prop.getName(), value.toString()));
        if (!props.isEmpty()) o.add("properties", props);
        o.addProperty("hardness", s.getDestroySpeed(level, pos));
        o.addProperty("needs_correct_tool", s.requiresCorrectToolForDrops());
        return ToolResult.json(o);
    }

    // ---------------------------------------------------------------- get_view

    private static ToolResult view(Player p, Args a) throws Exception {
        Level level = p.level();
        int dist = Mth.clamp(a.integer("distance", 32), 8, 64);
        Vec3 eye = p.getEyePosition();
        String[] cols = {"far left", "left", "ahead", "right", "far right"};
        float[] yawOff = {-50, -25, 0, 25, 50};
        String[] rows = {"up", "level", "down"};
        float[] pitchOff = {-30, 0, 30};
        StringBuilder sb = new StringBuilder();
        sb.append("Looking ").append(p.getDirection().getName()).append(" (yaw ").append(Game.round(Mth.wrapDegrees(p.getYRot())))
                .append(", pitch ").append(Game.round(p.getXRot())).append(") from ").append(Game.pos(p.blockPosition()))
                .append(". Crosshair: ").append(describeHit(level, crosshair(p))).append(".\n");
        for (int r = 0; r < rows.length; r++) {
            sb.append(rows[r]).append(":\n");
            for (int c = 0; c < cols.length; c++) {
                float pitch = Mth.clamp(p.getXRot() + pitchOff[r], -90, 90);
                Vec3 dir = Vec3.directionFromRotation(pitch, p.getYRot() + yawOff[c]);
                Vec3 end = eye.add(dir.scale(dist));
                BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, p));
                sb.append("  ").append(cols[c]).append(": ");
                if (hit.getType() == HitResult.Type.MISS) {
                    boolean sky = pitch < -5 && level.canSeeSky(BlockPos.containing(end));
                    sb.append(sky ? "open sky" : "nothing within " + dist + " blocks");
                } else {
                    BlockPos bp = hit.getBlockPos();
                    BlockState s = level.getBlockState(bp);
                    String what = !s.getFluidState().isEmpty() && s.getCollisionShape(level, bp).isEmpty()
                            ? (s.getFluidState().is(FluidTags.LAVA) ? "lava" : "water") : Game.id(s.getBlock());
                    sb.append(what).append(" at ").append(Game.pos(bp)).append(", ")
                            .append(Game.round(hit.getLocation().distanceTo(eye))).append(" blocks");
                }
                sb.append('\n');
            }
        }
        // Creatures in the field of view (about 110 degrees wide), nearest first.
        Vec3 look = p.getViewVector(1f);
        List<Entity> seen = new ArrayList<>();
        for (Entity e : level.getEntities(p, p.getBoundingBox().inflate(dist), e -> e.isAlive() && !e.isInvisible())) {
            Vec3 to = e.getBoundingBox().getCenter().subtract(eye);
            if (to.length() > dist || to.normalize().dot(look) < 0.57) continue;
            if (Game.obstruction(p, BlockPos.containing(e.getBoundingBox().getCenter())) != null
                    && !p.hasLineOfSight(e)) continue;
            seen.add(e);
        }
        seen.sort(Comparator.comparingDouble(e -> e.distanceToSqr(p)));
        sb.append("In view: ");
        if (seen.isEmpty()) sb.append("no creatures or players.");
        for (int i = 0; i < Math.min(15, seen.size()); i++) {
            Entity e = seen.get(i);
            Vec3 to = e.position().subtract(p.position());
            float rel = Mth.wrapDegrees((float) (Mth.atan2(to.z, to.x) * Mth.RAD_TO_DEG) - 90f - p.getYRot());
            String side = Math.abs(rel) < 15 ? "ahead" : rel < 0 ? "to the left" : "to the right";
            sb.append(i == 0 ? "" : "; ").append(label(e)).append(" id=").append(e.getId()).append(' ')
                    .append(Game.round(e.distanceTo(p))).append(" blocks ").append(side);
        }
        return ToolResult.text(sb.toString());
    }

    private static String label(Entity e) {
        if (e instanceof Player pl) return "player " + pl.getGameProfile().getName();
        if (e instanceof ItemEntity it) return "dropped " + Game.describe(it.getItem());
        return Game.id(e.getType()) + (e.hasCustomName() ? " \"" + e.getCustomName().getString() + "\"" : "")
                + (e instanceof Enemy ? " (hostile)" : "");
    }

    // ---------------------------------------------------------------- scanning

    private static boolean exposed(Level level, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (!level.hasChunkAt(n) || !level.getBlockState(n).canOcclude()) return true;
        }
        return false;
    }

    private static ToolResult scan(Player p, Args a) throws Exception {
        Level level = p.level();
        int r = Mth.clamp(a.integer("radius", 6), 1, 10);
        BlockPos c = p.blockPosition();
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos bp : BlockPos.betweenClosed(c.offset(-r, -r, -r), c.offset(r, r, r))) {
            if (!level.hasChunkAt(bp)) continue;
            if (!level.getBlockState(bp).isAir() && exposed(level, bp)) found.add(bp.immutable());
        }
        found.sort(Comparator.comparingDouble(bp -> bp.distSqr(c)));
        int max = 800;
        Map<String, JsonArray> groups = new TreeMap<>();
        for (BlockPos bp : found.subList(0, Math.min(max, found.size()))) {
            groups.computeIfAbsent(Game.id(level.getBlockState(bp).getBlock()), k -> new JsonArray()).add(Game.pos(bp));
        }
        JsonObject o = new JsonObject();
        o.addProperty("player_block_position", Game.pos(c));
        o.addProperty("radius", r);
        JsonObject blocks = new JsonObject();
        groups.forEach(blocks::add);
        o.add("blocks", blocks);
        if (found.size() > max) o.addProperty("note", "truncated to the nearest " + max + " of " + found.size() + " blocks");
        return ToolResult.json(o);
    }

    /**
     * Section by section, skipping chunk sections whose palette cannot contain a target. Runs on the server
     * thread, so it must stay cheap: a 48-block radius is ~900k positions, most skipped this way.
     */
    private static ToolResult find(Player p, Args a) throws Exception {
        Level level = p.level();
        Set<Block> targets = new HashSet<>();
        for (String name : a.str("block").split(",")) {
            if (!name.isBlank()) targets.add(Game.resolveBlock(name));
        }
        int r = Mth.clamp(a.integer("radius", 32), 1, 48);
        int limit = Mth.clamp(a.integer("limit", 10), 1, 50);
        boolean exposedOnly = a.bool("exposed_only", true);
        BlockPos c = p.blockPosition();
        int minY = Math.max(level.getMinBuildHeight(), c.getY() - r);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, c.getY() + r);
        List<BlockPos> found = new ArrayList<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        outer:
        for (int cx = (c.getX() - r) >> 4; cx <= (c.getX() + r) >> 4; cx++) {
            for (int cz = (c.getZ() - r) >> 4; cz <= (c.getZ() + r) >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                LevelChunk chunk = level.getChunk(cx, cz);
                for (int sy = SectionPos.blockToSectionCoord(minY); sy <= SectionPos.blockToSectionCoord(maxY); sy++) {
                    int idx = chunk.getSectionIndexFromSectionY(sy);
                    if (idx < 0 || idx >= chunk.getSections().length) continue;
                    LevelChunkSection section = chunk.getSection(idx);
                    if (section.hasOnlyAir() || !section.maybeHas(s -> targets.contains(s.getBlock()))) continue;
                    for (int y = Math.max(minY, sy << 4); y <= Math.min(maxY, (sy << 4) + 15); y++) {
                        for (int x = Math.max(c.getX() - r, cx << 4); x <= Math.min(c.getX() + r, (cx << 4) + 15); x++) {
                            for (int z = Math.max(c.getZ() - r, cz << 4); z <= Math.min(c.getZ() + r, (cz << 4) + 15); z++) {
                                BlockState s = section.getBlockState(x & 15, y & 15, z & 15);
                                if (!targets.contains(s.getBlock())) continue;
                                m.set(x, y, z);
                                if (exposedOnly && !exposed(level, m)) continue;
                                found.add(m.immutable());
                                if (found.size() >= 20000) break outer;
                            }
                        }
                    }
                }
            }
        }
        found.sort(Comparator.comparingDouble(bp -> bp.distSqr(c)));
        JsonArray results = new JsonArray();
        for (BlockPos bp : found.subList(0, Math.min(limit, found.size()))) {
            results.add(Game.id(level.getBlockState(bp).getBlock()) + " at " + Game.pos(bp)
                    + " (distance " + Game.round(Math.sqrt(bp.distSqr(c))) + ")");
        }
        JsonObject o = new JsonObject();
        o.addProperty("player_block_position", Game.pos(c));
        o.addProperty("total_found", found.size());
        o.add("nearest", results);
        return ToolResult.json(o);
    }

    private static ToolResult entities(Player p, Args a) throws Exception {
        Level level = p.level();
        double r = Mth.clamp(a.integer("radius", 24), 1, 64);
        List<Entity> list = level.getEntities(p, new AABB(p.blockPosition()).inflate(r),
                e -> e.isAlive() && e.distanceTo(p) <= r && !(e instanceof Player pl && (pl.isSpectator() || pl.isInvisible())));
        list.sort(Comparator.comparingDouble(e -> e.distanceToSqr(p)));
        JsonArray out = new JsonArray();
        for (Entity e : list.subList(0, Math.min(40, list.size()))) {
            JsonObject o = new JsonObject();
            o.addProperty("id", e.getId());
            o.addProperty("type", Game.id(e.getType()));
            if (e instanceof Player || e.hasCustomName()) o.addProperty("name", e.getName().getString());
            if (e instanceof ItemEntity item) o.addProperty("item", Game.describe(item.getItem()));
            o.add("position", arr(e.getX(), e.getY(), e.getZ()));
            o.addProperty("distance", Game.round(e.distanceTo(p)));
            if (e instanceof LivingEntity living) {
                o.addProperty("health", Game.round(living.getHealth()) + "/" + Game.round(living.getMaxHealth()));
            }
            if (e instanceof Enemy) o.addProperty("hostile", true);
            out.add(o);
        }
        JsonObject o = new JsonObject();
        o.addProperty("count", list.size());
        o.add("entities", out);
        return ToolResult.json(o);
    }

    private static ToolResult chat(Agent agent, Args a) throws Exception {
        long since = a.integer("since", 0);
        int limit = Mth.clamp(a.integer("limit", 30), 1, 100);
        JsonArray out = new JsonArray();
        for (ChatLog.Entry e : agent.chat().since(since, limit)) out.add("[" + e.id() + "] " + e.text());
        JsonObject o = new JsonObject();
        o.add("messages", out);
        return ToolResult.json(o);
    }

    // ---------------------------------------------------------------- map

    private static char surfaceChar(BlockState s) {
        if (s.getFluidState().is(FluidTags.WATER)) return '~';
        if (s.getFluidState().is(FluidTags.LAVA)) return '!';
        if (s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS)) return 'T';
        if (s.is(BlockTags.SAND) || s.is(Blocks.GRAVEL)) return ':';
        if (s.is(BlockTags.SNOW) || s.is(BlockTags.ICE)) return '*';
        if (s.is(BlockTags.DIRT)) return '.';
        if (s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.BASE_STONE_NETHER)) return '#';
        if (s.hasBlockEntity() || s.getBlock() instanceof net.minecraft.world.level.block.CraftingTableBlock) return 'B';
        return '+';
    }

    private static ToolResult map(Player p, Args a) throws Exception {
        Level level = p.level();
        int r = Mth.clamp(a.integer("radius", 24), 4, 48);
        boolean slice = a.str("mode", "surface").toLowerCase(Locale.ROOT).startsWith("level");
        BlockPos c = p.blockPosition();
        StringBuilder terrain = new StringBuilder(), heights = new StringBuilder();
        for (int z = c.getZ() - r; z <= c.getZ() + r; z++) {
            for (int x = c.getX() - r; x <= c.getX() + r; x++) {
                if (x == c.getX() && z == c.getZ()) {
                    terrain.append('@');
                    heights.append('@');
                    continue;
                }
                BlockPos col = new BlockPos(x, c.getY(), z);
                if (!level.hasChunkAt(col)) {
                    terrain.append(' ');
                    heights.append(' ');
                    continue;
                }
                if (slice) {
                    terrain.append(sliceChar(level, col));
                } else {
                    int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
                    terrain.append(surfaceChar(level.getBlockState(new BlockPos(x, top, z))));
                    int dy = top + 1 - c.getY();
                    heights.append(dy == 0 ? '0' : dy > 9 ? '^' : dy < -9 ? 'v' : dy > 0 ? (char) ('0' + dy) : (char) ('a' + (-dy - 1)));
                }
            }
            terrain.append('\n');
            heights.append('\n');
        }
        String header = "Map centered on you (@) at " + Game.pos(c) + ". Top-left corner is x=" + (c.getX() - r) + ", z="
                + (c.getZ() - r) + "; one character per block, north at the top, x grows to the right, z downward.\n";
        if (slice) {
            return ToolResult.text(header + "Legend: . floor you can stand on, # wall, _ hole/drop, ~ water, ! lava, "
                    + "H ladder/vine, D door, ' ' unloaded.\n" + terrain);
        }
        return ToolResult.text(header + "Terrain: . dirt/grass, # stone, T tree, : sand/gravel, ~ water, ! lava, * snow/ice, "
                + "B crafted block (chest, furnace, table...), + other, ' ' unloaded.\n" + terrain
                + "\nGround height relative to your feet: 0 same, 1-9 higher, ^ more than 9 higher, a-i 1-9 lower "
                + "(a=-1 ... i=-9), v more than 9 lower.\n" + heights);
    }

    private static char sliceChar(Level level, BlockPos feet) {
        BlockState s = level.getBlockState(feet);
        if (s.getFluidState().is(FluidTags.LAVA) || level.getBlockState(feet.below()).getFluidState().is(FluidTags.LAVA)) return '!';
        if (s.getFluidState().is(FluidTags.WATER)) return '~';
        if (s.is(BlockTags.CLIMBABLE)) return 'H';
        if (s.is(BlockTags.DOORS) || s.is(BlockTags.FENCE_GATES)) return 'D';
        if (!Pathfinder.isOpen(level, feet)) return '#';
        if (!Pathfinder.isOpen(level, feet.above())) return '#'; // too low to walk through
        BlockState below = level.getBlockState(feet.below());
        return below.getCollisionShape(level, feet.below()).isEmpty() ? '_' : '.';
    }
}
