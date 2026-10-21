package dev.csmc.ballistics;

import dev.csmc.data.WeaponData;
import dev.csmc.data.WeaponRegistry;
import dev.csmc.item.GunItem;
import dev.csmc.server.GunSystem;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Per-player CS2 weapon state kept on the logical server thread: spray shot index (recoil index),
 * accumulated fire/land inaccuracy ({@code m_fAccuracyPenalty}) and measured horizontal speed.
 * Movement is measured from server-side positions because a {@link ServerPlayer}'s delta movement is not
 * authoritative for client-driven movement.
 */
final class ShooterState {
    private static final Map<UUID, ShooterState> STATES = new HashMap<>();
    private static final double TICK_S = 1.0 / 20.0;
    private static final double LN_10 = Math.log(10.0);

    /** 1-based index of the last shot in the current spray; 0 = no spray in progress. */
    private int shotsFired;
    private long lastShotTick;
    /** Fire and landing inaccuracy on top of the stance terms; decays over the weapon's recovery time. */
    double accuracyPenalty;
    /** Horizontal speed in blocks per tick, measured over the last server tick. */
    double horizontalSpeed;
    /**
     * Weapon, main-hand stack (identity) and hotbar slot the spray and penalty belong to; drawing another
     * stack — even of the same weapon type — or selecting another slot resets both like a CS deploy.
     */
    @Nullable
    private WeaponData boundWeapon;
    @Nullable
    private ItemStack boundStack;
    private int boundSlot = -1;
    private boolean wasOnGround = true;
    private Vec3 lastPos;
    /**
     * Recoil kicks sent to the client ({@code GunRecoilPayload}) that the server assumes are not yet part of
     * the rotation the client reports. Shots aim at the reported view plus this offset, so a burst fired
     * before the client echoes its kicked view still climbs. Kicks persist across weapon switches: the
     * client applies them regardless.
     */
    private final ArrayDeque<PendingKick> pendingKicks = new ArrayDeque<>();
    private double pendingPitch;
    private double pendingYaw;

    /** {@code absorbedAt}: game time from which the client's reported rotation is assumed to contain it. */
    private record PendingKick(double pitch, double yaw, long absorbedAt) {}

    private ShooterState() {}

    static ShooterState of(ServerPlayer player) {
        return STATES.computeIfAbsent(player.getUUID(), id -> new ShooterState());
    }

    static void tick(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            of(player).tick(player);
        }
    }

    private void tick(ServerPlayer player) {
        Vec3 pos = player.position();
        if (lastPos != null) {
            double dx = pos.x - lastPos.x;
            double dz = pos.z - lastPos.z;
            horizontalSpeed = Math.sqrt(dx * dx + dz * dz);
        }
        lastPos = pos;
        expireKicks(player.serverLevel().getGameTime());

        WeaponData held = heldWeapon(player);
        bind(held, player);
        boolean onGround = player.onGround();
        if (held != null) {
            // CS2 m_flInaccuracyLand: landing adds a penalty that then recovers like fire inaccuracy.
            if (onGround && !wasOnGround && !player.onClimbable()) {
                accuracyPenalty += held.activeInaccuracy(GunSystem.scopeLevel(player) > 0).land();
            }
            double recovery = held.recovery().timeS(player.isCrouching(), Math.max(1, shotsFired));
            // CS: the penalty decays to 10% over the recovery time.
            accuracyPenalty = recovery > 0.0 ? accuracyPenalty * Math.exp(-TICK_S * LN_10 / recovery) : 0.0;
        }
        wasOnGround = onGround;
    }

    /**
     * Binds the state to the weapon in use and the player's current main-hand stack and selected slot. A
     * different weapon (or none), a different stack object or another selected slot starts a fresh spray and
     * clears the accumulated penalty. Identity comparisons: a datapack reload or a replaced stack also count
     * as a switch.
     */
    void bind(@Nullable WeaponData weapon, ServerPlayer player) {
        ItemStack stack = player.getMainHandItem();
        int slot = player.getInventory().selected;
        if (weapon != boundWeapon || stack != boundStack || slot != boundSlot) {
            boundWeapon = weapon;
            boundStack = stack;
            boundSlot = slot;
            shotsFired = 0;
            lastShotTick = 0L;
            accuracyPenalty = 0.0;
        }
    }

    /**
     * Registers a shot at game time {@code now} and returns its 1-based spray (recoil) index. The spray
     * restarts when no spray is in progress, when more than {@code quietTicks} passed since the previous
     * shot, or when game time went backwards.
     */
    int nextShot(long now, long quietTicks) {
        if (shotsFired == 0 || now < lastShotTick || now - lastShotTick > quietTicks) {
            shotsFired = 0;
        }
        shotsFired++;
        lastShotTick = now;
        return shotsFired;
    }

    /** Current spray index; 0 when no spray is in progress. */
    int shotsFired() {
        return shotsFired;
    }

    /**
     * Records a kick just sent to the client. It counts toward the aim offset until game time
     * {@code now + absorbDelayTicks}, when the client's rotation reports are assumed to include it.
     */
    void addPendingKick(WeaponData.Kick kick, long now, long absorbDelayTicks) {
        if (kick.pitch() == 0.0 && kick.yaw() == 0.0) {
            return;
        }
        pendingKicks.addLast(new PendingKick(kick.pitch(), kick.yaw(), now + Math.max(1L, absorbDelayTicks)));
        pendingPitch += kick.pitch();
        pendingYaw += kick.yaw();
    }

    /** Forgets kicks the client has had time to apply; game time going backwards forgets everything. */
    private void expireKicks(long now) {
        while (!pendingKicks.isEmpty() && (pendingKicks.peekFirst().absorbedAt() <= now
                || now < lastShotTick)) {
            PendingKick kick = pendingKicks.removeFirst();
            pendingPitch -= kick.pitch();
            pendingYaw -= kick.yaw();
        }
        if (pendingKicks.isEmpty()) {
            // Drop accumulated floating-point residue.
            pendingPitch = 0.0;
            pendingYaw = 0.0;
        }
    }

    /** Degrees of kick not yet reflected in the reported view; positive raises the aim. */
    double pendingPitch() {
        return pendingPitch;
    }

    /** Degrees of kick not yet reflected in the reported view; positive turns the aim right. */
    double pendingYaw() {
        return pendingYaw;
    }

    @Nullable
    private static WeaponData heldWeapon(ServerPlayer player) {
        return player.getMainHandItem().getItem() instanceof GunItem gun ? WeaponRegistry.get(gun.weaponId()) : null;
    }

    static void remove(ServerPlayer player) {
        STATES.remove(player.getUUID());
    }

    static void reset() {
        STATES.clear();
    }
}
