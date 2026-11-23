package dev.mibao.companion;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;

/** Explicit one-item or whole-stack normal drop. No outside-slot clicks, no implicit cleanup. */
public final class DropInventoryJob extends Actions.Job {
  private final String item;
  private final boolean stack;
  private final InventoryMove prepare;
  private final InventorySession proof = new InventorySession(false);
  private int stage;
  private boolean slotSelected;
  private int settle;
  private net.minecraft.world.item.ItemStack expected;
  private String fingerprint;
  private int beforeTotal, requested, pickedUp;
  private final java.util.Map<Integer, net.minecraft.world.item.ItemStack> spawned =
      new java.util.HashMap<>();
  private final java.util.Set<Integer> oldEntities = new java.util.HashSet<>();

  void observeLoot(int entityId) {
    var mc = Minecraft.getInstance();
    if (stage != 2 || settle > 10 || oldEntities.contains(entityId) || spawned.size() >= 32) return;
    if (mc.level.getEntity(entityId) instanceof net.minecraft.world.entity.item.ItemEntity entity
        && entity.distanceToSqr(mc.player) <= 16
        && WorldSense.fingerprint(entity.getItem()).equals(fingerprint))
      spawned.putIfAbsent(entityId, entity.getItem().copy());
  }

  void pickup(int entityId, int playerId, int amount) {
    if (stage != 2 || playerId != Minecraft.getInstance().player.getId()) return;
    var item = spawned.remove(entityId);
    if (item != null) pickedUp += Math.min(amount, item.getCount());
  }

  public DropInventoryJob(String item, boolean stack, Consumer<String> done) {
    super(done);
    this.item = item;
    this.stack = stack;
    rejectAmbiguousName(item);
    prepare = InventoryMove.ensure(s -> Actions.match(s, item));
  }

  /** A display-name filter must not silently pick one of several different or NBT/damageable stacks. */
  private static void rejectAmbiguousName(String item) {
    var player = Minecraft.getInstance().player;
    if (player == null || item == null || item.isBlank() || item.equals("*")) return;
    String base = item.split("#", 2)[0];
    var fingerprints = new java.util.HashSet<String>();
    boolean byName = false, special = false;
    for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
      var s = player.getInventory().getItem(i);
      if (!Actions.match(s, item)) continue;
      fingerprints.add(WorldSense.fingerprint(s));
      if (!net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(base))
        byName = true;
      if (s.hasTag() || s.isDamageableItem()) special = true;
    }
    if (byName && (fingerprints.size() > 1 || special))
      throw new IllegalArgumentException(
          "[AMBIGUOUS_ITEM] 名称命中多个不同指纹或带NBT/耐久的物品，未执行丢弃；请改用注册ID或fingerprint");
  }

  @Override
  void tick() {
    var mc = Minecraft.getInstance();
    if (stage == 0) {
      if (mc.screen != null
          || mc.player.containerMenu != mc.player.inventoryMenu
          || !mc.player.containerMenu.getCarried().isEmpty()) {
        finish("失败：丢弃前请退出菜单并收好鼠标物品");
        return;
      }
      if (mc.gameMode.getPlayerMode() != net.minecraft.world.level.GameType.SURVIVAL
          && mc.gameMode.getPlayerMode() != net.minecraft.world.level.GameType.ADVENTURE
          && mc.gameMode.getPlayerMode() != net.minecraft.world.level.GameType.CREATIVE) {
        finish("失败：[MODE_UNSUPPORTED] 旁观模式不支持丢弃，未发送丢弃");
        return;
      }
      stage = 1;
    }
    if (stage == 1 && !slotSelected) {
      int r = prepare.tick();
      if (r < 0) {
        finish("失败：" + prepare.error);
        return;
      }
      if (r == 0) return;
      mc.player.getInventory().selected = prepare.readySlot;
      // Let the game mode send the carried-slot packet before the drop action is sent.
      slotSelected = true;
      return;
    }
    if (stage == 1) {
      if (mc.player.getInventory().selected != prepare.readySlot
          || !Actions.match(mc.player.getMainHandItem(), item)) {
        finish("失败：切换快捷栏后手中物品已变化，未发送丢弃");
        return;
      }
      expected = mc.player.getMainHandItem().copy();
      fingerprint = WorldSense.fingerprint(expected);
      requested = stack ? expected.getCount() : 1;
      beforeTotal = PlayerEvidence.total(PlayerEvidence.actual(fingerprint));
      for (var entity :
          mc.level.getEntitiesOfClass(
              net.minecraft.world.entity.item.ItemEntity.class,
              mc.player.getBoundingBox().inflate(4))) oldEntities.add(entity.getId());
      expected.shrink(stack ? expected.getCount() : 1);
      PlayerEvidence.begin();
      mc.player.drop(stack);
      stage = 2;
      return;
    }
    if (++settle < 8) return;
    int r = proof.tick();
    if (r < 0) {
      finish("部分完成：丢弃已发送，" + proof.error);
      return;
    }
    if (r == 0) return;
    var actual = mc.player.getInventory().getItem(prepare.readySlot);
    var evidence = new com.google.gson.JsonObject();
    evidence.addProperty("whole_stack", stack);
    evidence.addProperty("expected_remaining", expected.getCount());
    evidence.addProperty("actual_remaining", actual.getCount());
    int afterTotal = PlayerEvidence.total(PlayerEvidence.actual(fingerprint));
    evidence.addProperty("fingerprint", fingerprint);
    evidence.addProperty("requested", requested);
    evidence.addProperty("inventory_before", beforeTotal);
    evidence.addProperty("inventory_after", afterTotal);
    evidence.addProperty("nearby_new_item_repickup", pickedUp);
    evidence.addProperty("repickup_is_correlated_candidate", true);
    boolean verified =
        net.minecraft.world.item.ItemStack.matches(expected, actual)
            && beforeTotal - afterTotal == requested;
    boolean recovered = !verified && beforeTotal == afterTotal && pickedUp >= requested;
    finish(
        new ActionResult(
                verified ? ActionResult.Status.verified : ActionResult.Status.unverified,
                verified ? ActionResult.Code.NONE : ActionResult.Code.USE_UNCONFIRMED,
                verified
                    ? "指定槽与总背包的精确指纹减量已核对"
                    : recovered
                        ? "丢弃后观察到附近同指纹新实体被重新拾取，总量已恢复；未完成净移出，不重发"
                        : "已发送一次丢弃，净移出未核实；请检查重新拾取或槽位变化",
                evidence)
            .json());
  }

  @Override
  void stop() {
    prepare.stop();
    proof.close();
    super.stop();
  }
}
