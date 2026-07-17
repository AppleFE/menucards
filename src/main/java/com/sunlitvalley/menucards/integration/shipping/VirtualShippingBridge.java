package com.sunlitvalley.menucards.integration.shipping;

import com.sunlitvalley.menucards.data.VirtualShippingSavedData;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import net.minecraft.world.level.storage.LevelResource;
/** Server-thread-only KubeJS boundary backed by MenuCards SavedData. */
public final class VirtualShippingBridge {
    private interface Storage {
        LeaseView pollDue(MinecraftServer server);
        BridgeResult submitPlan(UUID token, UUID owner, long inputGeneration, byte[] fingerprint, VirtualSalePlan plan);
        BridgeResult beginApplying(UUID token, UUID owner);
        @Nullable VirtualSalePlan.EconomyTerms acceptedEconomyTerms(UUID token, UUID owner);
        BridgeResult applyPlannedOutput(UUID token, UUID owner);
        BridgeResult reportPolicyStep(UUID token, UUID owner, PolicyStep step);
        BridgeResult removeInputAndComplete(UUID token, UUID owner);
        BridgeResult quarantine(UUID token, UUID owner, String reason);
        BridgeResult.PlanningAbortCode abortPlanning(UUID token, String reason);
    }
    public enum PolicyStep { OUTPUT, DEBT, BANK }

    private static final VirtualShippingBridge INSTANCE = new VirtualShippingBridge(new SavedDataStorage());
    private final Storage storage;
    private Thread serverThread;

    public static VirtualShippingBridge instance() { return INSTANCE; }
    private VirtualShippingBridge(Storage storage) { this.storage = Objects.requireNonNull(storage, "storage"); }

    /** The caller supplies the logical server on every scheduler poll. */
    public @Nullable LeaseView pollDue(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        bindServerThread();
        return storage.pollDue(server);
    }
    public BridgeResult submitPlan(LeaseView lease, String json) {
        requireServerThread(); if (!validLease(lease)) return BridgeResult.requeue("LEASE_INVALID");
        try { VirtualSalePlan plan = VirtualSalePlan.parse(json, lease.input()); plan.validateRegistry(); return storage.submitPlan(lease.token(), lease.playerId(), lease.inputGeneration(), CanonicalInputFingerprint.sha256(lease.input()), plan); }
        catch (VirtualSalePlan.PlanValidationException | IllegalArgumentException exception) { String reason = boundedReason(exception.getMessage()) ? exception.getMessage() : "PLAN_INVALID"; return BridgeResult.requeue(reason); }
    }
    /** Returns only the immutable economy terms persisted by the accepted APPLYING plan. */
    public @Nullable VirtualSalePlan.EconomyTerms acceptedEconomyTerms(LeaseView lease) {
        requireServerThread();
        if (!validLease(lease)) return null;
        return storage.acceptedEconomyTerms(lease.token(), lease.playerId());
    }
    /** Saves APPLYING before policy code can create any external proceeds. */
    public BridgeResult beginApplying(LeaseView lease) { requireServerThread(); if (!validLease(lease)) return BridgeResult.requeue("LEASE_INVALID"); return storage.beginApplying(lease.token(), lease.playerId()); }
    public BridgeResult applyPlannedOutput(LeaseView lease) { requireServerThread(); if (!validLease(lease)) return BridgeResult.quarantine("LEASE_INVALID"); try { return storage.applyPlannedOutput(lease.token(), lease.playerId()); } catch (RuntimeException exception) { return quarantine(lease, "OUTPUT_EXCEPTION"); } }
    public BridgeResult reportPolicyStep(LeaseView lease, String step) { requireServerThread(); if (!validLease(lease)) return BridgeResult.quarantine("LEASE_INVALID"); try { return storage.reportPolicyStep(lease.token(), lease.playerId(), PolicyStep.valueOf(step)); } catch (IllegalArgumentException exception) { return quarantine(lease, "POLICY_STEP"); } catch (RuntimeException exception) { return quarantine(lease, "POLICY_STEP_EXCEPTION"); } }
    public BridgeResult removeInputAndComplete(LeaseView lease) { requireServerThread(); if (!validLease(lease)) return BridgeResult.quarantine("LEASE_INVALID"); try { return storage.removeInputAndComplete(lease.token(), lease.playerId()); } catch (RuntimeException exception) { return quarantine(lease, "INPUT_REMOVAL_EXCEPTION"); } }
    public BridgeResult quarantine(LeaseView lease, String reason) { requireServerThread(); if (!validLease(lease)) return BridgeResult.quarantine("LEASE_INVALID"); return storage.quarantine(lease.token(), lease.playerId(), boundedReason(reason) ? reason : "QUARANTINE_REASON"); }
    public BridgeResult.PlanningAbortCode abortPlanning(UUID token, String reason) { requireServerThread(); Objects.requireNonNull(token, "token"); return storage.abortPlanning(token, boundedReason(reason) ? reason : "ABORT_REASON"); }
    public void resetRuntimeState() {
        serverThread = null;
        if (storage instanceof SavedDataStorage savedDataStorage) savedDataStorage.clear();
    }

    /** Non-sensitive status for commands; actor must be an online op even for inspection. */
    public @Nullable VirtualShippingSavedData.Status status(MinecraftServer server, ServerPlayer actor, UUID owner) { requireOnlineOperator(server, actor); bindServerThread(); return VirtualShippingSavedData.get(server.overworld()).status(owner); }
    /** Two-phase audited recovery: force-save reconciliation before clearing the quarantine. */
    public VirtualShippingSavedData.RecoveryResult recover(
            MinecraftServer server, ServerPlayer actor, UUID owner, UUID token) {
        requireOnlineOperator(server, actor);
        bindServerThread();
        VirtualShippingSavedData data = VirtualShippingSavedData.get(server.overworld());
        long now = server.overworld().getGameTime();
        VirtualShippingSavedData.RecoveryResult prepared =
                data.prepareRecovery(owner, token, actor.getUUID(), now);
        if (prepared != VirtualShippingSavedData.RecoveryResult.SUCCESS) return prepared;
        try {
            saveDurably(server, data);
        } catch (RuntimeException exception) {
            return VirtualShippingSavedData.RecoveryResult.AUDIT_NOT_PERSISTED;
        }
        VirtualShippingSavedData.RecoveryResult completed =
                data.completeRecovery(owner, token, actor.getUUID(), now);
        if (completed == VirtualShippingSavedData.RecoveryResult.SUCCESS) {
            try {
                saveDurably(server, data);
                data.finishRecovery(owner, token, actor.getUUID());
            } catch (RuntimeException exception) {
                data.rollbackRecoveryClear(owner, token, actor.getUUID());
                try {
                    saveDurably(server, data);
                } catch (RuntimeException compensationFailure) {
                    exception.addSuppressed(compensationFailure);
                }
                return VirtualShippingSavedData.RecoveryResult.AUDIT_NOT_PERSISTED;
            }
        }
        return completed;
    }

    private boolean validLease(LeaseView lease) { return lease != null && lease.player() != null && lease.player().getUUID().equals(lease.playerId()) && lease.input().length == CanonicalInputFingerprint.SLOT_COUNT && lease.output().length == CanonicalInputFingerprint.SLOT_COUNT; }
    private static boolean boundedReason(String reason) { return reason != null && reason.length() <= VirtualSalePlan.MAX_STRING && reason.getBytes(StandardCharsets.UTF_8).length <= VirtualSalePlan.MAX_STRING; }
    private void bindServerThread() { Thread current = Thread.currentThread(); if (serverThread == null) serverThread = current; else if (serverThread != current) throw new IllegalStateException("SERVER_THREAD_REQUIRED"); }
    private void requireServerThread() { if (serverThread == null || serverThread != Thread.currentThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED"); }
    private static void requireOnlineOperator(MinecraftServer server, ServerPlayer actor) { Objects.requireNonNull(server, "server"); Objects.requireNonNull(actor, "actor"); if (!server.isSameThread() || server.getPlayerList().getPlayer(actor.getUUID()) != actor || !server.getPlayerList().isOp(actor.getGameProfile())) throw new IllegalStateException("OPERATOR_REQUIRED"); }

    /** Resolves SavedData at poll time, so KubeJS never supplies a world or storage reference. */
    private static final class SavedDataStorage implements Storage {
        @Nullable private VirtualShippingSavedData data;
        @Nullable private MinecraftServer server;

        @Override
        public LeaseView pollDue(MinecraftServer server) {
            this.server = server;
            data = VirtualShippingSavedData.get(server.overworld());
            return data.pollDue(server);
        }

        private VirtualShippingSavedData data() {
            if (data == null) throw new IllegalStateException("POLL_REQUIRED");
            return data;
        }

        private void clear() {
            data = null;
            server = null;
        }

        @Override public BridgeResult submitPlan(UUID token, UUID owner, long generation, byte[] fingerprint, VirtualSalePlan plan) { return data().submitPlan(token, owner, generation, fingerprint, plan); }

        @Override
        public BridgeResult beginApplying(UUID token, UUID owner) {
            VirtualShippingSavedData savedData = data();
            BridgeResult result = savedData.beginApplying(token, owner);
            if (!result.succeeded()) return result;
            if (server == null) {
                savedData.rollbackApplyingBeforePolicy(token, owner);
                throw new IllegalStateException("POLL_REQUIRED");
            }
            try {
                saveDurably(server, savedData);
            } catch (RuntimeException exception) {
                savedData.rollbackApplyingBeforePolicy(token, owner);
                throw exception;
            }
            return result;
        }

        @Override public BridgeResult applyPlannedOutput(UUID token, UUID owner) { return persistTerminal(owner, data().applyPlannedOutput(token, owner)); }
        @Override public @Nullable VirtualSalePlan.EconomyTerms acceptedEconomyTerms(UUID token, UUID owner) { return data().acceptedEconomyTerms(token, owner); }
        @Override public BridgeResult reportPolicyStep(UUID token, UUID owner, PolicyStep step) { return persistTerminal(owner, data().reportPolicyStep(token, owner, step)); }
        @Override public BridgeResult removeInputAndComplete(UUID token, UUID owner) { return persistTerminal(owner, data().removeInputAndComplete(token, owner)); }
        @Override public BridgeResult quarantine(UUID token, UUID owner, String reason) { return persistTerminal(owner, data().quarantine(token, owner, reason)); }

        private BridgeResult persistTerminal(UUID owner, BridgeResult result) {
            if (result.code() != BridgeResult.Code.AMBIGUOUS_QUARANTINE) return result;
            VirtualShippingSavedData savedData = data();
            VirtualShippingSavedData.Status status = savedData.status(owner);
            if (status == null || status.state() != VirtualShippingSavedData.State.QUARANTINED) {
                return result;
            }
            MinecraftServer boundServer = server;
            if (boundServer == null) throw new IllegalStateException("POLL_REQUIRED");
            saveDurably(boundServer, savedData);
            return result;
        }
        @Override
        public BridgeResult.PlanningAbortCode abortPlanning(UUID token, String reason) {
            VirtualShippingSavedData savedData = data();
            BridgeResult.PlanningAbortCode result = savedData.abortPlanning(token, reason);
            if (result == BridgeResult.PlanningAbortCode.REQUEUED) {
                MinecraftServer boundServer = server;
                if (boundServer == null) throw new IllegalStateException("POLL_REQUIRED");
                saveDurably(boundServer, savedData);
            }
            return result;
        }
    }

    private static void saveDurably(MinecraftServer server, VirtualShippingSavedData data) {
        Path dataDirectory = server.getWorldPath(LevelResource.ROOT).resolve("data");
        Path target = dataDirectory.resolve(VirtualShippingSavedData.DATA_ID + ".dat");
        Path temporary = null;
        try {
            Files.createDirectories(dataDirectory);
            CompoundTag root = new CompoundTag();
            root.put("data", data.save(new CompoundTag()));
            NbtUtils.addCurrentDataVersion(root);

            temporary = Files.createTempFile(
                    dataDirectory, VirtualShippingSavedData.DATA_ID + "-", ".tmp");
            NbtIo.writeCompressed(root, temporary.toFile());
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            if (!root.equals(NbtIo.readCompressed(temporary.toFile()))) {
                throw new IOException("SavedData verification mismatch");
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
            try (FileChannel channel = FileChannel.open(target, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            data.setDirty(false);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not durably save virtual shipping data", exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The orphaned temporary file is never read as live SavedData.
                }
            }
        }
    }
}
