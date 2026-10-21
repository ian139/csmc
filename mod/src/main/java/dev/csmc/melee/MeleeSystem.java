package dev.csmc.melee;

import dev.csmc.CsmcMod;
import dev.csmc.ballistics.Ballistics;
import dev.csmc.ballistics.ArmorModel;
import dev.csmc.ballistics.TraceResult;
import dev.csmc.data.WeaponData;
import dev.csmc.item.KnifeItem;
import dev.csmc.network.KnifeActionPayload;
import dev.csmc.network.KnifeEventPayload;
import dev.csmc.server.GunSystem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Server-authoritative knife melee. Clients only send {@link KnifeActionPayload} intent (slash = attack press,
 * stab = use press); the server owns per-player cooldowns, the follow-up slash window, the reach trace, backstab
 * detection and damage. Knives never break blocks, and vanilla attacks/mining are cancelled while one is held.
 * All state is accessed only on the logical server thread.
 */
public final class MeleeSystem {
    /** Damage type from {@code data/csmc/damage_type/knife.json} (death message {@code death.attack.csmc.knife}). */
    public static final ResourceKey<DamageType> KNIFE_DAMAGE =
            ResourceKey.create(Registries.DAMAGE_TYPE, CsmcMod.id("knife"));
    /** Blocks the reach ray is widened by on every side when testing entity boxes (CS uses a small hull trace). */
    public static final double HULL = 0.25;
    /** Seconds after a slash becomes ready again during which the next slash counts as a follow-up. */
    public static final double FOLLOWUP_WINDOW_SECONDS = 1.0;
    /** Knockback strength of a knife hit (vanilla's own melee knockback is 0.4; CS knives push only slightly). */
    public static final double KNOCKBACK = 0.25;
    /** Backstab when the target's horizontal look dotted with the target-to-attacker direction is below this. */
    public static final double BACKSTAB_DOT = -0.5;

    private static final Map<UUID, State> STATES = new HashMap<>();

    private static final class State {
        ItemStack knife = ItemStack.EMPTY;
        int slot = -1;
        /** Game time (ticks) when the next attack is accepted. */
        double nextAttack;
        /** Game time (ticks) until which a slash is a follow-up. */
        double followupUntil = Double.NEGATIVE_INFINITY;
    }

    /** How one accepted or refused attack ended. */
    public enum Outcome { REFUSED, MISS, HIT, HITWALL }

    /**
     * The result of one knife action. {@code csDamage} is the damage after armor, {@code health} the Minecraft
     * health requested; {@code hurt} whether vanilla accepted it. {@code events} lists the broadcast events in order.
     */
    public record Attack(Outcome outcome, @Nullable Entity target, boolean backstab, boolean followup,
                         double csDamage, float health, boolean hurt, @Nullable Vec3 impact, List<String> events) {
        static Attack refused() {
            return new Attack(Outcome.REFUSED, null, false, false, 0, 0, false, null, List.of());
        }
    }

    private MeleeSystem() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) bind(player, state(player));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> STATES.remove(handler.player.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> STATES.clear());
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
                holdsKnife(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                holdsKnife(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        // Use is the stab key: with a knife in the main hand no vanilla use (doors, chests, off-hand items) happens.
        UseBlockCallback.EVENT.register((player, level, hand, hit) ->
                holdsKnife(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                holdsKnife(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseItemCallback.EVENT.register((player, level, hand) -> holdsKnife(player)
                ? InteractionResultHolder.fail(player.getItemInHand(hand))
                : InteractionResultHolder.pass(player.getItemInHand(hand)));
    }

    public static boolean holdsKnife(Player player) {
        return player.getMainHandItem().getItem() instanceof KnifeItem;
    }

    /** Network entry point: one {@link KnifeActionPayload} action. Unknown actions are ignored. */
    public static Attack handle(ServerPlayer player, byte action) {
        if (action == KnifeActionPayload.SLASH) return attack(player, false);
        if (action == KnifeActionPayload.STAB) return attack(player, true);
        return Attack.refused();
    }

    /** Clears cooldown and follow-up state (tests and respawn-like resets). */
    public static void reset(ServerPlayer player) {
        STATES.remove(player.getUUID());
    }

    private static State state(ServerPlayer player) {
        return STATES.computeIfAbsent(player.getUUID(), id -> new State());
    }

    /** Tracks the selected knife; selecting one (slot or item change) deploys it and ends the follow-up chain. */
    private static void bind(ServerPlayer player, State state) {
        ItemStack current = player.getMainHandItem();
        int slot = player.getInventory().selected;
        if (state.knife == current && state.slot == slot) return;
        boolean deployed = current.getItem() instanceof KnifeItem
                && (slot != state.slot || current.getItem() != state.knife.getItem());
        state.knife = current;
        state.slot = slot;
        state.followupUntil = Double.NEGATIVE_INFINITY;
        if (deployed && player.isAlive() && !player.isSpectator()) {
            broadcast(player, ((KnifeItem) current.getItem()).weaponId().getPath(), KnifeEventPayload.DEPLOY,
                    player.getEyePosition());
        }
    }

    private static Attack attack(ServerPlayer player, boolean stab) {
        State state = state(player);
        bind(player, state);
        if (!player.isAlive() || player.isSpectator() || !(player.getMainHandItem().getItem() instanceof KnifeItem knife)) {
            return Attack.refused();
        }
        String id = knife.weaponId().getPath();
        KnifeStats stats = KnifeStats.get(id);
        ServerLevel level = player.serverLevel();
        double now = level.getGameTime();
        if (now + 1.0e-6 < state.nextAttack) return Attack.refused();

        boolean followup = !stab && now < state.followupUntil;
        double cooldown = stab ? stats.stabCooldown() : followup ? stats.slashFollowupCooldown() : stats.slashCooldown();
        state.nextAttack = now + cooldown * 20.0;
        state.followupUntil = stab ? Double.NEGATIVE_INFINITY : state.nextAttack + FOLLOWUP_WINDOW_SECONDS * 20.0;
        GunSystem.stopInspect(player);

        double range = (stab ? stats.stabRange() : stats.slashRange()) / WeaponData.UNITS_PER_BLOCK;
        Vec3 eye = player.getEyePosition();
        // Current server rotation, as Ballistics aims: yHeadRot lags behind a just-processed look packet.
        Vec3 end = eye.add(Vec3.directionFromRotation(player.getXRot(), player.getYRot()).scale(range));
        BlockHitResult block = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        double blockDistance = block.getType() == HitResult.Type.BLOCK ? eye.distanceTo(block.getLocation()) : Double.POSITIVE_INFINITY;
        EntityHit entityHit = traceEntity(player, eye, end);
        boolean direct = entityHit != null && entityHit.aimed != null && eye.distanceTo(entityHit.aimed) <= blockDistance;
        if (entityHit != null && !direct && entityHit.distance <= blockDistance) {
            // A hull-only contact must not reach around cover: the target's real box needs a clear line from the
            // eye, or the swing ends on the obstacle (e.g. a pane the target is pressed against). Contacts where the
            // aim ray itself meets the real box before any block are accepted above without this projection.
            BlockHitResult cover = level.clip(new ClipContext(eye, entityHit.surface, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, player));
            if (cover.getType() == HitResult.Type.BLOCK
                    && eye.distanceTo(cover.getLocation()) < eye.distanceTo(entityHit.surface) - 1.0e-4) {
                block = cover;
                blockDistance = eye.distanceTo(cover.getLocation());
                entityHit = null;
            }
        }

        List<String> events = new ArrayList<>(2);
        String swing = stab ? KnifeEventPayload.STAB : KnifeEventPayload.SLASH;
        if (entityHit != null && (direct || entityHit.distance <= blockDistance)) {
            LivingEntity target = entityHit.entity;
            boolean backstab = isBackstab(player, target);
            double csDamage = backstab
                    ? (stab ? stats.stabBackstabDamage() : stats.slashBackstabDamage())
                    : stab ? stats.stabDamage() : followup ? stats.slashFollowupDamage() : stats.slashDamage();
            events.add(backstab ? (stab ? KnifeEventPayload.BACKSTAB : KnifeEventPayload.SLASH_BACKSTAB) : swing);
            broadcast(player, id, events.get(0), eye);
            Damage damage = damage(player, target, csDamage, stats, entityHit.location);
            events.add(KnifeEventPayload.HIT);
            broadcast(player, id, KnifeEventPayload.HIT, entityHit.location);
            return new Attack(Outcome.HIT, target, backstab, followup, damage.csDamage, damage.health, damage.hurt,
                    entityHit.location, List.copyOf(events));
        }
        events.add(swing);
        broadcast(player, id, swing, eye);
        if (block.getType() == HitResult.Type.BLOCK) {
            // Presentation only: knives never damage or break blocks.
            Vec3 at = block.getLocation();
            events.add(KnifeEventPayload.HITWALL);
            broadcast(player, id, KnifeEventPayload.HITWALL, at);
            level.sendParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 3, 0.05, 0.05, 0.05, 0.05);
            return new Attack(Outcome.HITWALL, null, false, followup, 0, 0, false, at, List.copyOf(events));
        }
        return new Attack(Outcome.MISS, null, false, followup, 0, 0, false, null, List.copyOf(events));
    }

    /**
     * {@code location} is on the hull-widened box; {@code surface} is the nearest point of the real box to it;
     * {@code aimed} is where the aim ray itself enters the real box, or null when only the hull touches it.
     */
    private record EntityHit(LivingEntity entity, double distance, Vec3 location, Vec3 surface, @Nullable Vec3 aimed) {}

    /** Nearest living entity whose box, widened by {@link #HULL}, the eye ray crosses within range. */
    @Nullable
    private static EntityHit traceEntity(ServerPlayer player, Vec3 eye, Vec3 end) {
        AABB sweep = new AABB(eye, end).inflate(HULL + 0.5);
        EntityHit best = null;
        for (Entity entity : player.serverLevel().getEntities(player, sweep,
                e -> e instanceof LivingEntity && e.isAlive() && !e.isSpectator() && e.isPickable())) {
            AABB box = entity.getBoundingBox().inflate(HULL);
            Vec3 location;
            if (box.contains(eye)) {
                location = eye;
            } else {
                Optional<Vec3> clip = box.clip(eye, end);
                if (clip.isEmpty()) continue;
                location = clip.get();
            }
            double distance = eye.distanceTo(location);
            if (best == null || distance < best.distance) {
                AABB real = entity.getBoundingBox();
                Vec3 surface = new Vec3(Mth.clamp(location.x, real.minX, real.maxX),
                        Mth.clamp(location.y, real.minY, real.maxY), Mth.clamp(location.z, real.minZ, real.maxZ));
                Vec3 aimed = real.contains(eye) ? eye : real.clip(eye, end).orElse(null);
                best = new EntityHit((LivingEntity) entity, distance, location, surface, aimed);
            }
        }
        return best;
    }

    /**
     * Attacker behind the target: horizontal target facing dotted with target-to-attacker below {@link #BACKSTAB_DOT}.
     * Players face their current server yaw (head rotation lags look packets); mobs face where their head looks.
     */
    public static boolean isBackstab(Entity attacker, LivingEntity target) {
        float yaw = target instanceof Player ? target.getYRot() : target.getYHeadRot();
        Vec3 facing = Vec3.directionFromRotation(0.0F, yaw);
        Vec3 toAttacker = new Vec3(attacker.getX() - target.getX(), 0, attacker.getZ() - target.getZ());
        if (facing.lengthSqr() < 1.0e-8 || toAttacker.lengthSqr() < 1.0e-8) return false;
        return facing.normalize().dot(toAttacker.normalize()) < BACKSTAB_DOT;
    }

    private record Damage(double csDamage, float health, boolean hurt) {}

    /**
     * The csmc damage pipeline for a knife: PvP/team guards, tiered chest armor and durability wear,
     * then Minecraft health ({@link Ballistics#CS_TO_MC_HEALTH}) and vanilla enchantments.
     */
    private static Damage damage(ServerPlayer attacker, LivingEntity target, double csDamage, KnifeStats stats, Vec3 at) {
        ServerLevel level = attacker.serverLevel();
        if (target instanceof Player victim && (!level.getServer().isPvpAllowed() || !attacker.canHarmPlayer(victim))) {
            return new Damage(csDamage, 0.0F, false);
        }
        ArmorModel.Absorption absorbed = ArmorModel.absorb(target, TraceResult.HitGroup.CHEST,
                csDamage, stats.armorRatio(), 0.5);
        double damage = absorbed.healthDamage();
        ItemStack armor = target.getItemBySlot(EquipmentSlot.CHEST);
        float health = (float) (damage * Ballistics.CS_TO_MC_HEALTH);
        DamageSource damageSource = source(level, attacker);
        boolean hurt = target.hurt(damageSource, health);
        if (hurt) {
            if (absorbed.armorWear() > 0 && armor.isDamageableItem()
                    && target.getLastDamageSource() == damageSource) {
                // Damage-source recording survives totem restoration; health/absorption deltas do not.
                // The absorbing stack can already have transferred to a death drop.
                armor.hurtAndBreak(absorbed.armorWear(), target, EquipmentSlot.CHEST);
            }
            // Modest push away from the attacker; the knife damage type is tagged no_knockback so vanilla's
            // stronger melee knockback does not also apply.
            target.knockback(KNOCKBACK, attacker.getX() - target.getX(), attacker.getZ() - target.getZ());
            target.hurtMarked = true;
        }
        if (hurt) level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, at.x, at.y, at.z, 2, 0.1, 0.1, 0.1, 0.1);
        return new Damage(damage, health, hurt);
    }

    public static DamageSource source(ServerLevel level, ServerPlayer attacker) {
        return level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolder(KNIFE_DAMAGE)
                .map(type -> new DamageSource(type, attacker, attacker))
                .orElseGet(() -> level.damageSources().playerAttack(attacker));
    }

    /** Sends one knife event to the attacker and every player tracking them. */
    public static void broadcast(ServerPlayer attacker, String knifeId, String event, Vec3 at) {
        KnifeEventPayload payload = new KnifeEventPayload(knifeId, event, attacker.getId(), at.x, at.y, at.z);
        send(attacker, payload);
        for (ServerPlayer watcher : PlayerLookup.tracking(attacker)) {
            if (watcher != attacker) send(watcher, payload);
        }
    }

    private static void send(ServerPlayer player, KnifeEventPayload payload) {
        if (ServerPlayNetworking.canSend(player, KnifeEventPayload.TYPE)) ServerPlayNetworking.send(player, payload);
    }
}
