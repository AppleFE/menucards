package com.sunlitvalley.menucards.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Validates the exact requested teleport position without loading chunks or relocating it. */
public final class TeleportSafety {
    private static final Logger LOGGER = LoggerFactory.getLogger(TeleportSafety.class);
    private static final TagKey<Block> UNSAFE_FLOOR = BlockTags.create(
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    "menucards", "teleport_unsafe_floor"));
    private static final TagKey<Block> UNSAFE_SPACE = BlockTags.create(
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    "menucards", "teleport_unsafe_space"));
    private static final double EPSILON = 1.0E-7D;

    private TeleportSafety() {
    }

    public static AABB destinationBounds(ServerPlayer player, Vec3 destination) {
        return player.getBoundingBox().move(
                destination.x - player.getX(),
                destination.y - player.getY(),
                destination.z - player.getZ());
    }

    public static boolean isSafe(ServerPlayer player, ServerLevel level, Vec3 destination) {
        return validate(player, level, destination) == SafetyResult.SAFE;
    }

    static boolean isSafe(ServerPlayer player, ServerLevel level, Vec3 destination, AABB bounds) {
        return validate(player, level, destination, bounds) == SafetyResult.SAFE;
    }

    public static SafetyResult validate(ServerPlayer player, ServerLevel level, Vec3 destination) {
        return validate(player, level, destination, destinationBounds(player, destination));
    }

    static SafetyResult validate(ServerPlayer player, ServerLevel level, Vec3 destination, AABB bounds) {
        try {
            if (!hasValidDestination(level, destination) || !hasFiniteBounds(bounds)
                    || !level.getWorldBorder().isWithinBounds(bounds) || !hasLoadedChunks(level, bounds)) {
                return SafetyResult.UNSAFE;
            }

            BlockPos floor = BlockPos.containing(destination.x, destination.y - EPSILON, destination.z);
            BlockState floorState = level.getBlockState(floor);
            if (floorState.is(UNSAFE_FLOOR)
                    || floorState.getCollisionShape(level, floor).isEmpty()
                    || !floorState.isFaceSturdy(level, floor, Direction.UP)) {
                return SafetyResult.UNSAFE;
            }

            BlockPos min = BlockPos.containing(bounds.minX + EPSILON, bounds.minY + EPSILON, bounds.minZ + EPSILON);
            BlockPos max = BlockPos.containing(bounds.maxX - EPSILON, bounds.maxY - EPSILON, bounds.maxZ - EPSILON);
            for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
                BlockState state = level.getBlockState(pos);
                FluidState fluid = state.getFluidState();
                if (!fluid.isEmpty() || state.is(UNSAFE_SPACE)) {
                    return SafetyResult.UNSAFE;
                }
            }
            return level.noCollision(player, bounds) ? SafetyResult.SAFE : SafetyResult.UNSAFE;
        } catch (RuntimeException exception) {
            LOGGER.warn("Teleport safety validation failed in {}", level.dimension().location(), exception);
            return SafetyResult.VALIDATION_FAILURE;
        }
    }

    public enum SafetyResult {
        SAFE,
        UNSAFE,
        VALIDATION_FAILURE
    }

    public static boolean loadChunks(ServerPlayer player, ServerLevel level, Vec3 destination) {
        return loadChunks(level, destination, destinationBounds(player, destination));
    }

    static boolean loadChunks(ServerLevel level, Vec3 destination, AABB bounds) {
        if (!hasValidDestination(level, destination) || !hasFiniteBounds(bounds)
                || !level.getWorldBorder().isWithinBounds(bounds)) {
            return false;
        }
        int minChunkX = BlockPos.containing(bounds.minX + EPSILON, 0.0D, 0.0D).getX() >> 4;
        int maxChunkX = BlockPos.containing(bounds.maxX - EPSILON, 0.0D, 0.0D).getX() >> 4;
        int minChunkZ = BlockPos.containing(0.0D, 0.0D, bounds.minZ + EPSILON).getZ() >> 4;
        int maxChunkZ = BlockPos.containing(0.0D, 0.0D, bounds.maxZ - EPSILON).getZ() >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                level.getChunk(chunkX, chunkZ);
            }
        }
        return true;
    }

    private static boolean hasValidDestination(ServerLevel level, Vec3 destination) {
        if (!Double.isFinite(destination.x) || !Double.isFinite(destination.y)
                || !Double.isFinite(destination.z)) {
            return false;
        }
        int floorY = BlockPos.containing(destination.x, destination.y - EPSILON, destination.z).getY();
        return floorY >= level.getMinBuildHeight() && floorY < level.getMaxBuildHeight();
    }
    private static boolean hasFiniteBounds(AABB bounds) {
        return Double.isFinite(bounds.minX) && Double.isFinite(bounds.minY) && Double.isFinite(bounds.minZ)
                && Double.isFinite(bounds.maxX) && Double.isFinite(bounds.maxY) && Double.isFinite(bounds.maxZ);
    }

    private static boolean hasLoadedChunks(ServerLevel level, AABB bounds) {
        int minChunkX = BlockPos.containing(bounds.minX + EPSILON, 0.0D, 0.0D).getX() >> 4;
        int maxChunkX = BlockPos.containing(bounds.maxX - EPSILON, 0.0D, 0.0D).getX() >> 4;
        int minChunkZ = BlockPos.containing(0.0D, 0.0D, bounds.minZ + EPSILON).getZ() >> 4;
        int maxChunkZ = BlockPos.containing(0.0D, 0.0D, bounds.maxZ - EPSILON).getZ() >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!level.hasChunk(chunkX, chunkZ)) {
                    return false;
                }
            }
        }
        return true;
    }
}
