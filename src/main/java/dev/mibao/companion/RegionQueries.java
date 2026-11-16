package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;

/** One bounded read-only scan can advance beside the world-action lane. */
final class RegionQueries {
  private static RegionSurvey active;
  private static Consumer<String> completion;
  private static Object world;
  private static long epoch;
  private static String scope;

  static void request(JsonObject args, boolean lights, Consumer<String> done) {
    if (active != null) throw new IllegalArgumentException("[SURVEY_BUSY] 已有区域只读查询在分帧进行，请查原回执");
    active = new RegionSurvey(args, lights);
    completion = done;
    world = Minecraft.getInstance().level;
    epoch = Agent.epoch;
    scope = Store.scope();
  }

  static void tick() {
    if (active == null) return;
    String result = null;
    try {
      if (world != Minecraft.getInstance().level
          || epoch != Agent.epoch
          || !scope.equals(Store.scope()))
        result =
            new ActionResult(
                    ActionResult.Status.cancelled, ActionResult.Code.NONE, "区域查询上下文已改变，原查询停止", null)
                .json();
      else if (active.advance()) result = active.result().toString();
    } catch (RuntimeException e) {
      result = "失败：[SURVEY_FAILED] " + Rules.clean(e.getMessage(), 180);
    }
    if (result == null) return;
    var done = completion;
    active = null;
    completion = null;
    done.accept(result);
  }

  private RegionQueries() {}
}
