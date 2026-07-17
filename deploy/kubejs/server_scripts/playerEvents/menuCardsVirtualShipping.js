const VirtualShippingBridge = Java.loadClass(
  "com.sunlitvalley.menucards.integration.shipping.VirtualShippingBridge"
);
if (typeof global.planVirtualShipping !== "function") {
  throw new Error("MENUCARDS_VIRTUAL_SHIPPING_PLANNER_MISSING");
}

const virtualShippingResultCode = (result) =>
  result == null ? "NULL_RESULT" : String(result.code());
const virtualShippingResultSucceeded = (result) =>
  virtualShippingResultCode(result) === "SUCCESS";
const virtualShippingAbortSucceeded = (result) => String(result) === "REQUEUED";
const virtualShippingQuarantineSucceeded = (result, reason) =>
  result != null &&
  String(result.code()) === "AMBIGUOUS_QUARANTINE" &&
  String(result.reason()) === reason;

const virtualShippingAbort = (bridge, lease, reason) => {
  try {
    var terminal = bridge.abortPlanning(lease.token(), reason);
    if (!virtualShippingAbortSucceeded(terminal)) {
      console.error(`[MENUCARDS] Virtual shipping abort failed: ${String(terminal)}`);
    }
  } catch (error) {
    console.error(`[MENUCARDS] Virtual shipping abort persistence failed: ${String(error)}`);
  }
};

const virtualShippingQuarantine = (bridge, lease, reason) => {
  try {
    var terminal = bridge.quarantine(lease, reason);
    if (!virtualShippingQuarantineSucceeded(terminal, reason)) {
      console.error(
        `[MENUCARDS] Virtual shipping quarantine failed: ${virtualShippingResultCode(terminal)}`
      );
    }
  } catch (error) {
    console.error(`[MENUCARDS] Virtual shipping quarantine persistence failed: ${String(error)}`);
  }
};

const virtualShippingEconomyTerms = (terms) => {
  if (!terms) throw new Error("ECONOMY_TERMS_MISSING");
  var debt = Number(terms.debt());
  var debtPaid = Number(terms.debtPaid());
  var expectedAccountBalance = Number(terms.expectedAccountBalance());
  var bankDeposit = Number(terms.bankDeposit());
  var accountId = String(terms.accountId());
  if (
    !Number.isSafeInteger(debt) ||
    debt < 0 ||
    !Number.isSafeInteger(debtPaid) ||
    debtPaid < 0 ||
    debtPaid > debt ||
    !Number.isSafeInteger(expectedAccountBalance) ||
    expectedAccountBalance < 0 ||
    !Number.isSafeInteger(bankDeposit) ||
    bankDeposit < 0 ||
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(accountId)
  ) {
    throw new Error("ECONOMY_TERMS_INVALID");
  }
};

const virtualShippingDebt = (server, playerId, terms, beforeMutation) => {
  var matches = (server.persistentData.debts || []).filter(
    (debt) => debt.uuid === playerId
  );
  if (matches.length > 1) throw new Error("DEBT_DUPLICATE");
  var currentAmount = Number(matches.length === 0 ? 0 : matches[0].amount);
  if (!Number.isSafeInteger(currentAmount) || currentAmount < 0) {
    throw new Error("DEBT_INVALID");
  }
  var debt = Number(terms.debt());
  var debtPaid = Number(terms.debtPaid());
  if (currentAmount !== debt) throw new Error("DEBT_CHANGED");
  if (debtPaid <= 0) return false;
  beforeMutation();
  global.setDebt(server, playerId, debt - debtPaid);
  return true;
};

const virtualShippingBank = (terms, beforeMutation) => {
  var bankDeposit = Number(terms.bankDeposit());
  if (bankDeposit <= 0) return false;
  var account = global.GLOBAL_BANK.getAccount(String(terms.accountId()));
  if (!account) throw new Error("BANK_CHANGED");
  var balance = Number(account.getBalance());
  if (
    !Number.isSafeInteger(balance) ||
    balance < 0 ||
    balance !== Number(terms.expectedAccountBalance()) ||
    balance + bankDeposit >= 2147483000
  ) {
    throw new Error("BANK_CHANGED");
  }
  beforeMutation();
  account.deposit(bankDeposit);
  return true;
};

const virtualShippingCompensate = (
  server,
  playerId,
  terms,
  debtMutated,
  bankMutated
) => {
  if (bankMutated) {
    var expectedBalance = Number(terms.expectedAccountBalance());
    var bankDeposit = Number(terms.bankDeposit());
    var account = global.GLOBAL_BANK.getAccount(String(terms.accountId()));
    if (!account) {
      throw new Error("BANK_COMPENSATION_FAILED");
    }
    var currentBalance = Number(account.getBalance());
    if (currentBalance === expectedBalance + bankDeposit) {
      account.setBalance(expectedBalance);
      if (Number(account.getBalance()) !== expectedBalance) {
        throw new Error("BANK_COMPENSATION_FAILED");
      }
    } else if (currentBalance !== expectedBalance) {
      throw new Error("BANK_COMPENSATION_FAILED");
    }
  }
  if (debtMutated) {
    var debt = Number(terms.debt());
    var debtPaid = Number(terms.debtPaid());
    var matches = (server.persistentData.debts || []).filter(
      (entry) => entry.uuid === playerId
    );
    if (matches.length > 1) {
      throw new Error("DEBT_COMPENSATION_FAILED");
    }
    var currentDebt = Number(matches.length === 0 ? 0 : matches[0].amount);
    if (currentDebt === debt - debtPaid) {
      global.setDebt(server, playerId, debt);
      matches = (server.persistentData.debts || []).filter(
        (entry) => entry.uuid === playerId
      );
      if (
        matches.length > 1 ||
        Number(matches.length === 0 ? 0 : matches[0].amount) !== debt
      ) {
        throw new Error("DEBT_COMPENSATION_FAILED");
      }
    } else if (currentDebt !== debt) {
      throw new Error("DEBT_COMPENSATION_FAILED");
    }
  }
};

console.info("[MENUCARDS] Virtual shipping scheduler ABI inventory=54");
console.info("[MENUCARDS] menucards_virtual_shipping_scheduler_registered");

ServerEvents.tick((event) => {
  var server = event.server;
  var bridge = VirtualShippingBridge.instance();
  var lease = bridge.pollDue(server);
  if (!lease) return;

  var phase = "PLANNING";
  var economy = null;
  var debtMutated = false;
  var bankMutated = false;
  var inputCompleted = false;
  try {
    var sale = global.planVirtualShipping(lease, server);
    var submitted = bridge.submitPlan(lease, sale.json);
    if (!virtualShippingResultSucceeded(submitted)) {
      virtualShippingAbort(
        bridge,
        lease,
        `PLAN_${virtualShippingResultCode(submitted)}`
      );
      return;
    }
    var applying = bridge.beginApplying(lease);
    if (!virtualShippingResultSucceeded(applying)) {
      virtualShippingAbort(
        bridge,
        lease,
        `APPLYING_${virtualShippingResultCode(applying)}`
      );
      return;
    }
    phase = "OUTPUT";
    economy = bridge.acceptedEconomyTerms(lease);
    virtualShippingEconomyTerms(economy);

    var outputApplied = bridge.applyPlannedOutput(lease);
    if (!virtualShippingResultSucceeded(outputApplied)) {
      throw new Error(`OUTPUT_${virtualShippingResultCode(outputApplied)}`);
    }
    var outputReported = bridge.reportPolicyStep(lease, "OUTPUT");
    if (!virtualShippingResultSucceeded(outputReported)) {
      throw new Error(`OUTPUT_REPORT_${virtualShippingResultCode(outputReported)}`);
    }
    phase = "DEBT";
    virtualShippingDebt(server, String(lease.playerId()), economy, () => {
      debtMutated = true;
    });
    var debtReported = bridge.reportPolicyStep(lease, "DEBT");
    if (!virtualShippingResultSucceeded(debtReported)) {
      throw new Error(`DEBT_REPORT_${virtualShippingResultCode(debtReported)}`);
    }
    phase = "BANK";
    virtualShippingBank(economy, () => {
      bankMutated = true;
    });
    var bankReported = bridge.reportPolicyStep(lease, "BANK");
    if (!virtualShippingResultSucceeded(bankReported)) {
      throw new Error(`BANK_REPORT_${virtualShippingResultCode(bankReported)}`);
    }
    phase = "INPUT";
    var completed = bridge.removeInputAndComplete(lease);
    if (!virtualShippingResultSucceeded(completed)) {
      throw new Error(`INPUT_${virtualShippingResultCode(completed)}`);
    }
    inputCompleted = true;
  } catch (error) {
    console.error(`[MENUCARDS] Virtual shipping ${phase} failure: ${String(error)}`);
    var rawReason = String((error && error.message) || error || "UNKNOWN")
      .toUpperCase()
      .replace(/[^A-Z0-9_]/g, "_")
      .slice(0, 96);
    var reason = `${phase}_${rawReason || "UNKNOWN"}`;
    if (phase === "PLANNING") {
      virtualShippingAbort(bridge, lease, reason);
      return;
    }
    if (!inputCompleted && (debtMutated || bankMutated)) {
      try {
        virtualShippingCompensate(
          server,
          String(lease.playerId()),
          economy,
          debtMutated,
          bankMutated
        );
      } catch (compensationError) {
        reason = `${phase}_COMPENSATION_FAILED`;
        console.error(
          `[MENUCARDS] Virtual shipping ${reason} manual reconciliation required: ${String(compensationError)}`
        );
      }
    }
    virtualShippingQuarantine(bridge, lease, reason);
  }
});
