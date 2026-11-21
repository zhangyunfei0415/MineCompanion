package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * Bounded read-only tooltip inspection across 1..8 container slots, container-relative offsets,
 * or screen coordinates without requiring fullGameControl or moving the physical OS mouse.
 */
final class GuiTooltipProbeJob extends Actions.Job {
  private record TargetPoint(JsonObject anchor, int guiX, int guiY) {}

  private final List<TargetPoint> targets;
  private final Screen expectedScreen;
  private final String screenClass;
  private final JsonArray results = new JsonArray();
  private int index = -1;
  private int pointTicks;

  GuiTooltipProbeJob(JsonObject args, Consumer<String> done) {
    super(done);
    var mc = Minecraft.getInstance();
    if (mc.screen == null || Panel.managing(mc.screen)) {
      throw new IllegalArgumentException("[WRONG_CONTEXT] 需要非管理类已打开界面");
    }
    if (args.has("gui_view")) {
      GuiContext.validate(args.get("gui_view").getAsLong());
    } else if (args.has("screen_view")) {
      ScreenInput.validate(args.get("screen_view").getAsLong());
    } else {
      throw new IllegalArgumentException("[ARGUMENT_REQUIRED] 需要 gui_view 或 screen_view");
    }
    this.expectedScreen = mc.screen;
    this.screenClass = expectedScreen.getClass().getName();
    this.targets = parseTargets(args, expectedScreen, mc);
  }

  private static List<TargetPoint> parseTargets(
      JsonObject args, Screen screen, Minecraft mc) {
    List<JsonObject> raw = new ArrayList<>();
    if (args.has("targets") && args.get("targets").isJsonArray()) {
      JsonArray arr = args.getAsJsonArray("targets");
      if (arr.isEmpty() || arr.size() > 8) {
        throw new IllegalArgumentException("[INVALID_ARGUMENT] targets 须为 1..8 个探测点");
      }
      for (JsonElement el : arr) raw.add(el.getAsJsonObject());
    } else {
      raw.add(args);
    }

    List<TargetPoint> resolved = new ArrayList<>(raw.size());
    for (JsonObject t : raw) {
      JsonObject anchor = new JsonObject();
      int gx, gy;
      if (t.has("slot")) {
        if (!(screen instanceof AbstractContainerScreen<?> ac)) {
          throw new IllegalArgumentException("[WRONG_CONTEXT] slot 探测仅支持容器界面");
        }
        int slotIdx = t.get("slot").getAsInt();
        var menu = mc.player.containerMenu;
        if (slotIdx < 0 || slotIdx >= menu.slots.size()) {
          throw new IllegalArgumentException("[INVALID_ARGUMENT] slot 越界：" + slotIdx);
        }
        var slot = menu.getSlot(slotIdx);
        gx = ac.getGuiLeft() + slot.x + 8;
        gy = ac.getGuiTop() + slot.y + 8;
        anchor.addProperty("space", "slot");
        anchor.addProperty("slot", slotIdx);
        anchor.addProperty("x", slot.x);
        anchor.addProperty("y", slot.y);
      } else if (t.has("dx") && t.has("dy")) {
        if (!(screen instanceof AbstractContainerScreen<?> ac)) {
          throw new IllegalArgumentException("[WRONG_CONTEXT] dx/dy 探测仅支持容器界面");
        }
        int dx = t.get("dx").getAsInt();
        int dy = t.get("dy").getAsInt();
        gx = ac.getGuiLeft() + dx;
        gy = ac.getGuiTop() + dy;
        anchor.addProperty("space", "container_relative");
        anchor.addProperty("dx", dx);
        anchor.addProperty("dy", dy);
      } else if (t.has("x") && t.has("y")) {
        gx = t.get("x").getAsInt();
        gy = t.get("y").getAsInt();
        anchor.addProperty("space", "gui");
      } else {
        throw new IllegalArgumentException("[ARGUMENT_REQUIRED] 探测点须包含 slot、(dx,dy) 或 (x,y)");
      }
      if (gx < 0 || gy < 0 || gx >= screen.width || gy >= screen.height) {
        throw new IllegalArgumentException(
            "[INPUT_BOUNDS] 探测点不在当前GUI范围内：(" + gx + "," + gy + ")");
      }
      anchor.addProperty("gui_x", gx);
      anchor.addProperty("gui_y", gy);
      resolved.add(new TargetPoint(anchor, gx, gy));
    }
    return List.copyOf(resolved);
  }

  @Override
  void tick() {
    var mc = Minecraft.getInstance();
    if (mc.screen != expectedScreen || Panel.managing(mc.screen)) {
      cleanup();
      finish("部分完成：Tooltip探测期间界面切换或受保护；" + buildReport());
      return;
    }
    if (index < 0) {
      startPoint(0);
      return;
    }
    if (TooltipCapture.hasCaptured() || ++pointTicks >= 3) {
      JsonObject row = TooltipCapture.consume();
      row.add("anchor", targets.get(index).anchor());
      results.add(row);
      AgentPointer.endProbe();
      int next = index + 1;
      if (next >= targets.size()) {
        cleanup();
        finish(buildReport());
        return;
      }
      startPoint(next);
      return;
    }
    if (++ticks > 60) {
      cleanup();
      finish("部分完成：Tooltip探测超时；" + buildReport());
    }
  }

  private void startPoint(int nextIndex) {
    index = nextIndex;
    pointTicks = 0;
    TargetPoint tp = targets.get(nextIndex);
    AgentPointer.beginProbe(tp.guiX(), tp.guiY());
    ScreenInput.position(tp.guiX(), tp.guiY());
    TooltipCapture.start();
  }

  private String buildReport() {
    JsonObject out = new JsonObject();
    out.addProperty("screen_class", screenClass);
    out.add("results", results);
    out.addProperty("note", "Tooltip文本来自客户端渲染观察，仅供参考，不代表按钮或配方已生效。");
    return out.toString();
  }

  private static void cleanup() {
    AgentPointer.endProbe();
    TooltipCapture.reset();
  }

  @Override
  String interrupted() {
    cleanup();
    return "部分完成：Tooltip探测已中断；已释放探针。" + buildReport();
  }
}
