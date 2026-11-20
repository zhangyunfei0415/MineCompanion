package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;

/** Bounded two-stage vanilla brewing. Each menu write waits for authoritative sync. */
final class BrewJob extends Actions.Job {
  private record Click(int slot, int button, ClickType type) {}
  private record Recipe(String name, Potion result, Item ingredient) {}

  private final Recipe recipe;
  private final int count;
  private final JsonObject location;
  private final ArrayDeque<Click> clicks = new ArrayDeque<>();
  private BrewingStandMenu menu;
  private Object screen;
  private long revision, deadline = System.currentTimeMillis() + 105_000;
  private List<ItemStack> expected;
  private ItemStack expectedCursor;
  private boolean opening, prepared, fuelNeeded;
  private int stage, wait, received, beforeOutput;

  BrewJob(JsonObject args, Consumer<String> done) {
    super(done);
    recipe = switch (Agent.str(args, "recipe", "fire_resistance")) {
      case "fire_resistance" -> new Recipe("fire_resistance", Potions.FIRE_RESISTANCE, Items.MAGMA_CREAM);
      case "healing" -> new Recipe("healing", Potions.HEALING, Items.GLISTERING_MELON_SLICE);
      default -> throw new IllegalArgumentException("[INVALID_ARGUMENT] recipe=fire_resistance/healing");
    };
    count = SkillCatalog.bounded(args, "count", 3, 1, 3);
    location = new JsonObject();
    if (args.has("place")) location.add("place", args.get("place").deepCopy());
    else if (args.has("x") || args.has("y") || args.has("z")) {
      for (String key : List.of("x", "y", "z")) {
        if (!args.has(key)) throw new IllegalArgumentException("[INVALID_ARGUMENT] 需要完整酿造台坐标");
        location.add(key, args.get(key).deepCopy());
      }
    }
  }

  @Override void tick() {
    var mc = Minecraft.getInstance();
    Actions.status = "酿造 · " + recipe.name() + " · " + received + "/" + count;
    if (System.currentTimeMillis() > deadline) {
      finishResult("TIME_BUDGET", "等待酿造或服务器菜单同步超时");
      return;
    }
    if (menu == null) {
      if (mc.player.containerMenu instanceof BrewingStandMenu stand && mc.screen != null) {
        if (location.size() > 0 && !MenuContext.matches(Actions.target(location))) {
          finishResult("SCREEN_BLOCKING", "当前菜单来源与指定酿造台不一致或无法核实");
          return;
        }
        menu = stand;
        screen = mc.screen;
        refresh();
      } else if (location.size() > 0 && mc.screen == null && !opening) {
        opening = true;
        var target = Actions.target(location);
        if (!mc.level.hasChunkAt(target)
            || !mc.level.getBlockState(target).is(net.minecraft.world.level.block.Blocks.BREWING_STAND)) {
          finishResult("WRONG_STATION", "目标不是已加载的原版酿造台");
          return;
        }
        child("open_container", location, r -> {
          if (Rules.actionFailed(r)) finishResult("WRONG_STATION", r);
        });
      } else finishResult(mc.screen == null ? "NO_STATION" : "SCREEN_BLOCKING",
          "请打开原版酿造台或指定已加载的酿造台位置");
      return;
    }
    if (mc.player.containerMenu != menu || mc.screen != screen) {
      finishResult("STALE_VIEW", "酿造界面已经改变");
      return;
    }
    if (MenuContext.revision() <= revision || !MenuContext.synchronizedNow()) {
      if (++wait > 160) finishResult("MENU_NOT_SYNCED", "酿造菜单未取得服务器同步");
      return;
    }
    if (expected != null) {
      if (!ItemStack.matches(expectedCursor, menu.getCarried())
          || !matchesExpected()) {
        finishResult("MENU_NOT_SYNCED", "服务器背包与计划点击不一致，请核对当前酿造台");
        return;
      }
      expected = null;
    }
    if (!clicks.isEmpty()) {
      click(clicks.removeFirst());
      return;
    }
    if (!prepared) {
      if (!menu.getCarried().isEmpty() || menu.getBrewingTicks() > 0
          || menu.slots.subList(0, 5).stream().anyMatch(s -> s.hasItem())) {
        finishResult("BREW_STAND_OCCUPIED", "需要空酿造台和空鼠标");
        return;
      }
      fuelNeeded = menu.getFuel() < 2;
      var water = menu.slots.subList(5, menu.slots.size()).stream()
          .filter(s -> potion(s.getItem(), Potions.WATER)).limit(count).toList();
      if (water.size() < count || source(Items.NETHER_WART) < 0
          || source(recipe.ingredient()) < 0 || fuelNeeded && source(Items.BLAZE_POWDER) < 0) {
        finishResult("MISSING_MATERIAL", "需要水瓶、下界疣、配方材料及不足时的烈焰粉");
        return;
      }
      for (int i = 0; i < count; i++) {
        clicks.add(new Click(water.get(i).index, 0, ClickType.PICKUP));
        clicks.add(new Click(i, 0, ClickType.PICKUP));
      }
      prepared = true;
      return;
    }
    switch (stage) {
      case 0 -> {
        if (fuelNeeded && menu.getFuel() < 2) enqueueOne(source(Items.BLAZE_POWDER), 4);
        stage++;
      }
      case 1 -> {
        enqueueOne(source(Items.NETHER_WART), 3);
        stage++;
      }
      case 2 -> {
        if (!readyPotions(Potions.AWKWARD)) return;
        enqueueOne(source(recipe.ingredient()), 3);
        stage++;
      }
      case 3 -> {
        if (!readyPotions(recipe.result())) return;
        beforeOutput = inventoryPotions(recipe.result());
        for (int i = 0; i < count; i++) clicks.add(new Click(i, 0, ClickType.QUICK_MOVE));
        stage++;
      }
      case 4 -> {
        received = Math.max(0, inventoryPotions(recipe.result()) - beforeOutput);
        if (received != count || menu.slots.subList(0, count).stream().anyMatch(s -> s.hasItem())) {
          finishResult("NO_CAPACITY", "取出数量不齐，请核对酿造台与背包空间");
          return;
        }
        if (InventorySession.canClose()) mc.player.closeContainer();
        finishResult("NONE", "实际药水已入包");
      }
      default -> throw new IllegalStateException("brew stage");
    }
  }

  private void enqueueOne(int source, int target) {
    if (source < 0 || menu.getSlot(target).hasItem() || !menu.getCarried().isEmpty()) {
      finishResult("MISSING_MATERIAL", "材料、目标槽或鼠标状态已改变");
      return;
    }
    clicks.add(new Click(source, 0, ClickType.PICKUP));
    clicks.add(new Click(target, 1, ClickType.PICKUP));
    if (menu.getSlot(source).getItem().getCount() > 1)
      clicks.add(new Click(source, 0, ClickType.PICKUP));
  }

  private boolean readyPotions(Potion result) {
    return !menu.getSlot(3).hasItem() && menu.slots.subList(0, count).stream()
        .allMatch(s -> potion(s.getItem(), result));
  }

  private void click(Click click) {
    Actions.click(click.slot(), click.button(), click.type());
    // Fuel consumption may be synchronized along with the first fuel insertion.
    expected = menu.slots.stream().map(s -> s.getItem().copy()).toList();
    expectedCursor = menu.getCarried().copy();
    GuiContext.invalidate();
    refresh();
  }

  private boolean matchesExpected() {
    for (int i = 0; i < expected.size(); i++) {
      var actual = menu.getSlot(i).getItem();
      var wanted = expected.get(i);
      if (i == 4 && wanted.is(Items.BLAZE_POWDER) && actual.isEmpty() && menu.getFuel() > 0)
        continue;
      if (!ItemStack.matches(actual, wanted)) return false;
    }
    return true;
  }

  private int source(Item item) {
    for (int i = 5; i < menu.slots.size(); i++) {
      var stack = menu.getSlot(i).getItem();
      if (stack.is(item) && !stack.hasTag()) return i;
    }
    return -1;
  }

  private int inventoryPotions(Potion result) {
    return menu.slots.subList(5, menu.slots.size()).stream().map(s -> s.getItem())
        .filter(s -> potion(s, result)).mapToInt(ItemStack::getCount).sum();
  }

  private static boolean potion(ItemStack stack, Potion potion) {
    return stack.is(Items.POTION) && PotionUtils.getCustomEffects(stack).isEmpty()
        && PotionUtils.getPotion(stack) == potion;
  }

  private void refresh() {
    revision = MenuContext.revision();
    wait = 0;
    MenuRefresh.request();
  }

  private void finishResult(String code, String detail) {
    var out = new JsonObject();
    out.addProperty("status", code.equals("NONE") ? "verified" : "partial");
    out.addProperty("business_success", code.equals("NONE"));
    out.addProperty("reason_code", code);
    out.addProperty("detail", detail);
    out.addProperty("recipe", recipe.name());
    out.addProperty("received", received);
    if (code.equals("STALE_VIEW") || code.equals("SCREEN_BLOCKING")) {
      var screenNow = Minecraft.getInstance().screen;
      out.addProperty("screen", screenNow == null ? "world" : screenNow.getClass().getName());
      out.addProperty("suggested_tool", "inspect_gui");
    }
    if (received > 0)
      out.addProperty("item_fingerprint", WorldSense.fingerprint(
          PotionUtils.setPotion(new ItemStack(Items.POTION), recipe.result())));
    finish(out.toString());
  }
}
