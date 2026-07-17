package com.sunlitvalley.menucards.data;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Persistent, Menu Cards-owned optional spawn pose. */
public final class MenuCardsSpawnSavedData extends SavedData {
    private static final Logger LOGGER = LoggerFactory.getLogger(MenuCardsSpawnSavedData.class);
    private static final String DATA_NAME = "menucards_spawn";
    private static final String SPAWN_TAG = "Spawn";
    private static final String DIMENSION_TAG = "Dimension";
    private static final String X_TAG = "X";
    private static final String Y_TAG = "Y";
    private static final String Z_TAG = "Z";
    private static final String YAW_TAG = "Yaw";
    private static final String PITCH_TAG = "Pitch";

    private Optional<SpawnPose> spawn = Optional.empty();

    public static MenuCardsSpawnSavedData get(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        return server.overworld().getDataStorage().computeIfAbsent(
                MenuCardsSpawnSavedData::load,
                MenuCardsSpawnSavedData::new,
                DATA_NAME
        );
    }

    public static MenuCardsSpawnSavedData load(CompoundTag tag) {
        MenuCardsSpawnSavedData data = new MenuCardsSpawnSavedData();
        if (!tag.contains(SPAWN_TAG, Tag.TAG_COMPOUND)) {
            return data;
        }

        CompoundTag spawnTag = tag.getCompound(SPAWN_TAG);
        if (!spawnTag.contains(DIMENSION_TAG, Tag.TAG_STRING)
                || !spawnTag.contains(X_TAG, Tag.TAG_DOUBLE)
                || !spawnTag.contains(Y_TAG, Tag.TAG_DOUBLE)
                || !spawnTag.contains(Z_TAG, Tag.TAG_DOUBLE)
                || !spawnTag.contains(YAW_TAG, Tag.TAG_FLOAT)
                || !spawnTag.contains(PITCH_TAG, Tag.TAG_FLOAT)) {
            LOGGER.error("Rejected malformed MenuCards spawn data: missing or invalid field types: {}",
                    spawnTag);
            return data;
        }

        ResourceLocation dimensionId = ResourceLocation.tryParse(spawnTag.getString(DIMENSION_TAG));
        if (dimensionId == null) {
            LOGGER.error("Rejected malformed MenuCards spawn data: invalid dimension id: {}",
                    spawnTag.getString(DIMENSION_TAG));
            return data;
        }

        SpawnPose pose = new SpawnPose(
                ResourceKey.create(Registries.DIMENSION, dimensionId),
                spawnTag.getDouble(X_TAG),
                spawnTag.getDouble(Y_TAG),
                spawnTag.getDouble(Z_TAG),
                spawnTag.getFloat(YAW_TAG),
                spawnTag.getFloat(PITCH_TAG)
        );
        if (!pose.isFinite()) {
            LOGGER.error("Rejected malformed MenuCards spawn data: non-finite pose: {}", spawnTag);
            return data;
        }
        data.spawn = Optional.of(pose);
        return data;
    }

    public Optional<SpawnPose> getSpawn() {
        return spawn;
    }

    public void setSpawn(SpawnPose pose) {
        SpawnPose checkedPose = Objects.requireNonNull(pose, "pose");
        if (!checkedPose.isFinite()) {
            throw new IllegalArgumentException("Spawn pose values must be finite");
        }
        if (spawn.equals(Optional.of(checkedPose))) {
            return;
        }
        spawn = Optional.of(checkedPose);
        setDirty();
    }

    public void clearSpawn() {
        if (spawn.isEmpty()) {
            return;
        }
        spawn = Optional.empty();
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        spawn.ifPresent(pose -> {
            CompoundTag spawnTag = new CompoundTag();
            spawnTag.putString(DIMENSION_TAG, pose.dimension().location().toString());
            spawnTag.putDouble(X_TAG, pose.x());
            spawnTag.putDouble(Y_TAG, pose.y());
            spawnTag.putDouble(Z_TAG, pose.z());
            spawnTag.putFloat(YAW_TAG, pose.yaw());
            spawnTag.putFloat(PITCH_TAG, pose.pitch());
            tag.put(SPAWN_TAG, spawnTag);
        });
        return tag;
    }

    public record SpawnPose(ResourceKey<Level> dimension, double x, double y, double z, float yaw, float pitch) {
        public SpawnPose {
            Objects.requireNonNull(dimension, "dimension");
        }

        private boolean isFinite() {
            return Double.isFinite(x)
                    && Double.isFinite(y)
                    && Double.isFinite(z)
                    && Float.isFinite(yaw)
                    && Float.isFinite(pitch);
        }
    }
}
