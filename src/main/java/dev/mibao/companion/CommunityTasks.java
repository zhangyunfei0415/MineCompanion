package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import net.minecraft.client.Minecraft;

/** Confirmed sender identities only; every goal executes through the existing Agent. */
public final class CommunityTasks {
  private static boolean dispatching;
  private static int ticks;

  public static boolean dispatching() {
    return dispatching;
  }

  public static void superseded() {
    var t = board().active();
    if (t != null) {
      t.state = "INTERRUPTED";
      t.note = "被本机新目标接替，旧目标未重放";
    }
  }

  private static Minecraft mc() {
    return Minecraft.getInstance();
  }

  private static CommunityBoard board() {
    return Store.data.community;
  }

  public static void halt() {
    board().stop();
  }

  public static void reconnect() {
    board().reconnect();
  }

  public static CommunityBoard.Member member(String uuid, String name) {
    if (MemberDirectory.revoked(Store.data.revokedMembers, uuid, name)) return null;
    var m = CommunityBoard.resolve(Store.data.members, uuid, name);
    if (m != null) {
      if (m.uuid.isBlank()) {
        m.uuid = uuid;
        Store.save();
      }
      return m;
    }
    // A disabled explicitly configured member overrides the old name lists.
    if (Store.data.members.stream()
        .anyMatch(x -> x.uuid.equals(uuid) || x.name.equalsIgnoreCase(name))) return null;
    boolean owner = name.equalsIgnoreCase(Store.config.owner),
        trusted =
            Arrays.stream(Store.config.trusted.split("[,，\\s]+")).anyMatch(name::equalsIgnoreCase);
    if (!owner && !trusted) return null;
    m = new CommunityBoard.Member();
    m.name = name;
    m.uuid = uuid;
    m.role = owner ? "ADMIN" : "MEMBER";
    Store.data.members.add(m);
    Store.save();
    return m;
  }

  public static void receive(String uuid, String name, String text) {
    receiveMention(
        uuid, name, new MentionPolicy.ExtractedMention(Store.config.name, text == null ? "" : text, true));
  }

  public static void receiveMention(
      String uuid, String name, MentionPolicy.ExtractedMention extracted) {
    if (extracted == null) return;
    MentionPolicy.Role role =
        MentionPolicy.resolveRole(
            Store.data.members,
            Store.data.revokedMembers,
            Store.config.owner,
            Store.config.trusted,
            uuid,
            name);
    String text = ChatCommand.control(ChatCommand.normalize(extracted.body()));
    if (text.isBlank()) return;

    if (role == MentionPolicy.Role.GUEST) {
      if (!Store.config.allowGuestChat) {
        TaskTimeline.add("command_rejected", "未授权成员：" + name, "");
        return;
      }
      if (!Agent.enabled) return;
      if (extracted.atStart()
          && (ChatCommand.follow(text) != null
              || ChatCommand.incompleteFollow(text)
              || ChatCommand.duty(text) != null
              || text.equals("停止")
              || text.equalsIgnoreCase("stop")
              || text.equals("暂停")
              || text.equals("继续")
              || text.equals("进度")
              || text.equals("任务列表")
              || text.startsWith("取消任务 ")
              || text.startsWith("修改任务 "))) {
        Messages.mentionReply(
            name,
            "抱歉，你是访客身份，目前仅支持安全闲聊与基础问答，无法执行控制或操作指令。",
            MentionPolicy.ReplyPhase.CHAT,
            "BRIEF",
            false,
            false);
        return;
      }
      var guestMention =
          HarnessController.inbox()
              .enqueue(
                  HarnessController.currentScope(),
                  HarnessController.worldGeneration(),
                  uuid,
                  name,
                  role,
                  extracted.alias(),
                  text,
                  System.currentTimeMillis());
      if (guestMention == null) return;
      if (Store.config.driver.equals("HARNESS")) {
        HarnessController.publishPlayerMention(guestMention);
      } else {
        Agent.replyGuestMention(guestMention);
      }
      return;
    }

    var m = member(uuid, name);
    String mode =
        m != null
            ? CommunityReport.mode(m.report, Store.config.teamReport)
            : CommunityReport.mode("DEFAULT", Store.config.teamReport);
    String delivery = m != null ? m.delivery : "";
    boolean admin = role == MentionPolicy.Role.OWNER || role == MentionPolicy.Role.ADMIN;

    if (extracted.atStart()) {
      var follow = ChatCommand.follow(text);
      if (follow != null) {
        FollowControl.command(follow, uuid, result -> Messages.teamReport(name, result, mode));
        return;
      }
      if (ChatCommand.incompleteFollow(text)) {
        Messages.teamReport(name, "请说「跟着我」「跟随 玩家名」或「停止跟随」", mode);
        return;
      }
      var dutyCmd = ChatCommand.duty(text);
      if (dutyCmd != null) {
        DutyScheduler.handleChatCommand(dutyCmd, result -> Messages.teamReport(name, result, mode));
        return;
      }
      if (text.equals("停止") || text.equalsIgnoreCase("stop")) {
        Agent.stop("收到授权玩家 " + name + " 的停止指令");
        return;
      }
      if (text.equals("暂停") || text.equals("继续")) {
        Agent.pause(text.equals("暂停"));
        return;
      }
      if (text.equals("任务列表") || text.equals("进度")) {
        var active = board().active();
        String brief =
            (active == null ? "当前无活动目标" : "当前执行 " + active.id + " · " + active.name)
                + "；你的待办："
                + board().tasks.stream()
                    .filter(
                        t ->
                            t.sender.equals(uuid)
                                && Set.of("WAITING", "WAITING_MATERIALS").contains(t.state))
                    .map(t -> t.id + " " + t.state)
                    .toList();
        Messages.teamReport(name, mode.equals("DETAIL") ? human() : brief, mode);
        return;
      }
      if (text.startsWith("取消任务 ")) {
        board().change(text.substring(5).trim(), uuid, admin, null);
        Store.save();
        Messages.teamReport(name, "待办已取消", mode);
        return;
      }
      if (text.startsWith("修改任务 ")) {
        String[] p = text.substring(5).split("\\s+", 2);
        if (p.length != 2) throw new IllegalArgumentException("修改任务 ID 新目标");
        board().change(p[0], uuid, admin, p[1]);
        Store.save();
        Messages.teamReport(name, "待办目标已修改", mode);
        return;
      }
    }

    if (!Agent.enabled) {
      Companion.notifyUser("收到 " + name + " 的目标，但AI未接管；没有自动开启或排队");
      return;
    }

    var mention =
        HarnessController.inbox()
            .enqueue(
                HarnessController.currentScope(),
                HarnessController.worldGeneration(),
                uuid,
                name,
                role,
                extracted.alias(),
                text,
                System.currentTimeMillis());
    if (Store.config.driver.equals("HARNESS")) {
      if (mention != null) HarnessController.publishPlayerMention(mention);
      return;
    }

    var t =
        board()
            .add(
                uuid,
                name,
                text,
                mc().level.dimension().location().toString(),
                delivery,
                System.currentTimeMillis());
    t.role = role.name();
    if (mention != null) t.mentionId = mention.id;
    Store.save();
    if (board().active() != null || !Agent.task.isBlank() || HarnessController.foregroundBusy()) {
      Messages.mentionReply(
          name,
          "收到啦，我先把眼下这件事处理完就来帮你看看～",
          MentionPolicy.ReplyPhase.ACK,
          mode,
          false,
          false);
      t.ackSent = true;
    }
  }

  public static JsonObject acceptMentionWork(String mentionId, String note) {
    long now = System.currentTimeMillis();
    String sc = HarnessController.currentScope();
    var m = HarnessController.inbox().get(mentionId, sc, now);
    if (m == null) {
      throw new IllegalArgumentException("[MENTION_NOT_FOUND] 未找到有效或未过期的玩家提及会话");
    }
    if (!MentionPolicy.canWork(m.role)
        || MemberDirectory.revoked(Store.data.revokedMembers, m.senderUuid, m.senderName)) {
      throw new IllegalArgumentException("[PERMISSION_DENIED] 访客或已撤销成员禁止派发或执行任务");
    }
    if (m.state == PlayerMentionInbox.State.RESOLVED && !m.taskId.isBlank()) {
      var existing = new JsonObject();
      existing.addProperty("task_id", m.taskId);
      existing.addProperty("mention_id", m.id);
      existing.addProperty("already_accepted", true);
      existing.addProperty("busy", HarnessController.foregroundBusy());
      return existing;
    }
    var mem = member(m.senderUuid, m.senderName);
    String delivery = mem != null ? mem.delivery : "";
    String dim = mc().level == null ? "disconnected" : mc().level.dimension().location().toString();
    var t = board().add(m.senderUuid, m.senderName, m.text, dim, delivery, now);
    t.mentionId = m.id;
    t.role = m.role.name();
    t.ackSent = m.ackSent;
    if (note != null && !note.isBlank()) t.note = Rules.clean(note, 300);
    HarnessController.inbox().resolveAsWork(m.id, sc, t.id, now);
    Store.save();
    var res = new JsonObject();
    res.addProperty("task_id", t.id);
    res.addProperty("mention_id", m.id);
    res.addProperty("state", t.state);
    res.addProperty("busy", HarnessController.foregroundBusy());
    var active = board().active();
    if (active != null) res.addProperty("active_task_id", active.id);
    return res;
  }

  public static JsonObject completeMentionTask(
      String taskId, String outcome, String summary, String replyText) {
    var t = board().active();
    if (t == null || (!taskId.isBlank() && !t.id.equals(taskId))) {
      throw new IllegalArgumentException("[TASK_NOT_FOUND] 当前没有匹配的活动多人任务");
    }
    String cleanOutcome = outcome == null ? "COMPLETED" : outcome.strip().toUpperCase(Locale.ROOT);
    if (!Set.of("COMPLETED", "FAILED", "CANCELLED").contains(cleanOutcome)) {
      throw new IllegalArgumentException("[INVALID_ARGUMENT] outcome 须为 COMPLETED/FAILED/CANCELLED");
    }
    if ("COMPLETED".equals(cleanOutcome) && HarnessController.foregroundBusy()) {
      throw new IllegalArgumentException("[BUSY] 请先完成或停止当前前台子作业再结案");
    }
    t.state = cleanOutcome;
    t.note = summary == null || summary.isBlank() ? "Harness 已结案" : Rules.clean(summary, 300);
    if (replyText != null && !replyText.isBlank()) {
      Messages.mentionReply(
          t.name, replyText, MentionPolicy.ReplyPhase.FINAL, reportMode(), t.ackSent, true);
      t.naturalReplySent = true;
    }
    Store.save();
    var out = new JsonObject();
    out.addProperty("task_id", t.id);
    out.addProperty("state", t.state);
    out.addProperty("recipient", t.name);
    out.addProperty("message", "多人任务已结案");
    return out;
  }

  public static boolean tick() {
    if (mc().level == null) return false;
    if (++ticks % 10 == 0) {
      var stock = new LinkedHashMap<String, Integer>();
      for (var t : board().tasks)
        for (String id : t.requires.keySet()) stock.put(id, plainCount(id));
      if (CommunityBoard.wakeAvailable(
              board().tasks, mc().level.dimension().location().toString(), stock)
          > 0) Store.save();
    }
    boolean harness = Store.config.driver.equals("HARNESS");
    if (board().active() != null) return false;
    if (harness) {
      if (HarnessController.foregroundBusy() || !HarnessController.leaseActive()) return false;
    } else if (!Agent.task.isBlank() || mc().screen != null) {
      return false;
    }
    var t = board().next(mc().level.dimension().location().toString());
    if (t == null) return false;
    MentionPolicy.Role resolvedRole =
        MentionPolicy.resolveRole(
            Store.data.members,
            Store.data.revokedMembers,
            Store.config.owner,
            Store.config.trusted,
            t.sender,
            t.name);
    if (!MentionPolicy.canWork(resolvedRole)) {
      t.state = "CANCELLED";
      t.note = "成员授权已撤销";
      Store.save();
      return true;
    }
    t.state = "ACTIVE";
    t.role = resolvedRole.name();
    t.runId = UUID.randomUUID().toString().substring(0, 8);
    t.requires.clear();
    Store.save();
    if (harness) {
      HarnessController.communityTaskStarted(t);
      return true;
    }
    dispatching = true;
    try {
      Agent.submitFromMention(t);
    } finally {
      dispatching = false;
    }
    return true;
  }

  public static boolean ackSent() {
    var t = board().active();
    return t != null && t.ackSent;
  }

  public static void markAckSent() {
    var t = board().active();
    if (t != null) t.ackSent = true;
  }

  public static void markNaturalReplySent() {
    var t = board().active();
    if (t != null) t.naturalReplySent = true;
  }

  public static String context() {
    var t = board().active();
    return t == null
        ? ""
        : "\n当前多人对话对象="
            + t.name
            + "（身份="
            + t.role
            + "，任务ID="
            + t.id
            + "，本世界默认交付地点="
            + (t.delivery.isBlank() ? "未设置，任务未指定时先查询或澄清" : t.delivery)
            + "）。若对方只是闲聊或提问，直接自然简短回复并结束；若需要动手且观察/评估可尝试，请先用简洁一句话回应对方（例如「好，我试试看，这就去！」）再调用工具；若确实做不到或缺权限，礼貌说明原因（所有工具失败与缺失条件不得编造）。";
  }

  public static String reportMode() {
    var t = board().active();
    if (t == null) return Store.config.teamReport;
    var m = Store.data.members.stream().filter(x -> x.uuid.equals(t.sender)).findFirst();
    return CommunityReport.mode(m.map(x -> x.report).orElse("DEFAULT"), Store.config.teamReport);
  }

  public static String recipient() {
    var t = board().active();
    return t == null ? "" : t.name;
  }

  public static void result(String tool, String result) {
    var t = board().active();
    if (t != null
        && CapabilityCatalog.mutates(tool)
        && result != null
        && !result.startsWith("{")
        && !result.startsWith("[")) t.note = Rules.clean(result, 400);
  }

  public static void ended() {
    var t = board().active();
    if (t != null) {
      String mode = reportMode();
      t.state = t.requires.isEmpty() ? LogisticsRuntime.outcome(t) : "WAITING_MATERIALS";
      t.note = LogisticsRuntime.summary(t);
      if (t.note.isBlank()) t.note = "执行会话结束，业务结果以工具回执为准";
      Store.save();
      if (!t.naturalReplySent || t.state.equals("WAITING_MATERIALS")) {
        Messages.teamReport(
            t.name,
            "任务 "
                + t.id
                + (t.state.equals("WAITING_MATERIALS") ? " 等待随身补给 " + t.requires + "：" : " 结案：")
                + t.note,
            mode);
      }
    }
  }

  private static int plainCount(String id) {
    int n = 0;
    for (int i = 0; i < 36; i++) {
      var s = mc().player.getInventory().getItem(i);
      if (!s.isEmpty()
          && !s.hasTag()
          && net.minecraft.core.registries.BuiltInRegistries.ITEM
              .getKey(s.getItem())
              .toString()
              .equals(id)) n += s.getCount();
    }
    return n;
  }

  public static String waitFor(JsonObject required, String note) {
    var t = board().active();
    if (t == null) throw new IllegalArgumentException("没有活动多人目标");
    if (Agent.routine != null
        || Actions.job != null
        || Agent.session != null
        || Agent.production != null
        || Agent.resource != null
        || Agent.transport != null
        || Agent.restock != null
        || !Agent.pendingTool.isBlank()) throw new IllegalArgumentException("先结算当前子作业再挂起");
    var values = new LinkedHashMap<String, Integer>();
    for (var e : required.entrySet()) {
      if (!e.getKey().matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || values.size() >= 16)
        throw new IllegalArgumentException("需要1..16种普通注册ID");
      int n;
      try {
        n = e.getValue().getAsBigDecimal().intValueExact();
      } catch (Exception ex) {
        throw new IllegalArgumentException("需求必须是整数");
      }
      if (n < 1 || n > 256) throw new IllegalArgumentException("需求数量1..256");
      values.put(e.getKey(), n);
    }
    if (values.isEmpty()) throw new IllegalArgumentException("补给条件为空");
    t.requires = values;
    t.note = Rules.clean(note, 400);
    return "已记录补给条件；会话结束后挂起，材料到位只重新规划剩余目标，不重放原动作";
  }

  public static String finish(String id, String result) {
    var t = board().active();
    if (t == null || !t.id.equals(id)) throw new IllegalArgumentException("没有对应活动目标");
    if (Agent.routine != null
        || Agent.busy
        || Actions.job != null
        || Agent.session != null
        || Agent.production != null
        || Agent.resource != null
        || Agent.transport != null
        || Agent.restock != null
        || !Agent.pendingTool.isBlank())
      throw new IllegalArgumentException("先完成或停止当前子作业，不提前结束队列目标");
    String mode = reportMode();
    t.state = LogisticsRuntime.outcome(t);
    t.note = LogisticsRuntime.summary(t) + "；规划者摘要：" + Rules.clean(result, 200);
    Store.save();
    Messages.teamReport(t.name, "任务 " + t.id + " 结案：" + t.note, mode);
    return "队列目标已结案，不自动证明生产完成";
  }

  public static JsonObject view() {
    var o = new JsonObject();
    o.addProperty("schema", 1);
    o.add("tasks", Store.GSON.toJsonTree(board().tasks));
    o.add("members", Store.GSON.toJsonTree(Store.data.members));
    o.addProperty("auto_replay_after_reconnect", false);
    return o;
  }

  public static String human() {
    var b = new StringBuilder("多人任务队列（当前世界）\n");
    for (var t : board().tasks)
      b.append(t.id)
          .append(" · ")
          .append(t.name)
          .append(" · ")
          .append(t.state)
          .append(" · ")
          .append(t.text)
          .append('\n');
    return b.toString();
  }

  private CommunityTasks() {}
}
