function menuCardsUtf8ByteLength(value) {
  let length = 0;
  for (let index = 0; index < value.length; index++) {
    const code = value.charCodeAt(index);
    if (code <= 0x7f) {
      length++;
    } else if (code <= 0x7ff) {
      length += 2;
    } else if (
      code >= 0xd800 &&
      code <= 0xdbff &&
      index + 1 < value.length &&
      value.charCodeAt(index + 1) >= 0xdc00 &&
      value.charCodeAt(index + 1) <= 0xdfff
    ) {
      length += 4;
      index++;
    } else {
      length += 3;
    }
  }
  return length;
}

global.planVirtualShipping = (lease, server) => {
  const input = lease.input();
  if (input.length !== 54) throw new Error("LEASE_SLOT_ABI");
  const requireAmount = (value, reason) => {
    if (!Number.isSafeInteger(value) || value < 0) throw new Error(reason);
    return value;
  };
  const playerId = String(lease.playerId());
  const player = server.players.filter(
    (candidate) => candidate.getUuid().toString() === playerId
  )[0];
  if (!player) throw new Error("OWNER_OFFLINE");
  const attributes = [];
  const stages = [];
  const attributeMapping = [
    "shippingbin:crop_sell_multiplier",
    "shippingbin:wood_sell_multiplier",
    "shippingbin:gem_sell_multiplier",
    "shippingbin:meat_sell_multiplier",
  ];
  let calculatedValue = 0;
  const removals = [];

  attributeMapping.forEach((name) => {
    attributes.push({
      Name: name,
      Base: Number(
        player.nbt.Attributes.filter((attribute) => attribute.Name === name)[0]?.Base
      ),
    });
  });
  [
    "bluegill_meridian",
    "phenomenology_of_treasure",
    "brine_and_punishment",
    "the_quality_of_the_earth",
  ].forEach((stage) => {
    if (player.stages.has(stage)) stages.push(stage);
  });
  const stackItem = (stack) => stack.item || stack.getItem();
  const stackCount = (stack) => (stack.count === undefined ? stack.getCount() : stack.count);
  const stackNbt = (stack) => stack.nbt;
  const stackHasTag = (stack, tag) => Item.of(stack).hasTag(tag);

  for (var slot = 0; slot < 54; slot++) {
    var stack = input[slot];
    var item = stackItem(stack);
    var itemId = String(Item.of(stack).id);
    var sellable =
      global.trades.has(itemId) ||
      ["splendid_slimes:plort", "splendid_slimes:slime_heart"].includes(itemId);
    if (!sellable || stackCount(stack) <= 0) continue;

    var trade = global.trades.get(itemId);
    var nbt = stackNbt(stack);
    if (nbt && ((nbt.slime && nbt.slime.id) || (nbt.plort && nbt.plort.id))) {
      if (nbt.slime) trade = global.trades.get(`${itemId}/${nbt.slime.id}`);
      if (nbt.plort) trade = global.trades.get(`${itemId}/${nbt.plort.id}`);
    }
    if (!trade) continue;

    var qualityValue = Number(nbt?.quality_food?.quality);
    var quality = Number.isInteger(qualityValue) && qualityValue >= 0 && qualityValue <= 3
      ? qualityValue
      : undefined;
    var doubleQuality =
      quality &&
      quality > 0 &&
      stages.includes("the_quality_of_the_earth") &&
      String(trade.multiplier) === "shippingbin:crop_sell_multiplier" &&
      !Item.of(item).hasTag("minecraft:fishes");
    var itemValue = calculateQualityValue(trade.value, quality, doubleQuality);
    if (!Number.isFinite(itemValue) || itemValue < 0) throw new Error("ITEM_VALUE_INVALID");
    if (stages.includes("bluegill_meridian") && itemId === "aquaculture:bluegill") {
      itemValue = calculateQualityValue(666, quality);
    }
    if (
      stages.includes("phenomenology_of_treasure") &&
      (Item.of(item).hasTag("society:artifacts") || Item.of(item).hasTag("society:relics"))
    ) {
      itemValue *= 3;
    }
    if (
      stages.includes("brine_and_punishment") &&
      Item.of(item).hasTag("society:brine_and_punishment")
    ) {
      itemValue *= 2;
    }
    if (!Number.isFinite(itemValue) || itemValue < 0) throw new Error("ITEM_VALUE_INVALID");

    var multiplier = Number(global.getAttributeMultiplier(attributes, trade.multiplier));
    if (!Number.isFinite(multiplier) || multiplier < 0) {
      throw new Error("ATTRIBUTE_MULTIPLIER_INVALID");
    }
    var multiplied = Math.round(itemValue * stackCount(stack) * multiplier);
    requireAmount(multiplied, "STACK_VALUE_INVALID");
    if (multiplied === 0) continue;
    calculatedValue = requireAmount(calculatedValue + multiplied, "SALE_VALUE_INVALID");
    removals.push({ slot: slot, count: stackCount(stack) });
  }

  const debtMatches = (server.persistentData.debts || []).filter(
    (debt) => debt.uuid === playerId
  );
  if (debtMatches.length > 1) throw new Error("DEBT_DUPLICATE");
  const debt = requireAmount(
    Number(debtMatches.length === 0 ? 0 : debtMatches[0].amount),
    "DEBT_INVALID"
  );
  const debtPaid = Math.min(calculatedValue, debt);
  const proceeds = calculatedValue - debtPaid;
  requireAmount(proceeds, "PROCEEDS_INVALID");
  let accountId = playerId;
  const card = input[0];
  if (card && stackHasTag(card, "numismatics:cards")) {
    accountId = String(stackNbt(card).getUUID("AccountID"));
  }
  const account = global.GLOBAL_BANK.getAccount(accountId);
  let accountBalance = 0;
  if (account) {
    accountBalance = Number(account.getBalance());
    if (!Number.isSafeInteger(accountBalance) || accountBalance < 0) {
      throw new Error("BANK_BALANCE_INVALID");
    }
  }
  const bankDeposit = account && accountBalance + proceeds < 2147483000 ? proceeds : 0;
  const outputs = [];
  const receipts = [];

  if (proceeds > 0 && bankDeposit === 0) {
    const coinPlan = calculateCoinsFromValue(proceeds, [], global.coinMap);
    if (!Array.isArray(coinPlan) || coinPlan.length === 0) {
      throw new Error("COIN_PLAN_INVALID");
    }
    let representedValue = 0;
    let plannedCoinStacks = 0;
    coinPlan.forEach((plannedCoin) => {
      const coin = plannedCoin.coin;
      const count = plannedCoin.count;
      requireAmount(count, "COIN_COUNT_INVALID");
      if (count === 0) throw new Error("COIN_COUNT_INVALID");
      const denomination = global.coinMap.filter(
        (entry) => String(entry.coin) === String(coin)
      );
      if (denomination.length !== 1) throw new Error("COIN_DENOMINATION_INVALID");
      const coinValue = requireAmount(
        Number(denomination[0].value),
        "COIN_DENOMINATION_INVALID"
      );
      if (coinValue === 0) throw new Error("COIN_DENOMINATION_INVALID");
      representedValue = requireAmount(
        representedValue + coinValue * count,
        "COIN_VALUE_INVALID"
      );
      plannedCoinStacks = requireAmount(
        plannedCoinStacks + Math.ceil(count / 64),
        "PLAN_OUTPUT_LIMIT"
      );
    });
    if (representedValue !== proceeds || plannedCoinStacks > 54) {
      throw new Error(representedValue !== proceeds ? "COIN_VALUE_MISMATCH" : "PLAN_OUTPUT_LIMIT");
    }
    coinPlan.forEach((plannedCoin) => {
      const coin = plannedCoin.coin;
      const count = plannedCoin.count;
      for (let remaining = count; remaining > 0; remaining -= 64) {
        outputs.push({ item: String(coin), snbt: "{}", count: Math.min(64, remaining) });
      }
    });
  }
  if (debtPaid > 0) {
    var debtReceipt = global.getNotePaperItem(
      global.translatableWithFallback(
        "society.hospital_receipt.author",
        "Sunlit Valley Hospital"
      ).getString(),
      Text.translatable(
        "society.shipping_bin.debt_paid_note",
        player.username,
        global.formatPrice(debtPaid.toFixed()),
        global.formatPrice(debt.toFixed())
      ).toJson(),
      global.translatableWithFallback(
        "society.shipping_bin.debt_paid_note.title",
        "Debt Payment Receipt"
      ).getString()
    );
    receipts.push({
      item: String(debtReceipt.item.id),
      snbt: debtReceipt.nbt ? String(debtReceipt.nbt) : "{}",
      count: 1,
    });
  }

  if (outputs.length > 54 || receipts.length > 54) {
    throw new Error("PLAN_OUTPUT_LIMIT");
  }
  const plan = {
    version: 2,
    removals: removals,
    outputs: outputs,
    receipts: receipts,
    economy: {
      debt: debt,
      debtPaid: debtPaid,
      accountId: accountId,
      expectedBalance: accountBalance,
      bankDeposit: bankDeposit,
    },
    steps: ["OUTPUT", "DEBT", "BANK"],
  };
  const json = JSON.stringify(plan);
  if (menuCardsUtf8ByteLength(json) > 65536) {
    throw new Error("PLAN_SIZE_LIMIT");
  }
  return { json: json };
};
