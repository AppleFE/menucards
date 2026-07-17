package com.sunlitvalley.menucards.server;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Finds the highest safe position in the Skull Cavern's exact origin column. */
public final class SkullCavernDestinationResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkullCavernDestinationResolver.class);
    private static final ResourceKey<Level> SKULL_CAVERN = ResourceKey.create(
            Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("society", "skull_cavern"));
    private static final int X = 0;
    private static final int Z = 0;
    private static Destination cachedDestination;
    private static final ChunkLoader DEFAULT_CHUNK_LOADER = TeleportSafety::loadChunks;
    private static ChunkLoader chunkLoader = DEFAULT_CHUNK_LOADER;

    private SkullCavernDestinationResolver() {
    }

    public static Optional<Destination> resolve(MinecraftServer server, ServerPlayer player) {
        ServerLevel level = server.getLevel(SKULL_CAVERN);
        if (level == null) {
            return Optional.empty();
        }
        return resolve(level, player);
    }

    public static Optional<Destination> resolve(ServerLevel level, ServerPlayer player) {
        int highestFloor = level.getMaxBuildHeight() - 1;
        CacheResult cached = revalidateCachedDestination(level, player);
        if (cached.outcome() == CacheOutcome.LOAD_FAILURE
                || cached.safetyResult() == TeleportSafety.SafetyResult.VALIDATION_FAILURE) {
            return Optional.empty();
        }
        if (cached.outcome() == CacheOutcome.FOUND) {
            int cachedFloor = (int) cached.destination().position().y - 1;
            SearchResult higherDestination = findSafeDestination(
                    level, player, highestFloor, cachedFloor + 1);
            if (higherDestination.outcome() == SearchOutcome.FOUND) {
                cachedDestination = higherDestination.destination();
                return Optional.of(higherDestination.destination());
            }
            if (higherDestination.outcome() != SearchOutcome.NOT_FOUND) {
                return Optional.empty();
            }
            return Optional.of(cached.destination());
        }

        SearchResult destination = findSafeDestination(
                level, player, highestFloor, level.getMinBuildHeight());
        cachedDestination = destination.outcome() == SearchOutcome.FOUND ? destination.destination() : null;
        return destination.outcome() == SearchOutcome.FOUND
                ? Optional.of(destination.destination())
                : Optional.empty();
    }

    public static void clearCache() {
        cachedDestination = null;
    }

    public static void setChunkLoaderForTesting(ChunkLoader loader) {
        chunkLoader = loader;
    }

    public static void resetChunkLoaderForTesting() {
        chunkLoader = DEFAULT_CHUNK_LOADER;
    }
    private static CacheResult revalidateCachedDestination(ServerLevel level, ServerPlayer player) {
        Destination cached = cachedDestination;
        if (cached == null || cached.level() != level) {
            return CacheResult.missOrUnsafe(null);
        }
        AABB bounds = TeleportSafety.destinationBounds(player, cached.position());
        try {
            if (!chunkLoader.load(level, cached.position(), bounds)) {
                return CacheResult.loadFailure();
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to load cached Skull Cavern destination chunks", exception);
            return CacheResult.loadFailure();
        }
        TeleportSafety.SafetyResult safety = TeleportSafety.validate(player, level, cached.position(), bounds);
        return safety == TeleportSafety.SafetyResult.SAFE
                ? CacheResult.found(cached)
                : CacheResult.missOrUnsafe(safety);
    }

    private static SearchResult findSafeDestination(
            ServerLevel level, ServerPlayer player, int highestFloor, int lowestFloor) {
        Vec3 highestPosition = new Vec3(X + 0.5D, highestFloor + 1.0D, Z + 0.5D);
        AABB highestBounds = TeleportSafety.destinationBounds(player, highestPosition);
        try {
            if (!chunkLoader.load(level, highestPosition, highestBounds)) {
                return SearchResult.loadFailure();
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to load Skull Cavern destination chunks", exception);
            return SearchResult.loadFailure();
        }
        for (int floorY = highestFloor; floorY >= lowestFloor; floorY--) {
            Vec3 position = new Vec3(X + 0.5D, floorY + 1.0D, Z + 0.5D);
            AABB bounds = TeleportSafety.destinationBounds(player, position);
            TeleportSafety.SafetyResult safety = TeleportSafety.validate(player, level, position, bounds);
            if (safety == TeleportSafety.SafetyResult.SAFE) {
                return SearchResult.found(new Destination(level, position));
            }
            if (safety == TeleportSafety.SafetyResult.VALIDATION_FAILURE) {
                return SearchResult.validationFailure();
            }
        }
        return SearchResult.notFound();
    }

    @FunctionalInterface
    public interface ChunkLoader {
        boolean load(ServerLevel level, Vec3 position, AABB bounds);
    }

    private enum CacheOutcome {
        FOUND,
        MISS_OR_UNSAFE,
        LOAD_FAILURE
    }

    private record CacheResult(
            CacheOutcome outcome,
            Destination destination,
            TeleportSafety.SafetyResult safetyResult) {
        private static CacheResult found(Destination destination) {
            return new CacheResult(CacheOutcome.FOUND, destination, TeleportSafety.SafetyResult.SAFE);
        }

        private static CacheResult missOrUnsafe(TeleportSafety.SafetyResult safetyResult) {
            return new CacheResult(CacheOutcome.MISS_OR_UNSAFE, null, safetyResult);
        }

        private static CacheResult loadFailure() {
            return new CacheResult(CacheOutcome.LOAD_FAILURE, null, null);
        }
    }

    private enum SearchOutcome {
        FOUND,
        NOT_FOUND,
        LOAD_FAILURE,
        VALIDATION_FAILURE
    }

    private record SearchResult(SearchOutcome outcome, Destination destination) {
        private static SearchResult found(Destination destination) {
            return new SearchResult(SearchOutcome.FOUND, destination);
        }

        private static SearchResult notFound() {
            return new SearchResult(SearchOutcome.NOT_FOUND, null);
        }

        private static SearchResult loadFailure() {
            return new SearchResult(SearchOutcome.LOAD_FAILURE, null);
        }

        private static SearchResult validationFailure() {
            return new SearchResult(SearchOutcome.VALIDATION_FAILURE, null);
        }
    }

    public record Destination(ServerLevel level, Vec3 position) {
    }
}
