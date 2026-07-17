package com.sunlitvalley.menucards.integration.shipping;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VirtualSalePlanTest {
    @Test
    void acceptsAFullRemovalFromTheTwentySeventhInputSlot() throws Exception {
        VirtualSalePlan plan = parse(planJson(26, 4), 4);

        assertEquals(1, plan.removals().size());
        assertEquals(new VirtualSalePlan.Removal(26, 4), plan.removals().get(0));
    }

    @Test
    void acceptsAFullRemovalFromTheFirstInputSlot() throws Exception {
        int[] counts = new int[CanonicalInputFingerprint.SLOT_COUNT];
        int[] maxStackSizes = new int[CanonicalInputFingerprint.SLOT_COUNT];
        counts[0] = 4;
        maxStackSizes[0] = 64;

        VirtualSalePlan plan = VirtualSalePlan.parse(planJson(0, 4), counts, maxStackSizes);

        assertEquals(1, plan.removals().size());
        assertEquals(new VirtualSalePlan.Removal(0, 4), plan.removals().get(0));
    }

    @Test
    void rejectsUnknownTopLevelPlanFields() {
        String json = planJson(26, 1).replace("\"steps\":", "\"unexpected\":true,\"steps\":");

        assertInvalid(json, 1, "PLAN_FIELD");
    }

    @Test
    void rejectsOutOfOrderRequiredPolicySteps() {
        String json = planJson(26, 1).replace(
                "[\"OUTPUT\",\"DEBT\",\"BANK\"]",
                "[\"DEBT\",\"OUTPUT\",\"BANK\"]");

        assertInvalid(json, 1, "STEP_ORDER");
    }

    @Test
    void rejectsRemovalBeyondTheTwentySevenSlotSnapshot() {
        assertInvalid(planJson(27, 1), 1, "REMOVAL_SLOT");
    }

    @Test
    void rejectsRemovalCountAboveTheCapturedSnapshotCount() {
        assertInvalid(planJson(26, 5), 4, "REMOVAL_COUNT");
    }

    @Test
    void rejectsDuplicateRemovalSlots() {
        String json = "{\"version\":2,\"removals\":[{\"slot\":26,\"count\":1},"
                + "{\"slot\":26,\"count\":1}],\"outputs\":[],\"receipts\":[],"
                + "\"economy\":" + economyJson() + ",\"steps\":[\"OUTPUT\",\"DEBT\",\"BANK\"]}";
        assertInvalid(json, 2, "REMOVAL_SLOT");
    }

    @Test
    void rejectsMissingRequiredFields() {
        String json = planJson(26, 1).replace(",\"receipts\":[]", "");
        assertInvalid(json, 1, "PLAN_FIELD");
    }

    @Test
    void rejectsFractionalRemovalCounts() {
        String json = planJson(26, 1).replace("\"count\":1", "\"count\":1.5");
        assertInvalid(json, 2, "REMOVAL_COUNT");
    }

    @Test
    void rejectsOutputStackCountsAboveVanillaLimit() {
        String json = planJson(26, 1).replace("\"outputs\":[]",
                "\"outputs\":[{\"item\":\"minecraft:stone\",\"snbt\":\"{}\",\"count\":65}]");
        assertInvalid(json, 1, "OUTPUT_COUNT");
    }

    @Test
    void rejectsExtremePositiveExponentWithoutExpansion() {
        String json = planJson(26, 1).replace("\"outputs\":[]",
                "\"outputs\":[{\"item\":\"minecraft:stone\",\"snbt\":\"{}\",\"count\":1e500000000}]");

        assertInvalid(json, 1, "OUTPUT_COUNT");
    }

    @Test
    void persistsAcceptedEconomyTermsInThePlan() throws Exception {
        VirtualSalePlan plan = parse(planJson(26, 1), 1);

        assertEquals(new VirtualSalePlan.EconomyTerms(
                500L, 125L, "123e4567-e89b-12d3-a456-426614174000", 900L, 375L),
                plan.economy());
        assertEquals(plan.economy(), VirtualSalePlan.load(plan.save()).economy());
    }

    @Test
    void rejectsDebtPaymentAboveTheAcceptedDebt() {
        String json = planJson(26, 1).replace("\"debtPaid\":125", "\"debtPaid\":501");

        assertInvalid(json, 1, "ECONOMY_AMOUNT");
    }

    @Test
    void rejectsNonCanonicalBankAccountIdentity() {
        String json = planJson(26, 1).replace(
                "123e4567-e89b-12d3-a456-426614174000",
                "123E4567-E89B-12D3-A456-426614174000");

        assertInvalid(json, 1, "ECONOMY_ACCOUNT");
    }

    @Test
    void rejectsLongEconomyOverflow() {
        String json = planJson(26, 1).replace("\"expectedBalance\":900",
                "\"expectedBalance\":9223372036854775808");

        assertInvalid(json, 1, "ECONOMY_BALANCE");
    }

    @Test
    void rejectsDuplicateEconomyFields() {
        String json = planJson(26, 1).replace("\"bankDeposit\":375",
                "\"bankDeposit\":375,\"bankDeposit\":375");

        assertInvalid(json, 1, "PLAN_DUPLICATE_FIELD");
    }

    @Test
    void rejectsInvalidOutputSnbt() {
        String json = planJson(26, 1).replace("\"outputs\":[]",
                "\"outputs\":[{\"item\":\"minecraft:stone\",\"snbt\":\"{\",\"count\":1}]");
        assertInvalid(json, 1, "OUTPUT_SNBT");
    }

    @Test
    void enforcesJsonLimitByUtf8Bytes() throws Exception {
        String multibyteSnbt = "{text:\"" + "가".repeat(100) + "\"}";
        String base = planWithOutputSnbt(multibyteSnbt);
        int padding = VirtualSalePlan.MAX_JSON_BYTES
                - base.getBytes(StandardCharsets.UTF_8).length;
        String exactlyAtLimit = base + " ".repeat(padding);

        assertDoesNotThrow(() -> parse(exactlyAtLimit, 1));
        assertInvalid(exactlyAtLimit + " ", 1, "PLAN_TOO_LARGE");
    }

    @Test
    void enforcesSnbtLimitByUtf8Bytes() {
        String validSnbt = "{text:\"" + "가".repeat(2700) + "\"}";
        String oversizedSnbt = "{text:\"" + "가".repeat(2800) + "\"}";

        assertDoesNotThrow((Executable) () -> parse(planWithOutputSnbt(validSnbt), 1));
        assertInvalid(planWithOutputSnbt(oversizedSnbt), 1, "OUTPUT_SNBT");
    }

    private static VirtualSalePlan parse(String json, int finalSlotCount)
            throws VirtualSalePlan.PlanValidationException {
        int[] counts = new int[CanonicalInputFingerprint.SLOT_COUNT];
        int[] maxStackSizes = new int[CanonicalInputFingerprint.SLOT_COUNT];
        counts[counts.length - 1] = finalSlotCount;
        maxStackSizes[maxStackSizes.length - 1] = 64;
        return VirtualSalePlan.parse(json, counts, maxStackSizes);
    }

    private static String planJson(int slot, int count) {
        return "{\"version\":2,\"removals\":[{\"slot\":" + slot + ",\"count\":" + count
                + "}],\"outputs\":[],\"receipts\":[],\"economy\":" + economyJson()
                + ",\"steps\":[\"OUTPUT\",\"DEBT\",\"BANK\"]}";
    }

    private static String economyJson() {
        return "{\"debt\":500,\"debtPaid\":125,"
                + "\"accountId\":\"123e4567-e89b-12d3-a456-426614174000\","
                + "\"expectedBalance\":900,\"bankDeposit\":375}";
    }

    private static String planWithOutputSnbt(String snbt) {
        return planJson(26, 1).replace("\"outputs\":[]",
                "\"outputs\":[{\"item\":\"minecraft:stone\",\"snbt\":"
                        + new com.google.gson.JsonPrimitive(snbt)
                        + ",\"count\":1}]");
    }

    private static void assertInvalid(String json, int finalSlotCount, String reason) {
        VirtualSalePlan.PlanValidationException exception = assertThrows(
                VirtualSalePlan.PlanValidationException.class,
                () -> parse(json, finalSlotCount)
        );
        assertEquals(reason, exception.reason());
    }
}
