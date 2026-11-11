package dev.mibao.companion;

import net.minecraft.client.Minecraft;

/** User-controlled message routing; debug traces never enter public chat. */
public final class Messages {
  private static final java.util.Deque<String> sent = new java.util.ArrayDeque<>();

  public static boolean isOwnReply(String text) {
    return sent.stream().anyMatch(text::contains);
  }

  private static final ChatOutbox outbox = new ChatOutbox();

  public static void teamReport(String recipient, String text, String mode) {
    Companion.log(text);
    Hud.notice(text);
    HarnessController.message(text);
    if (Store.config.publicReply)
      outbox.offer(
          Store.config.name,
          recipient,
          CommunityReport.compact(text, mode),
          Store.scope(),
          System.currentTimeMillis());
  }

  public static void notice(String text) {
    emit(text, false);
  }

  public static void reply(String text) {
    CompanionIdentity.record("assistant", text);
    emit(text, true);
  }

  private static void emit(String text, boolean reply) {
    String clean = Rules.clean(text, 1200);
    if (clean.isBlank()) return;
    Companion.log(clean);
    HarnessController.message(clean);
    if (HelpPolicy.suppress(Store.config.helpNotifications, reply, clean)) return;
    Hud.notice(clean);
    if (clean.startsWith("{") || clean.startsWith("[")) return;
    boolean alert = !StatusFeed.level(clean).equals("info");
    boolean receipt =
        clean.startsWith("成功")
            || clean.startsWith("补给完成")
            || clean.startsWith("部分完成")
            || clean.startsWith("未转移");
    if (reply || Agent.localConversation && (alert || receipt))
      LocalDialogue.add(Store.config.name, clean);
    if (!CommunityTasks.recipient().isBlank()) {
      if (Store.config.publicReply
          && CommunityReport.intermediate(
              CommunityTasks.reportMode(), reply, receipt, alert, clean))
        outbox.offer(
            Store.config.name,
            CommunityTasks.recipient(),
            CommunityReport.compact(clean, CommunityTasks.reportMode()),
            Store.scope(),
            System.currentTimeMillis());
      return;
    }
    if (!Agent.localConversation
        && (reply && Store.config.publicReply
            || alert && Store.config.publicAlerts
            || receipt && Store.config.publicReceipts))
      outbox.offer(
          Store.config.name, Store.config.owner, clean, Store.scope(), System.currentTimeMillis());
  }

  public static void tick() {
    var mc = Minecraft.getInstance();
    if (mc.getConnection() == null || mc.player == null) return;
    String text = outbox.poll(Store.scope(), System.currentTimeMillis());
    if (text != null) {
      sent.addLast(text);
      while (sent.size() > 32) sent.removeFirst();
      mc.getConnection().sendChat(text);
    }
  }

  public static void reset() {
    outbox.clear();
  }
}
