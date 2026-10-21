package dev.csmc.server;

import dev.csmc.CsmcMod;
import dev.csmc.ballistics.Ballistics;
import dev.csmc.data.WeaponData;
import dev.csmc.data.WeaponRegistry;
import dev.csmc.item.GunItem;
import dev.csmc.item.KnifeItem;
import dev.csmc.melee.MeleeSystem;
import dev.csmc.network.KnifeEventPayload;
import dev.csmc.network.AmmoHudPayload;
import dev.csmc.network.GunActionPayload;
import dev.csmc.network.GunSoundPayload;
import dev.csmc.network.ScopeStatePayload;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** All state is accessed only on the logical server thread. */
public final class GunSystem {
    private static final Map<UUID, State> STATES = new HashMap<>();
    /** Server-side inspect lifetime; after this the state silently clears so a new inspect can be accepted. */
    private static final long INSPECT_TICKS = 50;
    private static final ResourceLocation SCOPE_SPEED = CsmcMod.id("scope_speed");

    private static final class State {
        boolean trigger;
        boolean pendingPress;
        double nextShot;
        int slot = -1;
        ItemStack gun = ItemStack.EMPTY;
        Reload reload;
        int scopeLevel;
        Restore restore;
        WeaponData scopeData;
        boolean scopeDirty = true;
        /** World the scope binding belongs to; respawn/dimension packets reset the client's scope capability. */
        ServerLevel level;
        /** Weapon being inspected (presentation only), bound to {@link #gun}/{@link #slot}; null when idle. */
        ResourceLocation inspecting;
        long inspectUntil;
        int hudMagazine = -1;
        int hudCapacity = -1;
        int hudReserve = -1;
        int hudSlot = -1;
        ResourceLocation hudWeapon;
        /** Shots fired by this player this session; each FIRE event carries the next number. */
        int shots;
        boolean hudReloading;
    }

    private record Reload(ItemStack gun, int slot, long finishesAt, String ammoType) {}
    private record Restore(ItemStack gun, int slot, int level, double at) {}

    private GunSystem() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) tick(player);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            removeScopeSpeed(handler.player);
            STATES.remove(handler.player.getUUID());
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity instanceof ServerPlayer player)) return;
            State state = STATES.get(player.getUUID());
            if (state != null) {
                clearZoom(player, state);
                syncScope(player, state, null);
            } else {
                removeScopeSpeed(player);
            }
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) removeScopeSpeed(player);
            STATES.clear();
        });
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
                player.getMainHandItem().getItem() instanceof GunItem ? InteractionResult.FAIL : InteractionResult.PASS);
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                player.getMainHandItem().getItem() instanceof GunItem ? InteractionResult.FAIL : InteractionResult.PASS);
        // Zoom is exclusively a custom input intent. Vanilla use packets must neither place/use anything
        // (including the offhand) nor cause a second zoom, even when sent by a modified client.
        UseBlockCallback.EVENT.register((player, level, hand, hit) ->
                hasScopedMainGun(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                hasScopedMainGun(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseItemCallback.EVENT.register((player, level, hand) -> hasScopedMainGun(player)
                ? InteractionResultHolder.fail(player.getItemInHand(hand))
                : InteractionResultHolder.pass(player.getItemInHand(hand)));
    }

    public static void handle(ServerPlayer player, int action) {
        State state = STATES.computeIfAbsent(player.getUUID(), id -> new State());
        bind(player, state);
        if (player.isAlive() && !player.isSpectator() && state.gun.getItem() instanceof KnifeItem knife) {
            // Knives share the gun inspect path (F); every other gun action is meaningless for them.
            if (action == GunActionPayload.INSPECT) startInspect(player, state, knife.weaponId());
            return;
        }
        if (!player.isAlive() || player.isSpectator() || !(state.gun.getItem() instanceof GunItem gun)) {
            cancelReload(player, state);
            stopInspect(player, state);
            state.trigger = false;
            state.pendingPress = false;
            clearZoom(player, state);
            syncScope(player, state, null);
            return;
        }
        WeaponData data = WeaponRegistry.get(gun.weaponId());
        syncScope(player, state, data);
        if (action == GunActionPayload.RELEASE) {
            state.trigger = false;
            return;
        }
        // Fire/reload intent ends an inspect even if the shot dry-fires or the reload is denied.
        if (action == GunActionPayload.PRESS || action == GunActionPayload.RELOAD
                || (action == GunActionPayload.ZOOM && data != null && data.zoom().enabled())) stopInspect(player, state);
        if (data == null) return;
        if (action == GunActionPayload.PRESS) {
            if (!state.trigger) state.pendingPress = true;
            state.trigger = true;
        } else if (action == GunActionPayload.RELOAD) {
            startReload(player, state, data);
        } else if (action == GunActionPayload.INSPECT) {
            startInspect(player, state, data.id());
        } else if (action == GunActionPayload.ZOOM && data.zoom().enabled() && state.reload == null) {
            state.restore = null;
            state.scopeLevel = (state.scopeLevel + 1) % (data.zoom().levelCount() + 1);
            state.scopeDirty = true;
            syncScope(player, state, data);
            String weapon = data.id().getPath();
            if (weapon.equals("awp") || weapon.equals("ssg08")) {
                GunSounds.broadcast(player, data.id(), state.scopeLevel == 0
                        ? GunSoundPayload.ZOOM_OUT : GunSoundPayload.ZOOM_IN, player.getEyePosition(), 0);
            }
        }
    }

    private static void bind(ServerPlayer player, State state) {
        ItemStack current = player.getMainHandItem();
        // An input packet can arrive before the new stack's first inventory tick.
        if (current.getItem() instanceof GunItem gun) gun.initializeMagazine(current);
        int slot = player.getInventory().selected;
        if (state.gun != current || state.slot != slot) {
            boolean drawn = current.getItem() instanceof GunItem && (slot != state.slot || current.getItem() != state.gun.getItem());
            cancelReload(player, state);
            stopInspect(player, state);
            clearZoom(player, state);
            state.scopeDirty = true;
            state.trigger = false;
            state.pendingPress = false;
            state.gun = current;
            state.slot = slot;
            if (drawn) {
                GunSounds.broadcast(player, ((GunItem) current.getItem()).weaponId(), GunSoundPayload.DRAW, player.getEyePosition(), 0);
            }
        }
        ServerLevel world = player.serverLevel();
        if (state.level != world) {
            // A world change keeps the same stack and slot, but the client discarded its scope capability.
            // Drop zoom and any pending bolt rezoom, and resend the binding even at level 0.
            clearZoom(player, state);
            state.scopeDirty = true;
            state.level = world;
        }
    }

    private static void tick(ServerPlayer player) {
        // Every player gets state so draws are confirmed before their first trigger/reload packet.
        State state = STATES.computeIfAbsent(player.getUUID(), id -> new State());
        bind(player, state);
        boolean knife = player.isAlive() && !player.isSpectator() && state.gun.getItem() instanceof KnifeItem;
        if (knife && state.inspecting != null && player.serverLevel().getGameTime() >= state.inspectUntil) {
            state.inspecting = null;
        }
        if (!player.isAlive() || player.isSpectator() || !(state.gun.getItem() instanceof GunItem gun)) {
            cancelReload(player, state);
            if (!knife) stopInspect(player, state);
            state.trigger = false;
            state.pendingPress = false;
            clearZoom(player, state);
            syncScope(player, state, null);
            if (state.hudMagazine >= 0) sendHud(player, state, AmmoHudPayload.cleared());
            return;
        }
        WeaponData data = WeaponRegistry.get(gun.weaponId());
        if (data == null) {
            cancelReload(player, state);
            stopInspect(player, state);
            state.pendingPress = false;
            clearZoom(player, state);
            syncScope(player, state, null);
            if (state.hudMagazine >= 0) sendHud(player, state, AmmoHudPayload.cleared());
            return;
        }
        syncScope(player, state, data);
        long now = player.serverLevel().getGameTime();
        if (state.inspecting != null && now >= state.inspectUntil) state.inspecting = null;
        if (state.reload != null && now >= state.reload.finishesAt) finishReload(player, state, data);
        if (state.restore != null && now + 1.0e-6 >= state.restore.at) {
            Restore restore = state.restore;
            state.restore = null;
            if (restore.gun == state.gun && restore.slot == state.slot && state.reload == null
                    && restore.level <= data.zoom().levelCount()) {
                state.scopeLevel = restore.level;
                state.scopeDirty = true;
                syncScope(player, state, data);
            }
        }
        boolean request = state.pendingPress || (state.trigger && data.automatic());
        state.pendingPress = false;
        if (!request || state.reload != null || now + 1.0e-6 < state.nextShot) {
            refreshHud(player, state, data, AmmoHudPayload.NOTICE_NONE);
            return;
        }
        int magazine = Math.min(data.magazineSize(), Math.max(0, state.gun.getOrDefault(CsmcMod.MAGAZINE, 0)));
        if (magazine == 0) {
            // Cooldown also bounds dry-fire effects; clients cannot flood sounds with trigger packets.
            state.nextShot = now + 5;
            sound(player, SoundEvents.DISPENSER_FAIL, 0.5f, 1.5f);
            refreshHud(player, state, data, AmmoHudPayload.NOTICE_NONE);
            return;
        }
        state.gun.set(CsmcMod.MAGAZINE, magazine - 1);
        // Fractional ticks preserve CS2's cycle time instead of rounding its cadence.
        int shotScope = state.scopeLevel;
        double cycleTicks = Math.max(1.0, data.activeCycleTimeS(shotScope > 0) * 20.0);
        if (now - state.nextShot > 1.0) state.nextShot = now;
        state.nextShot += cycleTicks;
        Ballistics.fire(player, data, ++state.shots, magazine - 1);
        if (shotScope > 0 && data.zoom().unzoomAfterShot()) {
            clearZoom(player, state);
            state.restore = new Restore(state.gun, state.slot, shotScope, now + cycleTicks);
            syncScope(player, state, data);
        }
        refreshHud(player, state, data, AmmoHudPayload.NOTICE_NONE);
    }

    /** Logical-server zoom, valid only while the original stack, selected slot and world are still bound. */
    public static int scopeLevel(ServerPlayer player) {
        State state = STATES.get(player.getUUID());
        return state != null && player.isAlive() && !player.isSpectator()
                && state.gun == player.getMainHandItem() && state.slot == player.getInventory().selected
                && state.level == player.serverLevel()
                ? state.scopeLevel : 0;
    }

    private static boolean hasScopedMainGun(Player player) {
        if (!(player.getMainHandItem().getItem() instanceof GunItem gun)) return false;
        WeaponData data = WeaponRegistry.get(gun.weaponId());
        return data != null && data.zoom().enabled();
    }

    private static void clearZoom(ServerPlayer player, State state) {
        if (state.scopeLevel != 0) state.scopeDirty = true;
        state.scopeLevel = 0;
        state.restore = null;
        removeScopeSpeed(player);
    }

    /** Sends only authoritative transitions/bindings, never to observers or once per render/fire tick. */
    private static void syncScope(ServerPlayer player, State state, WeaponData data) {
        if (state.scopeData != data) {
            clearZoom(player, state);
            state.scopeData = data;
            state.scopeDirty = true;
        }
        if (!state.scopeDirty) return;
        int levels = data == null ? 0 : data.zoom().levelCount();
        if (state.scopeLevel > levels) clearZoom(player, state);
        var movement = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (movement != null && state.scopeLevel > 0) {
            double amount = data.activeMaxSpeed(true) / data.maxSpeed() - 1.0;
            var current = movement.getModifier(SCOPE_SPEED);
            if (amount == 0.0) {
                movement.removeModifier(SCOPE_SPEED);
            } else if (current == null || current.amount() != amount) {
                movement.removeModifier(SCOPE_SPEED);
                movement.addTransientModifier(new AttributeModifier(SCOPE_SPEED, amount,
                        AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            }
        } else {
            removeScopeSpeed(player);
        }
        // Mock players and vanilla clients do not advertise the channel. Keep the binding dirty until
        // negotiation completes so the first real owner update includes the level-0 capability.
        if (!ServerPlayNetworking.canSend(player, ScopeStatePayload.TYPE)) return;
        int level = state.scopeLevel;
        ServerPlayNetworking.send(player, new ScopeStatePayload(state.slot,
                data == null ? CsmcMod.id("none") : data.id(), level, levels,
                level == 0 ? 90.0F : (float) data.zoom().fovDegrees(level),
                data == null ? 0.0F : (float) data.zoom().transitionTimeS(level)));
        state.scopeDirty = false;
    }

    private static void removeScopeSpeed(ServerPlayer player) {
        var movement = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (movement != null) movement.removeModifier(SCOPE_SPEED);
    }

    /**
     * Owner HUD state (magazine, capacity, matching reserve, reload); sent only on change or with a one-shot
     * notice. Mock players and vanilla clients lack the channel, so nothing is recorded as sent for them.
     */
    private static void refreshHud(ServerPlayer player, State state, WeaponData data, int notice) {
        int magazine = Math.min(data.magazineSize(), Math.max(0, state.gun.getOrDefault(CsmcMod.MAGAZINE, 0)));
        int reserve = countAmmo(player, CsmcMod.ammo(data.ammoType()));
        boolean reloading = state.reload != null;
        if (notice == AmmoHudPayload.NOTICE_NONE && magazine == state.hudMagazine
                && data.magazineSize() == state.hudCapacity && reserve == state.hudReserve
                && reloading == state.hudReloading && state.slot == state.hudSlot && data.id().equals(state.hudWeapon)) {
            return;
        }
        sendHud(player, state, new AmmoHudPayload(state.slot, data.id(), magazine, data.magazineSize(), reserve,
                reloading, notice));
    }

    private static void sendHud(ServerPlayer player, State state, AmmoHudPayload payload) {
        if (!ServerPlayNetworking.canSend(player, AmmoHudPayload.TYPE)) return;
        ServerPlayNetworking.send(player, payload);
        state.hudMagazine = payload.magazine();
        state.hudCapacity = payload.capacity();
        state.hudReserve = payload.reserve();
        state.hudReloading = payload.reloading();
        state.hudSlot = payload.slot();
        state.hudWeapon = payload.weaponId();
    }

    private static void startInspect(ServerPlayer player, State state, ResourceLocation weapon) {
        // Already inspecting: ignore so repeated keypresses cannot flood broadcasts.
        if (state.inspecting != null || state.reload != null || state.trigger) return;
        state.inspecting = weapon;
        state.inspectUntil = player.serverLevel().getGameTime() + INSPECT_TICKS;
        GunSounds.broadcastInspect(player, state.inspecting, state.slot, true);
        if (state.gun.getItem() instanceof KnifeItem) {
            MeleeSystem.broadcast(player, weapon.getPath(), KnifeEventPayload.INSPECT, player.getEyePosition());
        }
    }

    /** The weapon (gun or knife) the player is inspecting, bound to the held stack and slot, or null. */
    @org.jetbrains.annotations.Nullable
    public static ResourceLocation inspecting(ServerPlayer player) {
        State state = STATES.get(player.getUUID());
        return state == null || state.gun != player.getMainHandItem() ? null : state.inspecting;
    }

    /** Ends an active inspect (e.g. a knife attack); no-op when idle. */
    public static void stopInspect(ServerPlayer player) {
        State state = STATES.get(player.getUUID());
        if (state != null) stopInspect(player, state);
    }

    /** Cancels an active inspect; broadcasts only when one was active, keeping traffic bounded. */
    private static void stopInspect(ServerPlayer player, State state) {
        if (state.inspecting == null) return;
        ResourceLocation weapon = state.inspecting;
        state.inspecting = null;
        GunSounds.broadcastInspect(player, weapon, state.slot, false);
    }

    /** Shots this player has fired this session (the sequence number of the latest FIRE event); 0 if none. */
    public static int shotSequence(ServerPlayer player) {
        State state = STATES.get(player.getUUID());
        return state == null ? 0 : state.shots;
    }

    /**
     * Rounds a reload will load: what fits in the magazine, limited by the reserve. Computed when the reload
     * starts, so clients can play one shotgun insertion per shell; {@link #finishReload} loads by the same rule.
     */
    public static int roundsToLoad(int magazine, int magazineSize, int reserve) {
        return Math.max(0, Math.min(magazineSize - Math.max(0, magazine), reserve));
    }

    private static void startReload(ServerPlayer player, State state, WeaponData data) {
        // Reload intent unzooms even when the magazine is full, no ammo exists, or a reload is already active.
        clearZoom(player, state);
        syncScope(player, state, data);
        if (state.reload != null || state.gun.getOrDefault(CsmcMod.MAGAZINE, 0) >= data.magazineSize()) return;
        Item ammo = CsmcMod.ammo(data.ammoType());
        if (ammo == Items.AIR || countAmmo(player, ammo) == 0) {
            refreshHud(player, state, data, AmmoHudPayload.NOTICE_NO_AMMO);
            return;
        }
        long ticks = Math.max(1, (long) Math.ceil(data.reloadTimeS() * 20));
        state.reload = new Reload(state.gun, state.slot, player.serverLevel().getGameTime() + ticks, data.ammoType());
        int magazine = Math.max(0, state.gun.getOrDefault(CsmcMod.MAGAZINE, 0));
        GunSounds.broadcast(player, data.id(), GunSoundPayload.RELOAD_START, player.getEyePosition(), (int) ticks,
                0, magazine, roundsToLoad(magazine, data.magazineSize(), countAmmo(player, ammo)));
        refreshHud(player, state, data, AmmoHudPayload.NOTICE_NONE);
    }

    private static void finishReload(ServerPlayer player, State state, WeaponData data) {
        Reload reload = state.reload;
        state.reload = null;
        if (reload.gun != player.getMainHandItem() || reload.slot != player.getInventory().selected
                || !reload.ammoType.equals(data.ammoType())) return;
        Item ammo = CsmcMod.ammo(data.ammoType());
        int current = Math.min(data.magazineSize(), Math.max(0, state.gun.getOrDefault(CsmcMod.MAGAZINE, 0)));
        int needed = data.magazineSize() - current;
        int loaded = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize() && loaded < needed; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.is(ammo)) continue;
            int take = Math.min(needed - loaded, stack.getCount());
            stack.shrink(take);
            loaded += take;
        }
        state.gun.set(CsmcMod.MAGAZINE, current + loaded);
        player.getInventory().setChanged();
        GunSounds.broadcast(player, data.id(), GunSoundPayload.RELOAD_END, player.getEyePosition(), 0);
        refreshHud(player, state, data, AmmoHudPayload.NOTICE_NONE);
    }

    private static void cancelReload(ServerPlayer player, State state) {
        if (state.reload != null) {
            state.reload = null;
            if (state.gun.getItem() instanceof GunItem gun) {
                GunSounds.broadcast(player, gun.weaponId(), GunSoundPayload.RELOAD_CANCEL, player.getEyePosition(), 0);
            }
            if (state.gun.getItem() instanceof GunItem gun && WeaponRegistry.get(gun.weaponId()) != null) {
                refreshHud(player, state, WeaponRegistry.get(gun.weaponId()), AmmoHudPayload.NOTICE_RELOAD_CANCELLED);
            }
        }
    }

    /** Total matching inventory rounds, including offhand; shared by reload checks and the reserve HUD. */
    public static int countAmmo(ServerPlayer player, Item ammo) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(ammo)) count += stack.getCount();
        }
        return count;
    }

    private static void sound(ServerPlayer player, net.minecraft.sounds.SoundEvent sound, float volume, float pitch) {
        player.serverLevel().playSound(null, player.blockPosition(), sound, SoundSource.PLAYERS, volume, pitch);
    }
}
