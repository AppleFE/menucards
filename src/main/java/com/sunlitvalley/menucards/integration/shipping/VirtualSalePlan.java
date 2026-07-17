package com.sunlitvalley.menucards.integration.shipping;

import com.google.gson.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.StringReader;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.*;

/** Fully validated, immutable v2 policy plan. */
public final class VirtualSalePlan {
    public static final int MAX_JSON_BYTES = 65_536;
    public static final int MAX_ENTRIES = 54;
    public static final int MAX_SNBT_BYTES = 8_192;
    public static final int MAX_STRING = 256;
    public static final int MAX_REGISTRY_ID = 128;
    private static final Set<String> ROOT_KEYS = Set.of("version", "removals", "outputs", "receipts", "economy", "steps");
    private static final List<String> REQUIRED_STEPS = List.of("OUTPUT", "DEBT", "BANK");

    public record Removal(int slot, int count) { }
    public record StackEntry(String itemId, String snbt, int count) { }
    public record EconomyTerms(long debt, long debtPaid, String accountId,
            long expectedAccountBalance, long bankDeposit) { }

    private final List<Removal> removals;
    private final List<StackEntry> outputs;
    private final List<StackEntry> receipts;
    private final EconomyTerms economy;

    private VirtualSalePlan(List<Removal> removals, List<StackEntry> outputs,
            List<StackEntry> receipts, EconomyTerms economy) {
        this.removals = List.copyOf(removals);
        this.outputs = List.copyOf(outputs);
        this.receipts = List.copyOf(receipts);
        this.economy = economy;
    }

    public List<Removal> removals() { return removals; }
    public List<StackEntry> outputs() { return outputs; }
    public List<StackEntry> receipts() { return receipts; }
    public EconomyTerms economy() { return economy; }
    public void validateRegistry() throws PlanValidationException {
        for (StackEntry entry : outputs) validateStackEntry(entry, "OUTPUT");
        for (StackEntry entry : receipts) validateStackEntry(entry, "RECEIPT");
    }
    public void validateRemovalsForSnapshot(ItemStack[] snapshot) throws PlanValidationException {
        if (snapshot == null || snapshot.length != CanonicalInputFingerprint.SLOT_COUNT) {
            throw invalid("SNAPSHOT_SIZE");
        }
        int[] counts = new int[snapshot.length];
        int[] maxStackSizes = new int[snapshot.length];
        for (int slot = 0; slot < snapshot.length; slot++) {
            ItemStack stack = snapshot[slot];
            if (stack != null && !stack.isEmpty()) {
                counts[slot] = stack.getCount();
                maxStackSizes[slot] = stack.getMaxStackSize();
            }
        }
        validateRemovals(removals, counts, maxStackSizes);
    }

    public void validateForSnapshot(ItemStack[] snapshot) throws PlanValidationException {
        validateRemovalsForSnapshot(snapshot);
        for (StackEntry entry : outputs) validateStackEntry(entry, "OUTPUT");
        for (StackEntry entry : receipts) validateStackEntry(entry, "RECEIPT");
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.put("Removals", saveRemovals(removals));
        tag.put("Outputs", saveStacks(outputs));
        tag.put("Receipts", saveStacks(receipts));
        tag.put("Economy", saveEconomy(economy));
        return tag;
    }

    public static VirtualSalePlan load(CompoundTag tag) {
        try {
            if (!tag.getAllKeys().equals(Set.of("Removals", "Outputs", "Receipts", "Economy"))) {
                throw new IllegalArgumentException("Invalid persisted plan fields");
            }
            VirtualSalePlan plan = new VirtualSalePlan(
                    loadRemovals(requireCompoundList(tag, "Removals")),
                    loadStacks(requireCompoundList(tag, "Outputs")),
                    loadStacks(requireCompoundList(tag, "Receipts")),
                    loadEconomy(tag.getCompound("Economy")));
            return plan;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    public ItemStack stack(StackEntry entry) {
        ResourceLocation id = ResourceLocation.tryParse(entry.itemId());
        Item item = id == null ? null : ForgeRegistries.ITEMS.getValue(id);
        if (item == null) throw new IllegalArgumentException("OUTPUT_ITEM");
        CompoundTag tag;
        try { tag = TagParser.parseTag(entry.snbt()); } catch (Exception exception) { throw new IllegalArgumentException("OUTPUT_SNBT", exception); }
        ItemStack stack = new ItemStack(item, entry.count());
        stack.setTag(tag);
        return stack;
    }

    private static ListTag saveRemovals(List<Removal> removals) {
        ListTag result = new ListTag();
        for (Removal removal : removals) { CompoundTag tag = new CompoundTag(); tag.putInt("Slot", removal.slot()); tag.putInt("Count", removal.count()); result.add(tag); }
        return result;
    }

    private static ListTag saveStacks(List<StackEntry> entries) {
        ListTag result = new ListTag();
        for (StackEntry entry : entries) { CompoundTag tag = new CompoundTag(); tag.putString("Item", entry.itemId()); tag.putString("Snbt", entry.snbt()); tag.putInt("Count", entry.count()); result.add(tag); }
        return result;
    }
    private static CompoundTag saveEconomy(EconomyTerms economy) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Debt", economy.debt());
        tag.putLong("DebtPaid", economy.debtPaid());
        tag.putString("AccountId", economy.accountId());
        tag.putLong("ExpectedBalance", economy.expectedAccountBalance());
        tag.putLong("BankDeposit", economy.bankDeposit());
        return tag;
    }

    private static EconomyTerms loadEconomy(CompoundTag tag) {
        if (!tag.getAllKeys().equals(Set.of("Debt", "DebtPaid", "AccountId", "ExpectedBalance", "BankDeposit"))
                || !tag.contains("Debt", Tag.TAG_LONG) || !tag.contains("DebtPaid", Tag.TAG_LONG)
                || !tag.contains("AccountId", Tag.TAG_STRING)
                || !tag.contains("ExpectedBalance", Tag.TAG_LONG)
                || !tag.contains("BankDeposit", Tag.TAG_LONG)) {
            throw new IllegalArgumentException("Invalid persisted economy fields");
        }
        try {
            return economy(tag.getLong("Debt"), tag.getLong("DebtPaid"), tag.getString("AccountId"),
                    tag.getLong("ExpectedBalance"), tag.getLong("BankDeposit"));
        } catch (PlanValidationException exception) {
            throw new IllegalArgumentException(exception);
        }
    }


    private static ListTag requireCompoundList(CompoundTag tag, String key) {
        Tag value = tag.get(key);
        if (!(value instanceof ListTag list)
                || list.getElementType() != Tag.TAG_END
                && list.getElementType() != Tag.TAG_COMPOUND) {
            throw new IllegalArgumentException(key + " must be a compound list");
        }
        return list;
    }

    private static List<Removal> loadRemovals(ListTag entries) {
        if (entries.size() > MAX_ENTRIES) throw new IllegalArgumentException();
        List<Removal> result = new ArrayList<>();
        int previous = -1;
        for (Tag value : entries) {
            if (!(value instanceof CompoundTag tag)
                    || !tag.getAllKeys().equals(Set.of("Slot", "Count"))
                    || !tag.contains("Slot", Tag.TAG_INT)
                    || !tag.contains("Count", Tag.TAG_INT)) {
                throw new IllegalArgumentException();
            }
            int slot = tag.getInt("Slot"), count = tag.getInt("Count");
            if (slot < 0 || slot >= CanonicalInputFingerprint.SLOT_COUNT
                    || slot <= previous || count < 1) throw new IllegalArgumentException();
            result.add(new Removal(slot, count));
            previous = slot;
        }
        return result;
    }

    private static List<StackEntry> loadStacks(ListTag entries) {
        List<StackEntry> result = new ArrayList<>();
        if (entries.size() > MAX_ENTRIES) throw new IllegalArgumentException();
        for (Tag value : entries) {
            if (!(value instanceof CompoundTag tag)
                    || !tag.getAllKeys().equals(Set.of("Item", "Snbt", "Count"))
                    || !tag.contains("Item", Tag.TAG_STRING)
                    || !tag.contains("Snbt", Tag.TAG_STRING)
                    || !tag.contains("Count", Tag.TAG_INT)) {
                throw new IllegalArgumentException();
            }
            String item = tag.getString("Item"), snbt = tag.getString("Snbt");
            int count = tag.getInt("Count");
            try {
                validateStackEntryStructure(new StackEntry(item, snbt, count), "OUTPUT");
            } catch (PlanValidationException exception) {
                throw new IllegalArgumentException(exception);
            }
            result.add(new StackEntry(item, snbt, count));
        }
        return result;
    }

    public static VirtualSalePlan parse(String json, ItemStack[] snapshot) throws PlanValidationException {
        if (snapshot == null || snapshot.length != CanonicalInputFingerprint.SLOT_COUNT) {
            throw invalid("SNAPSHOT_SIZE");
        }
        int[] counts = new int[snapshot.length];
        int[] maxStackSizes = new int[snapshot.length];
        for (int slot = 0; slot < snapshot.length; slot++) {
            ItemStack stack = snapshot[slot];
            if (stack != null && !stack.isEmpty()) {
                counts[slot] = stack.getCount();
                maxStackSizes[slot] = stack.getMaxStackSize();
            }
        }
        return parse(json, counts, maxStackSizes);
    }

    static VirtualSalePlan parse(String json, int[] counts, int[] maxStackSizes) throws PlanValidationException {
        if (counts == null || maxStackSizes == null
                || counts.length != CanonicalInputFingerprint.SLOT_COUNT
                || maxStackSizes.length != CanonicalInputFingerprint.SLOT_COUNT) {
            throw invalid("SNAPSHOT_SIZE");
        }
        requireBounded(json, MAX_JSON_BYTES, "PLAN_TOO_LARGE");
        try {
            rejectDuplicateMembers(json);
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) throw invalid("PLAN_ROOT");
            JsonObject root = parsed.getAsJsonObject();
            requireKeys(root, ROOT_KEYS, "PLAN_FIELD");
            if (integer(root, "version", "PLAN_VERSION") != 2) throw invalid("PLAN_VERSION");
            List<Removal> removals = parseRemovals(array(root, "removals", "REMOVALS"), counts, maxStackSizes);
            List<StackEntry> outputs = parseStacks(array(root, "outputs", "OUTPUTS"), "OUTPUT");
            List<StackEntry> receipts = parseStacks(array(root, "receipts", "RECEIPTS"), "RECEIPT");
            EconomyTerms economy = parseEconomy(object(root, "economy", "ECONOMY"));
            parseSteps(array(root, "steps", "STEPS"));
            return new VirtualSalePlan(removals, outputs, receipts, economy);
        } catch (JsonParseException | IllegalStateException exception) {
            throw invalid("PLAN_JSON");
        }
    }

    private static List<Removal> parseRemovals(JsonArray values, int[] counts, int[] maxStackSizes)
            throws PlanValidationException {
        if (values.size() > MAX_ENTRIES) throw invalid("REMOVAL_LIMIT");
        List<Removal> result = new ArrayList<>(values.size());
        int previousSlot = -1;
        for (JsonElement element : values) {
            if (!element.isJsonObject()) throw invalid("REMOVAL_ENTRY");
            JsonObject value = element.getAsJsonObject();
            requireKeys(value, Set.of("slot", "count"), "REMOVAL_FIELD");
            int slot = integer(value, "slot", "REMOVAL_SLOT");
            int count = integer(value, "count", "REMOVAL_COUNT");
            if (slot < 0 || slot >= CanonicalInputFingerprint.SLOT_COUNT || slot <= previousSlot) {
                throw invalid("REMOVAL_SLOT");
            }
            if (counts[slot] <= 0 || count < 1 || count > counts[slot] || count > maxStackSizes[slot]) {
                throw invalid("REMOVAL_COUNT");
            }
            previousSlot = slot;
            result.add(new Removal(slot, count));
        }
        return result;
    }

    private static List<StackEntry> parseStacks(JsonArray values, String prefix) throws PlanValidationException {
        if (values.size() > MAX_ENTRIES) throw invalid(prefix + "_LIMIT");
        List<StackEntry> result = new ArrayList<>(values.size());
        for (JsonElement element : values) {
            if (!element.isJsonObject()) throw invalid(prefix + "_ENTRY");
            JsonObject value = element.getAsJsonObject();
            requireKeys(value, Set.of("item", "snbt", "count"), prefix + "_FIELD");
            String item = string(value, "item", MAX_REGISTRY_ID, prefix + "_ITEM");
            String snbt = string(value, "snbt", MAX_SNBT_BYTES, prefix + "_SNBT");
            int count = integer(value, "count", prefix + "_COUNT");
            StackEntry entry = new StackEntry(item, snbt, count);
            validateStackEntryStructure(entry, prefix);
            result.add(entry);
        }
        return result;
    }

    private static void parseSteps(JsonArray values) throws PlanValidationException {
        if (values.size() != REQUIRED_STEPS.size()) throw invalid("STEP_ORDER");
        for (int index = 0; index < REQUIRED_STEPS.size(); index++) {
            JsonElement value = values.get(index);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || !REQUIRED_STEPS.get(index).equals(value.getAsString())) throw invalid("STEP_ORDER");
        }
    }
    private static EconomyTerms parseEconomy(JsonObject value) throws PlanValidationException {
        requireKeys(value, Set.of("debt", "debtPaid", "accountId", "expectedBalance", "bankDeposit"),
                "ECONOMY_FIELD");
        return economy(longInteger(value, "debt", "ECONOMY_DEBT"),
                longInteger(value, "debtPaid", "ECONOMY_DEBT_PAID"),
                string(value, "accountId", MAX_STRING, "ECONOMY_ACCOUNT"),
                longInteger(value, "expectedBalance", "ECONOMY_BALANCE"),
                longInteger(value, "bankDeposit", "ECONOMY_DEPOSIT"));
    }

    private static EconomyTerms economy(long debt, long debtPaid, String accountId,
            long expectedAccountBalance, long bankDeposit) throws PlanValidationException {
        if (debt < 0 || debtPaid < 0 || debtPaid > debt || expectedAccountBalance < 0
                || bankDeposit < 0) throw invalid("ECONOMY_AMOUNT");
        requireBounded(accountId, MAX_STRING, "ECONOMY_ACCOUNT");
        try {
            if (!UUID.fromString(accountId).toString().equals(accountId)) throw invalid("ECONOMY_ACCOUNT");
        } catch (IllegalArgumentException exception) {
            throw invalid("ECONOMY_ACCOUNT");
        }
        return new EconomyTerms(debt, debtPaid, accountId, expectedAccountBalance, bankDeposit);
    }

    private static JsonObject object(JsonObject object, String key, String reason) throws PlanValidationException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonObject()) throw invalid(reason);
        return value.getAsJsonObject();
    }


    private static JsonArray array(JsonObject object, String key, String reason) throws PlanValidationException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonArray()) throw invalid(reason);
        return value.getAsJsonArray();
    }

    private static int integer(JsonObject object, String key, String reason) throws PlanValidationException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw invalid(reason);
        try {
            BigDecimal number = new BigDecimal(value.getAsString());
            if (number.signum() < 0 || number.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
                throw invalid(reason);
            }
            return number.intValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            throw invalid(reason);
        }
    }
    private static long longInteger(JsonObject object, String key, String reason)
            throws PlanValidationException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw invalid(reason);
        }
        try {
            BigDecimal number = new BigDecimal(value.getAsString());
            if (number.signum() < 0) throw invalid(reason);
            return number.longValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            throw invalid(reason);
        }
    }
    private static String string(JsonObject object, String key, int byteLimit, String reason) throws PlanValidationException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid(reason);
        String result = value.getAsString();
        requireBounded(result, byteLimit, reason);
        if (result.length() > MAX_STRING && byteLimit != MAX_SNBT_BYTES) throw invalid(reason);
        return result;
    }

    private static void requireKeys(JsonObject object, Set<String> permitted, String reason) throws PlanValidationException {
        if (!object.keySet().equals(permitted)) throw invalid(reason);
    }

    private static void validateRemovals(List<Removal> values, int[] counts, int[] maxStackSizes)
            throws PlanValidationException {
        int previous = -1;
        for (Removal removal : values) {
            int slot = removal.slot(), count = removal.count();
            if (slot < 0 || slot >= CanonicalInputFingerprint.SLOT_COUNT || slot <= previous
                    || counts[slot] <= 0 || count < 1 || count > counts[slot]
                    || count > maxStackSizes[slot]) {
                throw invalid("REMOVAL_COUNT");
            }
            previous = slot;
        }
    }

    private static void validateStackEntryStructure(StackEntry entry, String prefix)
            throws PlanValidationException {
        if (entry == null || entry.itemId() == null || entry.snbt() == null
                || entry.count() < 1 || entry.count() > 64) {
            throw invalid(prefix + "_COUNT");
        }
        String item = entry.itemId();
        requireBounded(item, MAX_REGISTRY_ID, prefix + "_ITEM");
        if (item.length() > MAX_STRING) throw invalid(prefix + "_ITEM");
        ResourceLocation id = ResourceLocation.tryParse(item);
        if (id == null || "minecraft:air".equals(id.toString())) throw invalid(prefix + "_ITEM");
        requireBounded(entry.snbt(), MAX_SNBT_BYTES, prefix + "_SNBT");
        try {
            TagParser.parseTag(entry.snbt());
        } catch (Exception exception) {
            throw invalid(prefix + "_SNBT");
        }
    }

    private static void validateStackEntry(StackEntry entry, String prefix)
            throws PlanValidationException {
        validateStackEntryStructure(entry, prefix);
        ResourceLocation id = ResourceLocation.tryParse(entry.itemId());
        if (id == null || !ForgeRegistries.ITEMS.containsKey(id)) throw invalid(prefix + "_ITEM");
        Item item = ForgeRegistries.ITEMS.getValue(id);
        if (item == null) throw invalid(prefix + "_ITEM");
        ItemStack stack = new ItemStack(item, entry.count());
        try {
            stack.setTag(TagParser.parseTag(entry.snbt()));
        } catch (Exception exception) {
            throw invalid(prefix + "_SNBT");
        }
        if (stack.isEmpty()) throw invalid(prefix + "_ITEM");
    }

    private static void rejectDuplicateMembers(String json) throws PlanValidationException {
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            rejectDuplicateMembers(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw invalid("PLAN_JSON");
        } catch (PlanValidationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("PLAN_JSON");
        }
    }

    private static void rejectDuplicateMembers(JsonReader reader) throws java.io.IOException,
            PlanValidationException {
        JsonToken token = reader.peek();
        if (token == JsonToken.BEGIN_OBJECT) {
            reader.beginObject();
            Set<String> names = new HashSet<>();
            while (reader.hasNext()) {
                if (!names.add(reader.nextName())) throw invalid("PLAN_DUPLICATE_FIELD");
                rejectDuplicateMembers(reader);
            }
            reader.endObject();
        } else if (token == JsonToken.BEGIN_ARRAY) {
            reader.beginArray();
            while (reader.hasNext()) rejectDuplicateMembers(reader);
            reader.endArray();
        } else {
            reader.skipValue();
        }
    }
    private static void requireBounded(String value, int byteLimit, String reason) throws PlanValidationException {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length > byteLimit) throw invalid(reason);
    }

    private static PlanValidationException invalid(String reason) { return new PlanValidationException(reason); }

    public static final class PlanValidationException extends Exception {
        private final String reason;
        private PlanValidationException(String reason) { super(reason); this.reason = reason; }
        public String reason() { return reason; }
    }
}
