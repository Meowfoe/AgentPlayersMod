package dev.maddie.agents.game;

import dev.maddie.agents.agent.Agent;
import dev.maddie.agents.agent.AgentPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

/** Automatic behaviors one agent can switch on with set_behavior. Ticked on the server thread. */
public final class Behaviors {
    /** Fight back automatically when a hostile mob hurts you (instead of just stopping the action). */
    public volatile boolean autoDefend = false;
    /** Eat automatically when hungry, or when hurt and not regenerating. */
    public volatile boolean autoEat = false;
    /** Auto-defend and the fight tool give up below this health. */
    public volatile float fleeHealth = 6f;

    private int eatCooldown;

    /** Returns an automatic action that should run now, or null. */
    ActionRunner.TickAction maybeStart(Agent agent, AgentPlayer p) {
        if (eatCooldown > 0) eatCooldown--;
        if (!autoEat || eatCooldown > 0 || p.isDeadOrDying() || p.getAbilities().instabuild) return null;
        int food = p.getFoodData().getFoodLevel();
        boolean hungry = food <= 6 || (food < 18 && p.getHealth() < p.getMaxHealth()); // regeneration needs food >= 18
        if (!hungry || Combat.nearestHostile(p, 6) != null) return null;
        int slot = bestFood(p);
        if (slot < 0) {
            eatCooldown = 20 * 60;
            agent.events().add("Auto-eat: you are hungry (food " + food + "/20) but have no safe food.");
            return null;
        }
        eatCooldown = 20 * 3;
        return new Eat(slot);
    }

    /** Inventory index of the most nourishing food without harmful effects, or -1. */
    public static int bestFood(Player p) {
        Inventory inv = p.getInventory();
        int best = -1;
        float bestScore = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.items.get(i);
            FoodProperties food = s.getFoodProperties(p);
            if (food == null || !p.canEat(food.canAlwaysEat())) continue;
            boolean harmful = food.effects().stream().anyMatch(e -> {
                MobEffectInstance m = e.effect();
                return !m.getEffect().value().isBeneficial();
            });
            if (harmful) continue;
            float score = food.nutrition() + food.saturation();
            if (score > bestScore) {
                best = i;
                bestScore = score;
            }
        }
        return best;
    }

    /** Equips and eats the food at an inventory index. The server keeps eating until done, like a held key. */
    public static final class Eat implements ActionRunner.TickAction {
        private final int slot;
        private int ticks = -1;
        private String what = "food";

        public Eat(int slot) {
            this.slot = slot;
        }

        @Override
        public String name() {
            return "eat";
        }

        @Override
        public String tick(Agent agent, AgentPlayer p) throws Exception {
            if (ticks < 0) {
                agent.body().equip(slot);
                what = Game.id(p.getMainHandItem().getItem());
                agent.body().useItem(InteractionHand.MAIN_HAND);
                ticks = 0;
                if (!p.isUsingItem()) return "Could not eat " + what + ".";
            }
            if (!p.isUsingItem() || ++ticks > 80) {
                return "Ate " + what + ". Food " + p.getFoodData().getFoodLevel() + "/20, health " + Game.round(p.getHealth()) + ".";
            }
            return null;
        }

        @Override
        public void stop(Agent agent) {
            agent.body().releaseUsingItem();
            agent.body().releaseKeys();
        }

        @Override
        public void pause(Agent agent) {
            stop(agent);
        }
    }
}
