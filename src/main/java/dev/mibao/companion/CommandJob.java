package dev.mibao.companion;

import com.google.gson.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

/** Sends once through the normal client. No integrated-server reads or implicit cheat enabling. */
public final class CommandJob extends Actions.Job {
  private final GameCommandPolicy.Plan plan;
  private final GiveOutcome.Expected give;
  private final int freeSlotsBefore;
  private InteractionOutcome outcome;
  private boolean sent;
  private long feedbackStart;
  private final net.minecraft.world.phys.Vec3 origin;
  private static long feedbackRevision;
  private static String feedback = "", feedbackKey = "";

  public static void feedback(net.minecraft.network.chat.Component message) {
    feedback = Rules.clean(message.getString(), 400);
    feedbackKey =
        message.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
            ? t.getKey()
            : "";
    feedbackRevision++;
  }

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  private CommandJob(GameCommandPolicy.Plan plan, Consumer<String> done) {
    super(done);
    this.plan = plan;
    give = GiveOutcome.parse(plan.command(), mc().player.getGameProfile().getName());
    int free = 0;
    for (int i = 0; i < 36; i++) if (mc().player.getInventory().getItem(i).isEmpty()) free++;
    freeSlotsBefore = free;
    origin = mc().player.position();
  }

  public static CommandJob create(String value, boolean confirm, Consumer<String> done) {
    String prepared = TestPreparation.prepareCommand(value, confirm);
    var p = mc().player.position();
    return new CommandJob(
        GameCommandPolicy.parse(prepared, CommandSettings.profile(), p.x, p.y, p.z),
        done);
  }

  @Override
  void tick() {
    Actions.status = sent ? "指令已发一次 · 等待客户端结果" : "发送正常玩家指令";
    if (!sent) {
      outcome = InteractionOutcome.begin();
      feedbackStart = feedbackRevision;
      mc().getConnection().sendCommand(plan.command());
      sent = true;
      return;
    }
    ticks++;
    outcome.tick();
    if (!plan.expectedMode().isEmpty()
        && mc().gameMode.getPlayerMode().getName().equals(plan.expectedMode())) {
      finish(receipt("verified", "客户端确认游戏模式=" + plan.expectedMode()));
      return;
    }
    if (give != null && ticks >= 20) {
      finish(GiveOutcome.receipt(give, outcome.evidence(), freeSlotsBefore));
      return;
    }
    if (!plan.expectedBlock().isEmpty() && ticks >= 10 && plan.bounds() != null) {
      int[] b = plan.bounds();
      int checked = 0, match = 0;
      boolean loaded = true;
      for (var p :
          BlockPos.betweenClosed(
              new BlockPos(Math.min(b[0], b[3]), Math.min(b[1], b[4]), Math.min(b[2], b[5])),
              new BlockPos(Math.max(b[0], b[3]), Math.max(b[1], b[4]), Math.max(b[2], b[5])))) {
        if (!mc().level.hasChunkAt(p)) {
          loaded = false;
          continue;
        }
        checked++;
        if (BuiltInRegistries.BLOCK
            .getKey(mc().level.getBlockState(p).getBlock())
            .toString()
            .equals(plan.expectedBlock())) match++;
      }
      if (loaded && checked == match) {
        finish(receipt("state_matches", "客户端当前区域方块ID符合目标，共" + checked + "格；不证明每格由本指令改变，方块属性需另查"));
        return;
      }
    }
    if (plan.targetPosition() != null) {
      double[] target = plan.targetPosition();
      if (mc().player.distanceToSqr(target[0], target[1], target[2]) < .5625) {
        finish(receipt("verified", "客户端当前位置已符合传送目标，容差0.75格"));
        return;
      }
    } else if (plan.teleport() && mc().player.position().distanceTo(origin) > 3) {
      finish(receipt("position_changed", "观察到位置变化；插件命令归因待核实，不重发"));
      return;
    }
    if (feedbackRevision > feedbackStart
        && (feedbackKey.equals("commands.generic.permission")
            || feedbackKey.equals("command.unknown.command")
            || feedbackKey.equals("command.unknown.argument"))) {
      finish("失败：" + receipt("rejected", feedback));
      return;
    }
    if (ticks >= (plan.teleport() ? 600 : 40))
      finish(receipt("unverified", "已发送一次；系统消息仅作候选反馈，未知结果先查现场，不重发"));
  }

  private String receipt(String state, String detail) {
    var o = new JsonObject();
    o.addProperty("status", state);
    o.addProperty("sent", sent);
    o.addProperty("command", plan.command());
    o.addProperty("detail", detail);
    o.addProperty("singleplayer", mc().isLocalServer());
    o.addProperty("feedback", feedbackRevision > feedbackStart ? feedback : "");
    o.addProperty("feedback_is_correlated_candidate", true);
    return o.toString();
  }

  @Override
  String interrupted() {
    return sent ? receipt("interrupted", "指令可能已生效，重新观察；不自动重发") : "指令未发送，已停止";
  }
}
