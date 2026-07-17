package com.sunlitvalley.menucards.integration.shipping;

import java.util.Objects;

/** Result returned to the sole policy scheduler. Details are stable reason codes, never item data. */
public final class BridgeResult {
    public enum Code { SUCCESS, DETERMINISTIC_REQUEUE, AMBIGUOUS_QUARANTINE }
    public enum PlanningAbortCode { REQUEUED, STALE_TOKEN }

    private final Code code;
    private final String reason;

    private BridgeResult(Code code, String reason) {
        this.code = Objects.requireNonNull(code, "code");
        this.reason = bounded(Objects.requireNonNull(reason, "reason"));
    }

    public static BridgeResult success() { return new BridgeResult(Code.SUCCESS, "OK"); }
    public static BridgeResult requeue(String reason) { return new BridgeResult(Code.DETERMINISTIC_REQUEUE, reason); }
    public static BridgeResult quarantine(String reason) { return new BridgeResult(Code.AMBIGUOUS_QUARANTINE, reason); }
    public Code code() { return code; }
    public String reason() { return reason; }
    public boolean succeeded() { return code == Code.SUCCESS; }

    private static String bounded(String value) { return value.length() <= 256 ? value : "RESULT_REASON"; }
}
