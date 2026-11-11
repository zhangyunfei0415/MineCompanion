package dev.mibao.companion;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

/** Normal client command/creative actions. No server internals or private protocol. */
public final class TestPreparation {
  public static String command(String input) {
    return TestCommandPolicy.validate(input);
  }

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  public static void send(String value, Consumer<String> done) {
    send(value, false, done);
  }

  public static void send(String value, boolean confirm, Consumer<String> done) {
    String cmd = prepareCommand(value, confirm);
    mc().getConnection().sendCommand(cmd);
    done.accept("指令已发送一次，尚未确认执行成功；请observe/inspect_block检查结果，服务器拒绝或超时时不要盲目重发。");
  }

  /**
   * Validates one command (or bounded recovery command) without sending it.
   *
   * @return the exact command line to send
   * @throws IllegalArgumentException when recovery bounds, permissions, or screen state forbid it
   */
  public static String prepareCommand(String value, boolean confirm) {
    if (mc().player == null || mc().getConnection() == null)
      throw new IllegalArgumentException("[NOT_CONNECTED] 未进入世界");
    var at = mc().player.blockPosition();
    var plan =
        GameCommandPolicy.parse(value, CommandSettings.profile(), at.getX(), at.getY(), at.getZ());
    if (mc().screen != null)
      throw new IllegalArgumentException(
          "game_command 需要 screen=world：请先 gui_back/关闭界面后再试（不会自动关闭你的界面）");
    return plan.command();
  }

  public static final class Take extends Actions.Job {
    private final String id;
    private final int count, requestedSlot;
    private int slot = -1;
    private ItemStack expected;
    private final InventorySession verify = new InventorySession(false);

    public Take(String id, int count, int slot, Consumer<String> done) {
      super(done);
      this.id = id;
      this.count = count;
      requestedSlot = slot;
    }

    @Override
    void tick() {
      if (mc().gameMode.getPlayerMode() != GameType.CREATIVE) {
        finish("失败：creative_take只在当前服务器已授予创造模式时可用");
        return;
      }
      if (mc().screen != null
          || mc().player.containerMenu != mc().player.inventoryMenu
          || !mc().player.containerMenu.getCarried().isEmpty()) {
        finish("失败：先关闭菜单并收好鼠标物品");
        return;
      }
      if (expected == null) {
        String base = id.split("#", 2)[0];
        var key = ResourceLocation.tryParse(base);
        if (key == null || !BuiltInRegistries.ITEM.containsKey(key))
          throw new IllegalArgumentException("未知物品注册ID");
        expected = new ItemStack(BuiltInRegistries.ITEM.get(key));
        if (id.contains("#")) {
          boolean found = false;
          var rows = RecipeBridge.require().items(base, 0, 40).getAsJsonArray("items");
          for (var row : rows) {
            var e = row.getAsJsonObject();
            if (e.get("fingerprint").getAsString().equals(id)) {
              if (e.has("nbt"))
                try {
                  expected.setTag(net.minecraft.nbt.TagParser.parseTag(e.get("nbt").getAsString()));
                } catch (com.mojang.brigadier.exceptions.CommandSyntaxException ex) {
                  throw new IllegalArgumentException("JEI变体NBT不可解析");
                }
              found = true;
              break;
            }
          }
          if (!found) throw new IllegalArgumentException("未找到JEI实际变体，请先search_items，不接受任意NBT模板");
        }
        if (expected.isEmpty() || count < 1 || count > expected.getMaxStackSize())
          throw new IllegalArgumentException("数量超过物品堆叠上限");
        expected.setCount(count);
        if (requestedSlot < -1 || requestedSlot > 8)
          throw new IllegalArgumentException("hotbar_slot为0..8，省略时自动选空格");
        slot = requestedSlot;
        if (slot < 0)
          for (int i = 0; i < 9; i++)
            if (mc().player.getInventory().getItem(i).isEmpty()) {
              slot = i;
              break;
            }
        if (requestedSlot < 0 && slot < 0)
          for (int i = 9; i < 36; i++)
            if (mc().player.getInventory().getItem(i).isEmpty()) {
              slot = i;
              break;
            }
        if (slot < 0 || !mc().player.getInventory().getItem(slot).isEmpty())
          throw new IllegalArgumentException(
              "[NO_CAPACITY] 指定槽被占用或背包全满；可先存货/organize_inventory/显式creative_clear_slot，不覆盖原物品");
        PlayerEvidence.begin();
        mc().player.getInventory().setItem(slot, expected.copy());
        mc().gameMode.handleCreativeModeItemAdd(expected.copy(), InventoryLayout.menuSlot(slot));
        return;
      }
      int verification = verify.tick();
      if (verification < 0) {
        finish("部分完成：创造取物请求已发，" + verify.error + "；请核对背包，不重复取物");
        return;
      }
      if (verification == 0) return;
      if (PlayerEvidence.slotConfirmed(slot)
          && PlayerEvidence.inventorySlot(slot).getItem() == expected.getItem()
          && PlayerEvidence.inventorySlot(slot).getCount() == count
          && (!id.contains("#") || Actions.match(PlayerEvidence.inventorySlot(slot), id))
          && ItemStack.matches(
              PlayerEvidence.inventorySlot(slot), mc().player.getInventory().getItem(slot))) {
        finish(
            "成功：创造取物已由服务器背包同步核实；"
                + id
                + " ×"
                + count
                + "，inventory_slot="
                + slot
                + "；属于测试准备，非生产成果。");
        return;
      }
      finish("部分完成：服务器背包已刷新，但目标槽未符合期望；可能被拒绝或物品变化，请observe核对，不自动重复取物。");
    }
  }
}
