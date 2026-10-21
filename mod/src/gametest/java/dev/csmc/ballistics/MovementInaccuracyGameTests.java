package dev.csmc.ballistics;

import dev.csmc.CsmcMod;
import dev.csmc.data.WeaponData;
import dev.csmc.data.WeaponRegistry;
import dev.csmc.movement.CsMovement;
import dev.csmc.movement.MovementMode;
import dev.csmc.movement.physics.CsUnits;
import net.fabricmc.fabric.api.gamerule.v1.rule.EnumRule;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Movement inaccuracy with and without CS movement. The gamerule is switched and restored within one synchronous
 * call so concurrently running tests never observe CS movement.
 */
public final class MovementInaccuracyGameTests implements FabricGameTest {
    private static final double EPSILON = 1.0E-9;

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void stanceInaccuracyUnchangedWithMovementOffAndScaledWithItOn(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        player.getInventory().selected = 0;
        player.getInventory().setItem(0, new ItemStack(CsmcMod.AK47));
        player.setOnGround(true);
        WeaponData data = WeaponRegistry.get(CsmcMod.AK47.weaponId());
        WeaponData.Inaccuracy in = data.activeInaccuracy(false);
        ShooterState state = ShooterState.of(player);
        EnumRule<MovementMode> rule = player.serverLevel().getGameRules().getRule(CsMovement.RULE);
        helper.assertValueEqual(rule.get(), MovementMode.OFF, "csmcMovement defaults to off");
        helper.assertValueEqual(CsMovement.inaccuracySpeedScale(player), 1.0, "no rescaling with movement off");

        // 160 u/s at the CS movement scale: 74% of the AK-47's max speed, but faster than vanilla walking.
        double speed = CsUnits.unitsPerSecondToBlocksPerTick(160.0);
        state.horizontalSpeed = speed;
        double off = Ballistics.stanceInaccuracy(player, state, data, false);
        helper.assertTrue(Math.abs(off - (in.stand() + in.move() * Ballistics.movementScale(speed, data.maxSpeed())))
                < EPSILON, "movement off keeps the existing ramp: " + off);
        helper.assertTrue(Math.abs(off - (in.stand() + in.move())) < EPSILON,
                "with movement off this speed is above vanilla walking, so full movement inaccuracy");

        double on;
        double scale;
        rule.set(MovementMode.COMPETITIVE, player.server);
        try {
            on = Ballistics.stanceInaccuracy(player, state, data, false);
            scale = CsMovement.inaccuracySpeedScale(player);
        } finally {
            rule.set(MovementMode.OFF, player.server);
        }
        helper.assertTrue(Math.abs(scale - (4.317 / 20.0) / CsUnits.unitsPerSecondToBlocksPerTick(250.0)) < EPSILON
                && Math.abs(scale - 0.69072) < 1.0E-4, "CS speed scale maps 250 u/s to vanilla walking: " + scale);
        double fraction = (160.0 / data.maxSpeed() - 0.34) / (0.95 - 0.34);
        helper.assertTrue(Math.abs(on - (in.stand() + in.move() * fraction)) < 1.0E-6,
                "with CS movement on, 160 u/s ramps by its share of the weapon's max speed: " + on);
        helper.assertTrue(on < off, "CS movement lowers inaccuracy at the same block speed");

        // Full CS run speed for the weapon gives the full movement penalty; standing still gives none.
        rule.set(MovementMode.BHOP, player.server);
        double full;
        double still;
        try {
            state.horizontalSpeed = CsUnits.unitsPerSecondToBlocksPerTick(data.maxSpeed());
            full = Ballistics.stanceInaccuracy(player, state, data, false);
            state.horizontalSpeed = 0.0;
            still = Ballistics.stanceInaccuracy(player, state, data, false);
        } finally {
            rule.set(MovementMode.OFF, player.server);
        }
        helper.assertTrue(Math.abs(full - (in.stand() + in.move())) < EPSILON, "max CS speed: full movement penalty");
        helper.assertTrue(Math.abs(still - in.stand()) < EPSILON, "standing still: stand inaccuracy only");
        remove(player);
        helper.succeed();
    }

    @SuppressWarnings("removal")
    private static ServerPlayer player(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Vec3 position = helper.absoluteVec(new Vec3(0.5, 4.0, 0.5));
        player.teleportTo(position.x, position.y, position.z);
        player.setNoGravity(true);
        return player;
    }

    private static void remove(ServerPlayer player) {
        player.server.getPlayerList().remove(player);
        player.discard();
    }
}
