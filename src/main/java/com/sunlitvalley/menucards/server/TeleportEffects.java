package com.sunlitvalley.menucards.server;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Emits teleport feedback only after a teleport action has succeeded. */
public final class TeleportEffects {
    private static final Logger LOGGER = LoggerFactory.getLogger(TeleportEffects.class);
    private TeleportEffects() {
    }

    public static Source captureSource(ServerPlayer player) {
        return new Source(player.serverLevel(), player.position());
    }

    public static void play(ServerPlayer traveler, Source source) {
        runEffect("source particles", () -> particles(source.level(), source.position()));
        runEffect("destination particles",
                () -> particles(traveler.serverLevel(), traveler.position()));
        runEffect("traveler sound", () -> traveler.playNotifySound(
                SoundEvents.CHORUS_FRUIT_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F));
    }

    private static void runEffect(String description, Runnable effect) {
        try {
            effect.run();
        } catch (RuntimeException exception) {
            LOGGER.warn("MenuCards teleport {} failed after movement completed", description,
                    exception);
        }
    }

    private static void particles(ServerLevel level, Vec3 position) {
        level.sendParticles(ParticleTypes.PORTAL, position.x, position.y + 1.0D, position.z,
                32, 0.5D, 0.8D, 0.5D, 0.05D);
    }

    public record Source(ServerLevel level, Vec3 position) {
    }
}
