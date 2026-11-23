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
  private final ItemReplaceCheck.Expected replacement;
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
    replacement = ItemReplaceCheck.parse(plan.command(), mc().player.getGameProfile().getName());
    int free = 0;
    for (int i = 0; i < 36; i++) if (mc().player.getInventory().getItem(i).isEmpty()) free++;
    freeSlotsBefore = free;
    origin = mc().player.position();
  }

  public static CommandJob create(String value, boolean confirm, Consumer<String> done) {
    String prepared = TestPreparation.prepareCommand(value, confirm);
    var p = mc().player.position();
    var plan = GameCommandPolicy.parse(prepared, CommandSettings.profile(), p.x, p.y, p.z);
    if (Store.config.protectCriticalCommandBlocks
        && !confirm
        && plan.bounds() != null
        && java.util.Set.of("fill", "setblock", "clone").contains(plan.root())) {
      // clone overwrites its destination region, not the source bounds.
      int[] b = plan.root().equals("clone") ? cloneDestination(plan) : plan.bounds();
      if (plan.volume() <= 65536) {
        for (var pos :
            BlockPos.betweenClosed(
                new BlockPos(b[0], b[1], b[2]), new BlockPos(b[3], b[4], b[5]))) {
          if (!mc().level.hasChunkAt(pos))
            throw new IllegalArgumentException(
                "[COMMAND_UNLOADED_AREA] 区域含客户端未加载区块 @ "
                    + pos.toShortString()
                    + "，无法确认其中是否有容器或关键方块；靠近加载后再试，或确认范围后显式confirm=true");
          var block = mc().level.getBlockState(pos).getBlock();
          if (block == net.minecraft.world.level.block.Blocks.END_PORTAL_FRAME
              || block == net.minecraft.world.level.block.Blocks.END_PORTAL
              || block == net.minecraft.world.level.block.Blocks.SPAWNER
              || mc().level.getBlockEntity(pos) instanceof net.minecraft.world.Container)
            throw new IllegalArgumentException(
                "[COMMAND_PROTECTED_BLOCK] 区域包含"
                    + BuiltInRegistries.BLOCK.getKey(block)
                    + " @ "
                    + pos.toShortString()
                    + "；确认范围后显式confirm=true，或关闭关键方块提示");
        }
      }
    }
    return new CommandJob(plan, done);
  }

  /** Destination box of a resolved "clone x1 y1 z1 x2 y2 z2 dx dy dz ..." plan. */
  private static int[] cloneDestination(GameCommandPolicy.Plan plan) {
    int[] b = plan.bounds();
    String[] parts =
        CommandWords.parse(plan.command()).stream()
            .map(CommandWords.Word::text)
            .toArray(String[]::new);
    int[] out = new int[6];
    try {
      for (int i = 0; i < 3; i++) {
        int d = Integer.parseInt(parts[7 + i]);
        out[i] = d;
        out[3 + i] = Math.addExact(d, Math.abs(b[i] - b[3 + i]));
      }
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("[COMMAND_BOUNDS] 无法解析clone目标坐标，未发送");
    }
    return out;
  }

  @Override
  void tick() {
    Actions.status = sent ? "指令已发一次 · 等待客户端结果" : "发送正常玩家指令";
    if (!sent) {
      if (plan.targetPosition() != null && Store.config.safeTeleport) {
        double[] p = plan.targetPosition();
        var landing = LandingSafety.inspect(p[0], p[1], p[2]);
        if (!landing.get("safe").getAsBoolean()) {
          landing.addProperty("status", "rejected");
          landing.addProperty("sent", false);
          landing.addProperty("detail", "安全传送拒绝此落点；未加载视为未知。先inspect_landing，或在权限页显式关闭安全传送检查");
          finish(landing.toString());
          return;
        }
      }
      outcome = InteractionOutcome.begin();
      feedbackStart = feedbackRevision;
      mc().getConnection().sendCommand(plan.command());
      sent = true;
      return;
    }
    ticks++;
    outcome.tick();
    if (replacement != null && ticks >= 10 && ItemReplaceCheck.confirmed(replacement)) {
      var result =
          JsonParser.parseString(receipt("verified", "目标槽位物品ID、数量、指定NBT与服务器同步快照已核对"))
              .getAsJsonObject();
      result.addProperty("slot", replacement.slot());
      result.addProperty(
          "actual_stack",
          mc().player
              .getInventory()
              .getItem(replacement.slot())
              .save(new net.minecraft.nbt.CompoundTag())
              .toString());
      finish(result.toString());
      return;
    }
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
    if (plan.teleport())
      o.add(
          "landing",
          LandingSafety.inspect(mc().player.getX(), mc().player.getY(), mc().player.getZ()));
    return o.toString();
  }

  @Override
  String interrupted() {
    return sent ? receipt("interrupted", "指令可能已生效，重新观察；不自动重发") : "指令未发送，已停止";
  }
}
