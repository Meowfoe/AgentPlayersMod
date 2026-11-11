package dev.maddie.agents.game;

import dev.maddie.agents.agent.Agent;
import dev.maddie.agents.agent.AgentPlayer;
import dev.maddie.agents.mcp.ToolException;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Comparator;

public final class Combat {
    private Combat() {
    }

    public static Entity nearestHostile(Player p, double radius) {
        return p.level().getEntities(p, p.getBoundingBox().inflate(radius), e -> e instanceof Enemy && e.isAlive()
                        && e.distanceTo(p) <= radius).stream()
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(p))).orElse(null);
    }

    /** Rough damage per second of an item used as a weapon. */
    static double weaponScore(ItemStack stack) {
        double[] dmg = {1}, speed = {4};
        stack.forEachModifier(EquipmentSlot.MAINHAND, (attr, mod) -> {
            if (attr.value() == Attributes.ATTACK_DAMAGE.value()) dmg[0] += mod.amount();
            if (attr.value() == Attributes.ATTACK_SPEED.value()) speed[0] += mod.amount();
        });
        return dmg[0] * Math.max(0.1, speed[0]);
    }

    static void equipBestWeapon(Agent a, Player p) {
        Inventory inv = p.getInventory();
        int best = -1;
        double bestScore = weaponScore(inv.getSelected());
        for (int i = 0; i < 36; i++) {
            double s = weaponScore(inv.items.get(i));
            if (s > bestScore + 0.01) {
                best = i;
                bestScore = s;
            }
        }
        if (best >= 0) {
            try {
                a.body().equip(best);
            } catch (ToolException ignored) {
            }
        }
    }

    /** Chases and hits one entity with full-strength attacks until it dies; raises a shield between hits. */
    public static final class Fight implements ActionRunner.TickAction {
        private final Entity target;
        private final int maxTicks;
        private final float fleeHealth;
        private final String label;
        private Navigator nav;
        private int ticks, hits;

        public Fight(Entity target, int maxTicks, float fleeHealth) {
            this.target = target;
            this.maxTicks = maxTicks;
            this.fleeHealth = fleeHealth;
            this.label = Game.id(target.getType()) + " id=" + target.getId();
        }

        @Override
        public String name() {
            return "fight " + label;
        }

        @Override
        public boolean interruptOnDamage() {
            return false;
        }

        @Override
        public String tick(Agent a, AgentPlayer p) throws ToolException {
            if (!target.isAlive() || target.isRemoved() || target.level() != p.level()) {
                boolean killed = target instanceof LivingEntity l && l.isDeadOrDying();
                return (killed ? "Killed " : "Target gone: ") + label + " after " + hits + " hit(s). Health "
                        + Game.round(p.getHealth()) + ". Use collect_items for drops.";
            }
            if (++ticks > maxTicks) {
                throw new ToolException("Gave up fighting " + label + " after " + maxTicks / 20 + "s.");
            }
            if (p.getHealth() <= fleeHealth) {
                throw new ToolException("Stopped fighting " + label + ": your health is low (" + Game.round(p.getHealth())
                        + "). Flee (move_to with continue_when_hurt) or eat.");
            }
            if (ticks == 1) equipBestWeapon(a, p);
            boolean shield = p.getOffhandItem().is(Items.SHIELD);

            if (!p.canInteractWithEntity(target, -0.2)) { // chase
                a.body().releaseUsingItem();
                if (nav == null || nav.goal().hdist(target.getX(), target.getZ()) > 1.5) {
                    if (nav != null) nav.stop(a);
                    nav = new Navigator(new Pathfinder.Goal(target.getX(), (int) Math.floor(target.getY()), target.getZ(), 1.5),
                            true, Pathfinder.Options.WALK);
                }
                if (nav.tick(a, p) == Navigator.Status.FAILED) {
                    throw new ToolException("Cannot reach " + label + ": " + nav.failure());
                }
                return null;
            }

            if (nav != null) {
                nav.stop(a);
                nav = null;
            }
            a.body().lookAt(target.getBoundingBox().getCenter());
            if (p.getAttackStrengthScale(0.5f) >= 0.95f) {
                a.body().releaseUsingItem();
                a.body().attack(target);
                a.body().swing(InteractionHand.MAIN_HAND);
                hits++;
            } else if (shield && !p.isUsingItem()) {
                a.body().useItem(InteractionHand.OFF_HAND); // the server keeps it raised until released
            }
            return null;
        }

        @Override
        public void stop(Agent a) {
            if (nav != null) nav.stop(a);
            a.body().releaseKeys();
            if (a.player() != null) a.body().releaseUsingItem();
        }
    }
}
