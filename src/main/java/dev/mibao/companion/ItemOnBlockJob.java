package dev.mibao.companion;

import java.util.function.Consumer;
import net.minecraft.core.BlockPos;

public final class ItemOnBlockJob extends Actions.Job {
  private final ItemOnBlockOperation operation;

  public ItemOnBlockJob(BlockPos p, String item, boolean top, Consumer<String> done) {
    this(p, item, top, false, done);
  }

  public ItemOnBlockJob(
      BlockPos p, String item, boolean top, boolean sneak, Consumer<String> done) {
    this(p, item, top, sneak, 3, done);
  }

  public ItemOnBlockJob(
      BlockPos p,
      String item,
      boolean top,
      boolean sneak,
      int stanceRadius,
      Consumer<String> done) {
    super(done);
    operation = new ItemOnBlockOperation(p, item, top, sneak, stanceRadius);
  }

  void tick() {
    Actions.status = "使用物品作用于目标 · 走位/同步/核验";
    int r = operation.tick();
    if (r > 0) finish(operation.detail);
    else if (r < 0) finish("部分完成：" + operation.error);
  }

  void stop() {
    operation.stop();
    super.stop();
  }

  String interrupted() {
    return operation.sent() ? "目标物品操作已中断，可能已生效，请观察后再安排下一步。" : "目标物品操作停止。";
  }
}
