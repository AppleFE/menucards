package com.sunlitvalley.menucards.server;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

@GameTestHolder("menucards")
@PrefixGameTestTemplate(false)
public final class MenuCardActionsGameTests {
    private MenuCardActionsGameTests() {
    }

    @GameTest(templateNamespace = "menucards", template = "menucardsgametests.empty", timeoutTicks = 20)
    public static void teleportCompletionRequiresExactTarget(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = mockServerPlayer(level);
        Vec3 position = player.position();
        float yaw = player.getYRot();
        float pitch = player.getXRot();

        helper.assertTrue(MenuCardActions.teleportCompleted(player, level, position, yaw, pitch),
                "Teleport completion must accept the player at the requested coordinate in the requested level");
        helper.assertTrue(!MenuCardActions.teleportCompleted(
                        player, level, position.add(1.0D, 0.0D, 0.0D), yaw, pitch),
                "Teleport completion must reject a redirected coordinate");
        player.setYRot(yaw + 1.0F);
        helper.assertTrue(!MenuCardActions.teleportCompleted(player, level, position, yaw, pitch),
                "Teleport completion must reject a redirected rotation");
        player.setYRot(yaw);

        ServerLevel wrongLevel = level.getServer().getLevel(Level.NETHER);
        helper.assertTrue(wrongLevel != null, "GameTest server must provide the Nether for a wrong-level check");
        helper.assertTrue(!MenuCardActions.teleportCompleted(player, wrongLevel, position, yaw, pitch),
                "Teleport completion must reject a player left in a different level");
        helper.succeed();
    }

    @GameTest(templateNamespace = "menucards", template = "menucardsgametests.empty", timeoutTicks = 20)
    public static void teleportCountdownAllowsLookingButRejectsMovement(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = mockServerPlayer(level);
        Vec3 start = player.position();

        player.setYRot(player.getYRot() + 90.0F);
        player.setXRot(45.0F);
        helper.assertTrue(!TeleportCountdown.positionChanged(level.dimension(), start, player),
                "Looking around must not cancel a teleport countdown");

        player.setPos(start.add(0.01D, 0.0D, 0.0D));
        helper.assertTrue(TeleportCountdown.positionChanged(level.dimension(), start, player),
                "Changing position must cancel a teleport countdown");
        helper.assertTrue(TeleportCountdown.remainingSeconds(0L) == 5
                        && TeleportCountdown.remainingSeconds(20L) == 4
                        && TeleportCountdown.remainingSeconds(80L) == 1,
                "The teleport countdown must display five through one seconds");
        helper.succeed();
    }

    @GameTest(templateNamespace = "menucards", template = "menucardsgametests.empty", timeoutTicks = 20)
    public static void skullCavernResolverRevalidatesHighestSafeCache(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = mockServerPlayer(level);
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();
        BlockState[] originalColumn = new BlockState[maxY - minY];
        for (int y = minY; y < maxY; y++) {
            originalColumn[y - minY] = level.getBlockState(new BlockPos(0, y, 0));
        }

        int lowerFloor = minY + 8;
        int initialHighestFloor = minY + 16;
        int newHighestFloor = minY + 24;
        try {
            SkullCavernDestinationResolver.clearCache();
            for (int y = minY; y < maxY; y++) {
                level.setBlockAndUpdate(new BlockPos(0, y, 0), Blocks.AIR.defaultBlockState());
            }
            level.setBlockAndUpdate(new BlockPos(0, lowerFloor, 0), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(0, initialHighestFloor, 0), Blocks.STONE.defaultBlockState());

            assertDestinationFloor(helper, SkullCavernDestinationResolver.resolve(level, player), level,
                    initialHighestFloor, "The initial search must select the highest safe floor");
            assertDestinationFloor(helper, SkullCavernDestinationResolver.resolve(level, player), level,
                    initialHighestFloor, "An unchanged safe floor must be reused from the cache");

            level.setBlockAndUpdate(new BlockPos(0, newHighestFloor, 0), Blocks.STONE.defaultBlockState());
            assertDestinationFloor(helper, SkullCavernDestinationResolver.resolve(level, player), level,
                    newHighestFloor, "A newly higher safe floor must replace the cached floor");

            level.setBlockAndUpdate(new BlockPos(0, newHighestFloor, 0), Blocks.AIR.defaultBlockState());
            assertDestinationFloor(helper, SkullCavernDestinationResolver.resolve(level, player), level,
                    initialHighestFloor, "An unsafe cached floor must be invalidated in favor of the lower safe floor");

            SkullCavernDestinationResolver.clearCache();
            assertDestinationFloor(helper, SkullCavernDestinationResolver.resolve(level, player), level,
                    initialHighestFloor, "Clearing the cache must allow the current safe floor to be discovered again");
            helper.succeed();
        } finally {
            SkullCavernDestinationResolver.clearCache();
            for (int y = minY; y < maxY; y++) {
                level.setBlockAndUpdate(new BlockPos(0, y, 0), originalColumn[y - minY]);
            }
        }
    }
    @GameTest(templateNamespace = "menucards", template = "menucardsgametests.empty", timeoutTicks = 20)
    public static void skullCavernResolverFailsClosedWhenAboveCacheLoadFails(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = mockServerPlayer(level);
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();
        BlockState[] originalColumn = new BlockState[maxY - minY];
        for (int y = minY; y < maxY; y++) {
            originalColumn[y - minY] = level.getBlockState(new BlockPos(0, y, 0));
        }

        int cachedFloor = minY + 16;
        try {
            SkullCavernDestinationResolver.clearCache();
            for (int y = minY; y < maxY; y++) {
                level.setBlockAndUpdate(new BlockPos(0, y, 0), Blocks.AIR.defaultBlockState());
            }
            level.setBlockAndUpdate(new BlockPos(0, cachedFloor, 0), Blocks.STONE.defaultBlockState());

            assertDestinationFloor(helper, SkullCavernDestinationResolver.resolve(level, player), level,
                    cachedFloor, "The initial search must populate the cache");
            SkullCavernDestinationResolver.setChunkLoaderForTesting((loadLevel, position, bounds) -> {
                if (position.y > cachedFloor + 1.0D) {
                    throw new IllegalStateException("forced above-cache load failure");
                }
                return TeleportSafety.loadChunks(loadLevel, position, bounds);
            });

            helper.assertTrue(SkullCavernDestinationResolver.resolve(level, player).isEmpty(),
                    "An above-cache load failure must not return the cached destination");
            helper.succeed();
        } finally {
            SkullCavernDestinationResolver.resetChunkLoaderForTesting();
            SkullCavernDestinationResolver.clearCache();
            for (int y = minY; y < maxY; y++) {
                level.setBlockAndUpdate(new BlockPos(0, y, 0), originalColumn[y - minY]);
            }
        }
    }

    private static ServerPlayer mockServerPlayer(ServerLevel level) {
        return new ServerPlayer(level.getServer(), level,
                new GameProfile(UUID.randomUUID(), "menucards-test"));
    }
    private static void assertDestinationFloor(GameTestHelper helper,
            java.util.Optional<SkullCavernDestinationResolver.Destination> destination,
            ServerLevel level, int floorY, String message) {
        helper.assertTrue(destination.isPresent(), message);
        SkullCavernDestinationResolver.Destination resolved = destination.orElseThrow();
        helper.assertTrue(resolved.level() == level
                        && resolved.position().x == 0.5D
                        && resolved.position().y == floorY + 1.0D
                        && resolved.position().z == 0.5D,
                message);
    }
}
