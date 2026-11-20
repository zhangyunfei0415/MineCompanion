package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.GameType;

/** Processes one item through a real vanilla menu, with one server-confirmed write at a time. */
final class VanillaGearJob extends Actions.Job {
  private enum Operation { ENCHANT, RENAME, REPAIR }
  private enum Phase { LOAD, OFFER, ENCHANT_RESULT, TAKE, VERIFY }
  private record Click(int slot, int button, ClickType type) {}

  private final Operation operation;
  private final String item, material, name;
  private final int inventorySlot, tier, maxCost;
  private final BlockPos position;
  private final long deadline = System.currentTimeMillis() + 90_000;
  private final ArrayDeque<Click> clicks = new ArrayDeque<>();
  private AbstractContainerMenu menu;
  private Object screen;
  private long revision, experienceBefore;
  private Phase phase = Phase.LOAD;
  private int waiting, offerWait, cost, levelBefore, originalMaterialCount, previousProductCount;
  private ItemStack original = ItemStack.EMPTY, product = ItemStack.EMPTY, expectedCursor;
  private ItemStack repairMaterial = ItemStack.EMPTY;
  private List<ItemStack> expected;
  private boolean opening, renameSent;

  VanillaGearJob(boolean enchanting, JsonObject args, Consumer<String> done) {
    super(done);
    var mc = mc();
    if (mc.level == null || mc.player == null || mc.gameMode == null)
      throw new IllegalArgumentException("[WORLD_REQUIRED] 请先进入世界");
    if (mc.gameMode.getPlayerMode() != GameType.SURVIVAL)
      throw new IllegalArgumentException("[MODE_REQUIRED] 附魔与铁砧核验需要生存模式");
    operation = enchanting ? Operation.ENCHANT : switch (Agent.str(args, "operation", "")) {
      case "rename" -> Operation.RENAME;
      case "repair" -> Operation.REPAIR;
      default -> throw new IllegalArgumentException("[INVALID_ARGUMENT] operation=rename/repair");
    };
    item = exactItem(args, "item");
    inventorySlot = SkillCatalog.bounded(args, "inventory_slot", -1, -1, 35);
    try {
      tier = enchanting ? SkillCatalog.bounded(args, "tier", 1, 1, 3) : 0;
    } catch (IllegalArgumentException invalidTier) {
      throw new IllegalArgumentException("[INVALID_ARGUMENT] tier 必须是 1..3 的整数");
    }
    maxCost = enchanting ? tier : SkillCatalog.bounded(args, "max_cost", 39, 1, 39);
    material = operation == Operation.REPAIR ? exactItem(args, "material") : "";
    name = operation == Operation.RENAME ? SkillCatalog.required(args, "name") : "";
    if (name.length() > 50 || name.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("[INVALID_ARGUMENT] 铁砧名称最多50字符且不能含控制字符");
    boolean coordinates = args.has("x") || args.has("y") || args.has("z");
    if (coordinates && !(args.has("x") && args.has("y") && args.has("z")))
      throw new IllegalArgumentException("[INVALID_ARGUMENT] 需要工作站完整坐标");
    if (coordinates && args.has("place"))
      throw new IllegalArgumentException("[INVALID_ARGUMENT] 请选择地点名或坐标");
    var location = new JsonObject();
    if (coordinates) {
      for (String key : List.of("x", "y", "z")) location.addProperty(key,
          SkillCatalog.bounded(args, key, 0, -30_000_000, 30_000_000));
    } else if (args.has("place")) location.addProperty("place", SkillCatalog.required(args, "place"));
    position = location.size() == 0 ? null : Actions.target(location);
  }

  private static String exactItem(JsonObject args, String key) {
    String value = SkillCatalog.required(args, key);
    if (!value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+(?:#sha256:[a-f0-9]{64})?"))
      throw new IllegalArgumentException("[INVALID_ARGUMENT] " + key + "须为完整物品ID或指纹");
    return value;
  }

  private static boolean matchesItem(ItemStack stack, String item) {
    if (stack.isEmpty()) return false;
    return item.contains("#")
        ? WorldSense.fingerprint(stack).equals(item)
        : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(item);
  }

  @Override void tick() {
    Actions.status = "原版装备加工 · " + operation.name() + " · " + phase.name();
    if (System.currentTimeMillis() >= deadline) {
      result("TIME_BUDGET", "达到等待预算，保留菜单和已核实状态");
      return;
    }
    if (menu == null) {
      connectMenu();
      return;
    }
    if (mc().screen != screen || mc().player.containerMenu != menu) {
      result("STALE_VIEW", "工作台界面已经改变，请核对现场");
      return;
    }
    if (MenuContext.revision() <= revision || !MenuContext.synchronizedNow()) {
      if (++waiting > 160) result("MENU_NOT_SYNCED", "未取得新的服务器菜单快照");
      return;
    }
    if (expected != null) {
      if (!matchesClick()) {
        result("MENU_NOT_SYNCED", "收到的槽位或鼠标状态与预测不一致，请核对现场");
        return;
      }
      expected = null;
    }
    if (!clicks.isEmpty()) {
      var click = clicks.removeFirst();
      applyClick(click.slot(), click.button(), click.type());
      return;
    }
    switch (phase) {
      case LOAD -> prepare();
      case OFFER -> {
        if (operation == Operation.ENCHANT) enchant();
        else anvil();
      }
      case ENCHANT_RESULT -> verifyEnchantment();
      case TAKE -> take();
      case VERIFY -> verifyOutput();
    }
  }

  private void connectMenu() {
    var mc = mc();
    var actual = mc.player.containerMenu;
    if (mc.screen != null) {
      boolean supported = operation == Operation.ENCHANT
          ? actual instanceof EnchantmentMenu && mc.screen instanceof EnchantmentScreen
          : actual instanceof AnvilMenu && mc.screen instanceof AnvilScreen;
      if (!supported || position != null && !MenuContext.matches(position)) {
        result("SCREEN_BLOCKING", "当前菜单类型或来源与指定原版工作站不一致或无法核实");
        return;
      }
      menu = actual;
      screen = mc.screen;
      refresh();
    } else if (position == null || opening) {
      result("NO_STATION", "需要已打开的原版工作站或指定工作站位置");
    } else if (!world.hasChunkAt(position)) {
      result("UNLOADED_TARGET", "指定工作站未加载");
    } else {
      var block = world.getBlockState(position);
      if (operation == Operation.ENCHANT ? !block.is(Blocks.ENCHANTING_TABLE)
          : !(block.getBlock() instanceof AnvilBlock)) {
        result("WRONG_STATION", "指定位置不是匹配的原版工作站");
        return;
      }
      opening = true;
      var args = new JsonObject();
      args.addProperty("x", position.getX());
      args.addProperty("y", position.getY());
      args.addProperty("z", position.getZ());
      child("open_container", args, r -> {
        if (Rules.actionFailed(r)) result("WRONG_STATION", r);
      });
    }
  }

  private void prepare() {
    if (!menu.getCarried().isEmpty() || menu.slots.subList(0, firstInventory()).stream().anyMatch(Slot::hasItem)) {
      result("STATION_OCCUPIED", "请使用空工作站并收好鼠标物品");
      return;
    }
    int source = -1;
    var matchingSlots = new java.util.ArrayList<Integer>();
    for (int i = firstInventory(); i < menu.slots.size(); i++) {
      var slot = menu.getSlot(i);
      if (!matchesItem(slot.getItem(), item)) continue;
      matchingSlots.add(slot.getContainerSlot());
      if (inventorySlot >= 0 && slot.getContainerSlot() != inventorySlot) continue;
      if (source >= 0 && !ItemStack.isSameItemSameTags(menu.getSlot(source).getItem(), slot.getItem())) {
        result("AMBIGUOUS_ITEM", "背包有多个变体，请指定准确指纹或inventory_slot");
        return;
      }
      if (source < 0) source = i;
    }
    if (source < 0) {
      if (matchingSlots.isEmpty()) result("MISSING_MATERIAL", "背包里找不到待加工物品");
      else result("INVENTORY_SLOT_MISMATCH",
          "指定槽 " + inventorySlot + " 没有所需物品，候选背包槽：" + matchingSlots);
      return;
    }
    original = menu.getSlot(source).getItem().copyWithCount(1);
    if (operation == Operation.ENCHANT && original.isEnchanted()) {
      result("ALREADY_ENCHANTED", "物品已有附魔，附魔台无法再次加工");
      return;
    }
    if (operation == Operation.ENCHANT && !original.isEnchantable()
        || operation == Operation.REPAIR && !original.isDamaged()) {
      result("ITEM_NOT_ELIGIBLE", "目标须为可附魔物品或已损坏的可修理物品");
      return;
    }
    if (operation == Operation.RENAME && original.hasCustomHoverName()
        && original.getHoverName().getString().equals(name)) {
      result("ALREADY_NAMED", "物品已经是指定名称");
      return;
    }
    if (mc().player.experienceLevel < (operation == Operation.ENCHANT ? tier : 1)) {
      result("LEVEL_REQUIRED", "当前等级不足以开始加工");
      return;
    }
    move(source, 0, 1);
    if (operation != Operation.RENAME) {
      int remaining = operation == Operation.ENCHANT ? tier : 1;
      for (int i = firstInventory(); i < menu.slots.size() && remaining > 0; i++) {
        if (source == i) continue;
        var stack = menu.getSlot(i).getItem();
        boolean matches = operation == Operation.ENCHANT
            ? stack.is(Items.LAPIS_LAZULI) && !stack.hasTag()
            : matchesItem(stack, material)
                && (!stack.hasTag() || material.contains("#sha256:"));
        if (!matches) continue;
        int amount = Math.min(stack.getCount(), remaining);
        if (operation == Operation.REPAIR) repairMaterial = stack.copyWithCount(1);
        move(i, 1, amount);
        remaining -= amount;
      }
      if (remaining != 0) {
        result("MISSING_MATERIAL", operation == Operation.ENCHANT ? "青金石不足" : "找不到指定修理材料");
        return;
      }
    }
    phase = Phase.OFFER;
  }

  private void enchant() {
    var enchant = (EnchantmentMenu) menu;
    int required = enchant.costs[tier - 1];
    if (required <= 0) {
      waitOffer("ENCHANT_UNAVAILABLE", "所选档位不可用");
      return;
    }
    if (mc().player.experienceLevel < Math.max(required, tier)
        || menu.getSlot(1).getItem().getCount() < tier) {
      result("ENCHANT_REQUIREMENTS", "等级或青金石不足，档位要求等级 " + required);
      return;
    }
    levelBefore = mc().player.experienceLevel;
    experienceBefore = ClientEvidence.experienceRevision;
    originalMaterialCount = menu.getSlot(1).getItem().getCount();
    cost = tier;
    mc().gameMode.handleInventoryButtonClick(menu.containerId, tier - 1);
    GuiContext.invalidate();
    phase = Phase.ENCHANT_RESULT;
    offerWait = 0;
    refresh();
  }

  private void verifyEnchantment() {
    var output = menu.getSlot(0).getItem();
    boolean sameBase = output.is(original.getItem())
        || original.is(Items.BOOK) && output.is(Items.ENCHANTED_BOOK);
    if (!sameBase || EnchantmentHelper.getEnchantments(output).isEmpty()
        || originalMaterialCount - menu.getSlot(1).getItem().getCount() != tier) {
      waitOffer("ENCHANT_UNCONFIRMED", "未核实实际附魔和青金石消耗");
      return;
    }
    product = output.copy();
    phase = Phase.TAKE;
  }

  private void anvil() {
    if (!anvilInputsMatch()) {
      result("INPUT_CHANGED", "铁砧投入发生变化");
      return;
    }
    if (operation == Operation.RENAME && !renameSent) {
      var boxes = mc().screen.children().stream().filter(EditBox.class::isInstance)
          .map(EditBox.class::cast).toList();
      if (boxes.size() != 1) {
        result("RENAME_FIELD_UNAVAILABLE", "当前铁砧名称控件不唯一");
        return;
      }
      boxes.get(0).setValue(name);
      if (!boxes.get(0).getValue().equals(name)) {
        result("RENAME_FIELD_UNAVAILABLE", "当前控件没有接受完整名称");
        return;
      }
      renameSent = true;
      GuiContext.invalidate();
      refresh();
      return;
    }
    var output = menu.getSlot(2).getItem();
    cost = ((AnvilMenu) menu).getCost();
    boolean wanted = output.getCount() == 1 && output.is(original.getItem())
        && (operation == Operation.RENAME
            ? output.hasCustomHoverName() && name.equals(output.getHoverName().getString())
            : output.getDamageValue() < original.getDamageValue());
    if (cost < 1 || !wanted) {
      waitOffer("ANVIL_OUTPUT_UNAVAILABLE", "未观察到符合目标的铁砧产物");
      return;
    }
    levelBefore = mc().player.experienceLevel;
    if (cost > maxCost || cost > levelBefore) {
      result("ANVIL_COST_EXCEEDED", "铁砧报价超出指定上限或当前等级");
      return;
    }
    product = output.copy();
    phase = Phase.TAKE;
  }

  private void take() {
    int outputSlot = operation == Operation.ENCHANT ? 0 : 2;
    if (!ItemStack.matches(product, menu.getSlot(outputSlot).getItem())
        || !menu.getCarried().isEmpty()) {
      result("OUTPUT_CHANGED", "取物前产物或鼠标状态已变化");
      return;
    }
    if (operation != Operation.ENCHANT) {
      if (!anvilInputsMatch() || cost != ((AnvilMenu) menu).getCost()
          || mc().player.experienceLevel < cost) {
        result("ANVIL_OFFER_CHANGED", "取物前材料或等级报价已变化");
        return;
      }
      levelBefore = mc().player.experienceLevel;
      experienceBefore = ClientEvidence.experienceRevision;
    }
    if (menu.slots.subList(firstInventory(), menu.slots.size()).stream()
        .noneMatch(s -> !s.hasItem() || ItemStack.isSameItemSameTags(product, s.getItem())
            && s.getItem().getCount() < s.getMaxStackSize(product))) {
      result("NO_CAPACITY", "背包无法容纳当前产物");
      return;
    }
    previousProductCount = inventoryTotal(product);
    phase = Phase.VERIFY;
    offerWait = 0;
    applyClick(outputSlot, 0, ClickType.QUICK_MOVE);
  }

  private void verifyOutput() {
    int outputSlot = operation == Operation.ENCHANT ? 0 : 2;
    boolean spent = ClientEvidence.experienceRevision > experienceBefore
        && levelBefore - ClientEvidence.experienceLevel == cost
        && ClientEvidence.experienceLevel == mc().player.experienceLevel;
    boolean noInput = !menu.getSlot(0).hasItem() && !menu.getSlot(1).hasItem();
    if (!spent || !noInput || menu.getSlot(outputSlot).hasItem()
        || inventoryTotal(product) - previousProductCount != 1) {
      waitOffer("OUTPUT_UNCONFIRMED", "取出、消耗或等级结果未完全核实");
      return;
    }
    if (InventorySession.canClose()) mc().player.closeContainer();
    result("NONE", "产物已实际入包，消耗和等级已核实");
  }

  private void move(int source, int destination, int count) {
    clicks.add(new Click(source, 0, ClickType.PICKUP));
    for (int i = 0; i < count; i++) clicks.add(new Click(destination, 1, ClickType.PICKUP));
    if (count < menu.getSlot(source).getItem().getCount())
      clicks.add(new Click(source, 0, ClickType.PICKUP));
  }

  private void applyClick(int slot, int button, ClickType type) {
    Actions.click(slot, button, type);
    expected = menu.slots.stream().map(s -> s.getItem().copy()).toList();
    expectedCursor = menu.getCarried().copy();
    GuiContext.invalidate();
    refresh();
  }

  private boolean matchesClick() {
    if (!ItemStack.matches(expectedCursor, menu.getCarried())) return false;
    for (int i = 0; i < expected.size(); i++) {
      // Anvil preview is server-computed. Its exact result is checked before taking and after.
      if (operation != Operation.ENCHANT && i == 2) continue;
      if (!ItemStack.matches(expected.get(i), menu.getSlot(i).getItem())) return false;
    }
    return true;
  }

  private int firstInventory() { return operation == Operation.ENCHANT ? 2 : 3; }

  private boolean anvilInputsMatch() {
    return ItemStack.matches(original, menu.getSlot(0).getItem())
        && ItemStack.matches(repairMaterial, menu.getSlot(1).getItem());
  }

  private int inventoryTotal(ItemStack sample) {
    return menu.slots.subList(firstInventory(), menu.slots.size()).stream().map(Slot::getItem)
        .filter(s -> ItemStack.isSameItemSameTags(sample, s)).mapToInt(ItemStack::getCount).sum();
  }

  private void waitOffer(String code, String detail) {
    if (++offerWait > 100) result(code, detail);
  }

  private void refresh() {
    revision = MenuContext.revision();
    waiting = 0;
    MenuRefresh.request();
  }

  private void result(String code, String detail) {
    boolean verified = code.equals("NONE");
    var out = new JsonObject();
    out.addProperty("status", verified ? "verified" : "partial");
    out.addProperty("business_success", verified);
    out.addProperty("reason_code", code);
    out.addProperty("operation", operation.name().toLowerCase(java.util.Locale.ROOT));
    out.addProperty("phase", phase.name().toLowerCase(java.util.Locale.ROOT));
    out.addProperty("detail", Rules.clean(detail, 450));
    if (code.equals("STALE_VIEW") || code.equals("SCREEN_BLOCKING")) {
      var screenNow = mc().screen;
      out.addProperty("screen", screenNow == null ? "world" : screenNow.getClass().getName());
      out.addProperty("suggested_tool", "inspect_gui");
    }
    if (cost > 0) out.addProperty("level_cost", cost);
    if (verified) {
      out.add("output", Actions.item(product));
      out.addProperty("item_fingerprint", WorldSense.fingerprint(product));
      out.addProperty("levels_spent", cost);
      out.addProperty("materials_consumed", operation == Operation.ENCHANT ? tier
          : operation == Operation.REPAIR ? 1 : 0);
    }
    finish(out.toString());
  }

  @Override String interrupted() {
    return "装备加工已停止，按当前槽位与背包核对投入和产物";
  }

  private static Minecraft mc() { return Minecraft.getInstance(); }
}
