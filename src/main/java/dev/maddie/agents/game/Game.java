package dev.maddie.agents.game;

import dev.maddie.agents.agent.Agent;
import dev.maddie.agents.agent.AgentPlayer;
import dev.maddie.agents.mcp.ToolException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** Helpers for touching game state safely from the HTTP threads. */
public final class Game {
    private Game() {
    }

    public static MinecraftServer server() throws ToolException {
        MinecraftServer s = ServerLifecycleHooks.getCurrentServer();
        if (s == null || !s.isRunning()) throw new ToolException("The server is not running.");
        return s;
    }

    /** Runs {@code task} on the server thread and waits for its result. */
    public static <T> T call(Callable<T> task) throws Exception {
        MinecraftServer server = server();
        if (server.isSameThread()) return task.call();
        CompletableFuture<T> f = server.submit((Supplier<T>) () -> {
            try {
                return task.call();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
        try {
            return f.get(15, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw unwrap(e);
        } catch (TimeoutException e) {
            throw new ToolException("Timed out waiting for the server (it may be lagging badly).");
        }
    }

    static Exception unwrap(Throwable t) {
        while ((t instanceof ExecutionException || t instanceof CompletionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t instanceof Exception e ? e : new RuntimeException(t);
    }

    /** The agent's body; only call on the server thread. */
    public static AgentPlayer player(Agent a) throws ToolException {
        AgentPlayer p = a.player();
        if (p == null || !a.online()) throw new ToolException("You are not in the game right now. Call join first.");
        return p;
    }

    // ---- naming ----

    public static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    public static String id(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    public static String id(EntityType<?> type) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
    }

    public static String describe(ItemStack s) {
        if (s.isEmpty()) return "empty";
        StringBuilder sb = new StringBuilder(id(s.getItem())).append(" x").append(s.getCount());
        if (s.isDamageableItem()) {
            sb.append(" (durability ").append(s.getMaxDamage() - s.getDamageValue()).append('/').append(s.getMaxDamage()).append(')');
        }
        if (s.has(DataComponents.CUSTOM_NAME)) sb.append(" \"").append(s.getHoverName().getString()).append('"');
        return sb.toString();
    }

    public static String pos(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    public static double round(double d) {
        return Math.round(d * 100) / 100.0;
    }

    public static String where(Player p) {
        return "Now at " + round(p.getX()) + "," + round(p.getY()) + "," + round(p.getZ()) + ".";
    }

    private static ResourceLocation parseId(String name) throws ToolException {
        String n = name.trim().toLowerCase(Locale.ROOT);
        ResourceLocation rl = ResourceLocation.tryParse(n.contains(":") ? n : "minecraft:" + n);
        if (rl == null) throw new ToolException("Invalid id: " + name);
        return rl;
    }

    public static Item resolveItem(String name) throws ToolException {
        ResourceLocation rl = parseId(name);
        if (!BuiltInRegistries.ITEM.containsKey(rl)) throw new ToolException("Unknown item id: " + name);
        return BuiltInRegistries.ITEM.get(rl);
    }

    public static Block resolveBlock(String name) throws ToolException {
        ResourceLocation rl = parseId(name);
        if (!BuiltInRegistries.BLOCK.containsKey(rl)) throw new ToolException("Unknown block id: " + name);
        return BuiltInRegistries.BLOCK.get(rl);
    }

    /** Likely causes of damage: nearby hostile mobs and environmental hazards. */
    public static String dangerReport(Player p) {
        List<String> causes = new ArrayList<>();
        if (p.isOnFire()) causes.add("you are on fire");
        if (p.isInLava()) causes.add("you are in lava");
        if (p.getAirSupply() <= 0) causes.add("you are drowning");
        if (p.getFoodData().getFoodLevel() == 0) causes.add("you are starving");
        List<Entity> hostiles = p.level().getEntities(p, p.getBoundingBox().inflate(16),
                e -> e instanceof Enemy && e.isAlive() && e.distanceTo(p) <= 16);
        hostiles.sort(Comparator.comparingDouble(e -> e.distanceToSqr(p)));
        for (Entity e : hostiles.subList(0, Math.min(3, hostiles.size()))) {
            causes.add(id(e.getType()) + " id=" + e.getId() + " " + round(e.distanceTo(p)) + " blocks away");
        }
        if (p.getLastHurtByMob() instanceof Player attacker && attacker.isAlive()) {
            causes.add("player " + attacker.getGameProfile().getName() + " hit you");
        }
        return causes.isEmpty()
                ? "No hostile mob in sight (fall damage, a projectile from afar, or another player?)."
                : "Possible causes: " + String.join("; ", causes) + ".";
    }

    /** The first solid block between your eyes and {@code target}, or null if you can see it. */
    public static BlockPos obstruction(Player p, BlockPos target) {
        BlockHitResult hit = p.level().clip(new ClipContext(p.getEyePosition(), Vec3.atCenterOf(target),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target)) return null;
        return hit.getBlockPos();
    }

    public static Direction faceToward(BlockPos block, Vec3 from) {
        Vec3 c = Vec3.atCenterOf(block);
        return Direction.getNearest((float) (from.x - c.x), (float) (from.y - c.y), (float) (from.z - c.z));
    }

    // ---- inventory ----

    /** Inventory index (0-35; 0-8 is the hotbar) holding {@code item}, preferring the hotbar, or -1. */
    public static int findItem(Inventory inv, Item item) {
        for (int i = 0; i < 36; i++) if (inv.items.get(i).is(item)) return i;
        return -1;
    }

    /** Cheap, common, non-falling blocks used for pillaring and bridging, in order of preference. */
    private static final List<Item> SCAFFOLD = List.of(Items.DIRT, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE,
            Items.NETHERRACK, Items.STONE, Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.TUFF, Items.DEEPSLATE,
            Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS, Items.JUNGLE_PLANKS, Items.ACACIA_PLANKS,
            Items.DARK_OAK_PLANKS, Items.MANGROVE_PLANKS, Items.CHERRY_PLANKS, Items.SANDSTONE, Items.END_STONE);

    public static boolean isScaffold(ItemStack s) {
        return SCAFFOLD.contains(s.getItem());
    }

    public static int scaffoldCount(Player p) {
        int n = 0;
        for (ItemStack s : p.getInventory().items) if (isScaffold(s)) n += s.getCount();
        return n;
    }

    public static int findScaffold(Inventory inv) {
        for (Item item : SCAFFOLD) {
            int idx = findItem(inv, item);
            if (idx >= 0) return idx;
        }
        return -1;
    }

    public static int count(Player p, Item item) {
        int n = 0;
        for (ItemStack s : p.getInventory().items) if (s.is(item)) n += s.getCount();
        return n;
    }
}
