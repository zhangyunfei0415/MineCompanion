package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Optional FTB client widget API only. No server/team-file access; the only outgoing packet is
 * FTB's own task submit. Reward packets stay user-driven.
 */
public final class QuestBookBridge {
  private record Row(Object widget, JsonObject data) {}

  private static final int SUBMIT_RECHECK_TICKS = 12;

  private static Object observedScreen;
  private static List<Row> observed = List.of();
  private static String stamp = "";
  private static long view, at;
  private static boolean treeTruncated;
  private static volatile PendingSubmit pendingSubmit;

  private static final class PendingSubmit {
    final Object team, task, quest;
    final JsonObject snapshot;
    final long at = System.currentTimeMillis(); // Moment the packet was sent.
    volatile JsonObject view;
    int ticksLeft = SUBMIT_RECHECK_TICKS;
    boolean done;

    PendingSubmit(Object team, Object task, Object quest, JsonObject snapshot) {
      this.team = team;
      this.task = task;
      this.quest = quest;
      this.snapshot = snapshot;
    }
  }

  private interface Probe {
    Object read() throws Exception;
  }

  private static Object call(Object o, String name) throws ReflectiveOperationException {
    var method = o.getClass().getMethod(name);
    method.trySetAccessible();
    return method.invoke(o);
  }

  public static boolean available() {
    var screen = Minecraft.getInstance().screen;
    if (screen == null
        || !screen.getClass().getName().equals("dev.ftb.mods.ftblibrary.ui.ScreenWrapper"))
      return false;
    try {
      return call(screen, "getGui")
          .getClass()
          .getName()
          .startsWith("dev.ftb.mods.ftbquests.client.gui.");
    } catch (ReflectiveOperationException | LinkageError e) {
      return false;
    }
  }

  private static void walk(Object w, List<Row> rows, Set<Object> seen, int depth, GuiViewport clip)
      throws ReflectiveOperationException {
    if (depth > 32 || seen.size() >= 8192) {
      treeTruncated = true;
      return;
    }
    if (!seen.add(w)) return;
    String type = w.getClass().getName();
    boolean visible = (Boolean) call(w, "shouldDraw");
    if (!visible) return;
    var hierarchy = new ArrayList<String>();
    for (Class<?> c = w.getClass(); c != null; c = c.getSuperclass()) hierarchy.add(c.getName());
    if (QuestWidgetPolicy.privateInput(hierarchy))
      throw new IllegalArgumentException(
          "[SCREEN_PRIVATE] 任务书当前含可编辑文本输入，请关闭输入后重试；普通标题TextField允许读取");
    int x = ((Number) call(w, "getX")).intValue(), y = ((Number) call(w, "getY")).intValue();
    int width = ((Number) call(w, "getWidth")).intValue(),
        height = ((Number) call(w, "getHeight")).intValue();
    var mc = Minecraft.getInstance();
    var d = new JsonObject();
    d.addProperty("widget", rows.size());
    d.addProperty("class", type);
    Object title = call(w, "getTitle");
    d.addProperty("label", title instanceof Component c ? Rules.clean(c.getString(), 1500) : "");
    d.addProperty("role", QuestWidgetPolicy.role(type));
    if (hierarchy.contains("dev.ftb.mods.ftblibrary.ui.TextField")) {
      try {
        Object component = w.getClass().getField("component").get(w);
        if (component instanceof Component text)
          d.addProperty("display_text", Rules.clean(text.getString(), 1500));
      } catch (ReflectiveOperationException ignored) {
        /* Title remains available. */
      }
    }
    describeObject(w, d);
    boolean button = false;
    for (Class<?> c = w.getClass(); c != null; c = c.getSuperclass())
      if (c.getName().equals("dev.ftb.mods.ftblibrary.ui.Button")) button = true;
    d.addProperty("button", button);
    d.addProperty(
        "active",
        (Boolean) call(w, "isEnabled") && !call(w, "getWidgetType").toString().equals("DISABLED"));
    var bounds = new GuiViewport(x, y, x + width, y + height);
    var hit = clip.intersect(bounds);
    d.addProperty("visible", hit.visible());
    d.addProperty("coordinate_space", "gui_screen_pixels_with_parent_scroll");
    d.addProperty("content_x", ((Number) call(w, "getPosX")).intValue());
    d.addProperty("content_y", ((Number) call(w, "getPosY")).intValue());
    d.addProperty("click_x", hit.visible() ? hit.centerX() : -1);
    d.addProperty("click_y", hit.visible() ? hit.centerY() : -1);
    d.addProperty(
        "semantic_open", type.equals("dev.ftb.mods.ftbquests.client.gui.quests.QuestButton"));
    d.addProperty("x", x);
    d.addProperty("y", y);
    d.addProperty("width", width);
    d.addProperty("height", height);
    rows.add(new Row(w, d));
    try {
      Object children = call(w, "getWidgets");
      boolean offset = (Boolean) call(w, "isOffset");
      GuiViewport childClip = (Boolean) call(w, "getOnlyRenderWidgetsInside") ? hit : clip;
      var scroll = new JsonObject();
      scroll.addProperty("x", ((Number) call(w, "getScrollX")).doubleValue());
      scroll.addProperty("y", ((Number) call(w, "getScrollY")).doubleValue());
      d.add("scroll", scroll);
      try {
        w.getClass().getMethod("setOffset", boolean.class).invoke(w, true);
        if (children instanceof Iterable<?> list)
          for (Object child : list) walk(child, rows, seen, depth + 1, childClip);
      } finally {
        w.getClass().getMethod("setOffset", boolean.class).invoke(w, offset);
      }
    } catch (NoSuchMethodException leaf) {
      /* Widget leaf, not a Panel. */
    }
  }

  private static List<Row> rows() {
    treeTruncated = false;
    if (!available()) throw new IllegalArgumentException("[QUEST_SCREEN_REQUIRED] 请先打开FTB任务书主界面");
    try {
      Object gui = call(Minecraft.getInstance().screen, "getGui");
      if (((Optional<?>) call(gui, "getContextMenu")).isPresent())
        throw new IllegalArgumentException("[SCREEN_PRIVATE] 请先关闭任务书上下文菜单");
      Object root = gui;
      if ((Boolean) call(gui, "anyModalPanelOpen")) {
        // FTB 2001 has no public top-modal accessor. Read only the client UI stack,
        // and only accept its known quest detail panel; never inspect arbitrary dialogs.
        Class<?> base = Class.forName("dev.ftb.mods.ftblibrary.ui.BaseScreen");
        var field = base.getDeclaredField("modalPanels");
        if (!field.trySetAccessible()) throw new IllegalAccessException("modalPanels");
        Object top = ((Deque<?>) field.get(gui)).peekFirst();
        if (top == null
            || !top.getClass().getName().startsWith("dev.ftb.mods.ftbquests.client.gui."))
          throw new IllegalArgumentException("[SCREEN_PRIVATE] 未适配任务书弹窗，请先关闭");
        root = top;
      }
      var out = new ArrayList<Row>();
      var mc = Minecraft.getInstance();
      walk(
          root,
          out,
          Collections.newSetFromMap(new IdentityHashMap<>()),
          0,
          new GuiViewport(
              0, 0, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()));
      return out;
    } catch (ReflectiveOperationException | LinkageError e) {
      throw new IllegalArgumentException(
          "[QUEST_API_UNSUPPORTED] FTB客户端控件接口不兼容：" + e.getClass().getSimpleName());
    }
  }

  public static boolean safeImage() {
    try {
      rows();
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static Object readField(Object target, String name) throws ReflectiveOperationException {
    for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
      try {
        var f = c.getDeclaredField(name);
        if (f.trySetAccessible()) return f.get(target);
      } catch (NoSuchFieldException ignored) {
      }
    }
    throw new NoSuchFieldException(name);
  }

  private static Object invokeRead(Object target, String name, Object... args)
      throws ReflectiveOperationException {
    for (var method : target.getClass().getMethods()) {
      if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
      boolean compatible = true;
      for (int i = 0; i < args.length; i++)
        if (!method.getParameterTypes()[i].isInstance(args[i])) compatible = false;
      if (compatible) {
        method.trySetAccessible();
        return method.invoke(target, args);
      }
    }
    throw new NoSuchMethodException(name);
  }

  private static void describeObject(Object widget, JsonObject out) {
    String role = out.get("role").getAsString();
    if (!role.equals("task_action") && !role.equals("claim_reward")) return;
    try {
      Object object = readField(widget, role.equals("task_action") ? "task" : "reward");
      out.addProperty("object_class", object.getClass().getName());
      out.addProperty("object_id", String.valueOf(call(object, "getId")));
      if (call(object, "getTitle") instanceof Component title)
        out.addProperty("object_title", Rules.clean(title.getString(), 1500));
      try {
        Object quest = call(object, "getQuest");
        out.addProperty("object_quest_id", String.valueOf(call(quest, "getId")));
        if (call(quest, "getTitle") instanceof Component questTitle)
          out.addProperty("object_quest_title", Rules.clean(questTitle.getString(), 1500));
      } catch (ReflectiveOperationException | RuntimeException ignored) {
        /* Custom task/reward types may not expose their quest; matching then stays permissive. */
      }
      Object gui = call(Minecraft.getInstance().screen, "getGui");
      Object team = readField(readField(gui, "file"), "selfTeamData");
      if (role.equals("task_action")) {
        out.addProperty("completed_client", (Boolean) invokeRead(team, "isCompleted", object));
        out.addProperty(
            "task_kind",
            object.getClass().getSimpleName().equals("CheckmarkTask")
                ? "checkmark"
                : "other_client_task");
      } else {
        Object claim =
            invokeRead(team, "getClaimType", Minecraft.getInstance().player.getUUID(), object);
        out.addProperty("can_claim_client", (Boolean) call(claim, "canClaim"));
        out.addProperty("claimed_client", (Boolean) call(claim, "isClaimed"));
      }
      out.addProperty("progress_source", "client_synced_ftb_view_not_server_file");
    } catch (ReflectiveOperationException | RuntimeException e) {
      out.addProperty("progress_known", false);
    }
  }

  private static String signature(List<Row> rows) {
    var text = new StringBuilder();
    for (Row r : rows) text.append(r.data);
    return text.toString();
  }

  /** Re-read the visible widget tree and consume this observation number. */
  private static List<Row> refresh() {
    observed = rows();
    observedScreen = Minecraft.getInstance().screen;
    stamp = signature(observed);
    view++;
    at = System.currentTimeMillis();
    return observed;
  }

  /** Project observed rows into the pure matcher's entry list. */
  private static List<QuestMatch.Entry> entriesFrom(List<Row> rows) {
    var entries = new ArrayList<QuestMatch.Entry>();
    for (Row r : rows)
      entries.add(
          new QuestMatch.Entry(
              r.data.get("widget").getAsInt(),
              r.data.get("label").getAsString(),
              r.data.get("semantic_open").getAsBoolean()));
    return entries;
  }

  /** Read one string argument, treating missing/JsonNull as empty. */
  static String arg(JsonObject a, String key) {
    JsonElement e = a.get(key);
    return e == null || e.isJsonNull() ? "" : e.getAsString();
  }

  /** Read {@code quest_view} with a readable Chinese reason instead of a JsonNull failure. */
  public static long viewArg(JsonObject a) {
    JsonElement e = a.get("quest_view");
    if (e == null || e.isJsonNull())
      throw new IllegalArgumentException(
          "[ARGUMENT_REQUIRED] 需要 quest_view：先 quest_book_state 取最新 quest_view；或改用 query"
              + " 一步式（quest_book_open/quest_book_act）");
    try {
      long v = e.getAsLong();
      if (v > 0) return v;
    } catch (RuntimeException ignored) {
      /* Reported below. */
    }
    throw new IllegalArgumentException(
        "[ARGUMENT_INVALID] quest_view 必须是正整数：先 quest_book_state 取最新编号，禁止复用旧编号");
  }

  /** Read {@code widget} with a readable Chinese reason instead of a JsonNull failure. */
  public static int widgetArg(JsonObject a) {
    JsonElement e = a.get("widget");
    if (e == null || e.isJsonNull())
      throw new IllegalArgumentException(
          "[ARGUMENT_REQUIRED] 需要 widget 编号：先 quest_book_state 取 widgets[].widget 再操作");
    try {
      int w = e.getAsInt();
      if (w >= 0) return w;
    } catch (RuntimeException ignored) {
      /* Reported below. */
    }
    throw new IllegalArgumentException(
        "[ARGUMENT_INVALID] widget 必须是非负整数：先 quest_book_state 取最新控件编号");
  }

  public static JsonObject inspect() {
    return inspect(0, 64, "");
  }

  public static JsonObject inspect(int offset, int limit, String query) {
    refresh();
    var o = new JsonObject();
    o.addProperty("quest_view", view);
    o.addProperty("screen_class", observedScreen.getClass().getName());
    o.addProperty("title", Minecraft.getInstance().screen.getTitle().getString());
    o.addProperty("source", "client_visible_ftb_widgets");
    o.addProperty("progress_complete", false);
    try {
      Object gui = call(observedScreen, "getGui");
      Object quest = call(gui, "getViewedQuest");
      o.addProperty("viewing_quest", quest != null);
      if (quest != null && call(quest, "getTitle") instanceof Component title)
        o.addProperty("viewed_quest_title", Rules.clean(title.getString(), 1500));
    } catch (ReflectiveOperationException e) {
      o.addProperty("viewing_quest_known", false);
    }
    o.addProperty(
        "hint", "可见控件不是全任务进度。按label/class选择章节、任务或奖励；点击后重新观察；背包变化另核验。观看中按F6退出观看后可人工点击（以实际绑定为准）。");
    var filtered =
        observed.stream()
            .filter(
                r ->
                    r.data
                        .toString()
                        .toLowerCase(Locale.ROOT)
                        .contains(query.toLowerCase(Locale.ROOT)))
            .toList();
    int start = Math.min(filtered.size(), Math.max(0, offset));
    int end = Math.min(filtered.size(), start + Math.max(1, Math.min(128, limit)));
    var a = new JsonArray();
    for (Row r : filtered.subList(start, end)) a.add(r.data.deepCopy());
    o.add("widgets", a);
    o.addProperty("total_matching", filtered.size());
    o.addProperty("total_observed", observed.size());
    o.addProperty("next_offset", end < filtered.size() ? end : -1);
    o.addProperty("tree_may_be_truncated", treeTruncated);
    o.addProperty("viewport_width", Minecraft.getInstance().getWindow().getGuiScaledWidth());
    o.addProperty("viewport_height", Minecraft.getInstance().getWindow().getGuiScaledHeight());
    o.addProperty(
        "navigation",
        "已观察QuestButton可用quest_book_open语义打开（允许屏外）；其他按钮仍要求实际可见。图形浏览用game_input拖动/滚轮；坐标已包含父链滚动，操作后重读。");
    PendingSubmit submit = pendingSubmit;
    if (submit != null && submit.view != null) o.add("last_submit", submit.view.deepCopy());
    return o;
  }

  public static String click(long expected, int index) {
    return click(expected, index, false);
  }

  public static String open(long expected, int index, String query) {
    if (index < 0) {
      QuestMatch.Resolution match = QuestMatch.resolve(entriesFrom(observed), query);
      if (!match.matched()) throw new IllegalArgumentException(match.message());
      index = match.widget();
    }
    return click(expected, index, true);
  }

  /**
   * One-step open: read the current quest tree, resolve the query to a unique visible quest button,
   * and open its detail. No prior quest_view is required.
   */
  public static String open(String query) {
    if (!available()) throw new IllegalArgumentException("[QUEST_SCREEN_REQUIRED] 请先打开FTB任务书主界面");
    refresh();
    QuestMatch.Resolution match = QuestMatch.resolve(entriesFrom(observed), query);
    if (!match.matched()) throw new IllegalArgumentException(match.message());
    return click(view, match.widget(), true);
  }

  /** Quest id currently open in the detail page, or -1 when no detail is open. */
  static long viewedQuestId() {
    try {
      Object gui = call(Minecraft.getInstance().screen, "getGui");
      Object quest = call(gui, "getViewedQuest");
      if (quest == null) return -1;
      return ((Number) call(quest, "getId")).longValue();
    } catch (ReflectiveOperationException | RuntimeException e) {
      return -1;
    }
  }

  /** Quest title currently open in the detail page, or an empty string. */
  static String viewedQuestTitle() {
    try {
      Object gui = call(Minecraft.getInstance().screen, "getGui");
      Object quest = call(gui, "getViewedQuest");
      if (quest == null) return "";
      return call(quest, "getTitle") instanceof Component title ? title.getString() : "";
    } catch (ReflectiveOperationException | RuntimeException e) {
      return "";
    }
  }

  /**
   * Send one wheel event to the quest screen.
   *
   * <p>The quest book is a plain Screen, not a menu, so gui_scroll cannot address it. FTB lays the
   * chapter list on the left and the quest tree on the right; both react to wheel events, and the
   * tree zooms while CTRL is held. FTB dispatches the wheel through widget mouse-over flags rather
   * than through the event coordinates, so the virtual pointer is moved into the target panel
   * before the event is sent, and only once.
   *
   * @param area "chapters" for the left sidebar, "tree" for the quest tree
   * @param zoom hold CTRL for the duration of the event (tree zoom only)
   */
  public static String scroll(long expected, String area, int direction, boolean zoom) {
    if (!available()) throw new IllegalArgumentException("[QUEST_SCREEN_REQUIRED] 请先打开FTB任务书主界面");
    String areaError = ScrollTarget.validate(area);
    if (areaError != null) throw new IllegalArgumentException(areaError);
    if (direction != -1 && direction != 1)
      throw new IllegalArgumentException("[ARGUMENT_INVALID] direction须为-1或1");
    var screen = Minecraft.getInstance().screen;
    if (expected != view || System.currentTimeMillis() - at > 15000 || screen != observedScreen)
      throw new IllegalArgumentException(
          ViewReject.stale("请重新quest_book_state，不能复用旧视图", expected, view));
    int w = Minecraft.getInstance().getWindow().getGuiScaledWidth();
    int h = Minecraft.getInstance().getWindow().getGuiScaledHeight();
    boolean chapters = "chapters".equals(area);
    var plan =
        ScrollTarget.plan(
            panelRect(chapters ? "chapterPanel" : "questPanel"), w, h, chapters ? 0.06 : 0.62);
    if (chapters && plan.sidebarCollapsed)
      throw new IllegalArgumentException(
          "[SIDEBAR_COLLAPSED] 章节列表当前收起；先展开章节栏再滚动，或改用 area=tree；未发送滚轮");
    // Zoom is a tree-view gesture only; asking for it on the chapter list is ignored
    // rather than silently applying CTRL to a panel that does not read it.
    boolean ctrl = zoom && !chapters;
    boolean ignoredZoom = zoom && chapters;
    boolean heldCtrl = false;
    try {
      // FTB chooses the receiving panel from mouse-over flags, so aim the virtual pointer first.
      ScreenInput.position(plan.x, plan.y);
      if (ctrl) {
        // Arm the live() bypass before begin(): the first tick that injects CTRL must
        // already see scrollInput, since this path is not a GameInputJob.
        VirtualInput.scrollInput = true;
        VirtualInput.begin(java.util.Set.of(341));
        heldCtrl = true;
      }
      boolean handled = screen.mouseScrolled(plan.x, plan.y, direction);
      view++;
      observedScreen = null;
      return (handled ? "已发送一次" : "已发送一次但目标面板未处理")
          + (ctrl ? "Ctrl+滚轮" : "滚轮")
          + (ignoredZoom ? "（zoom 仅对 area=tree 生效，本次按普通滚轮发送）" : "")
          + "（area="
          + (chapters ? "chapters" : "tree")
          + ", direction="
          + direction
          + ", x="
          + plan.x
          + ", y="
          + plan.y
          + "）；可见内容请重新 quest_book_state 核对，不自动重放。";
    } finally {
      VirtualInput.scrollInput = false;
      if (heldCtrl) VirtualInput.clear();
    }
  }

  /**
   * Read one quest-screen panel rectangle as {@code {x,y,width,height}}.
   *
   * <p>Layout is owned by FTB, so the panel is located through its field and widget accessors;
   * {@code null} means the rectangle is unavailable and the caller falls back to a viewport
   * fraction.
   */
  private static int[] panelRect(String field) {
    var screen = Minecraft.getInstance().screen;
    if (screen == null) return null;
    try {
      Object gui = call(screen, "getGui");
      Object panel;
      try {
        panel = gui.getClass().getField(field).get(gui);
      } catch (NoSuchFieldException e) {
        var declared = gui.getClass().getDeclaredField(field);
        declared.trySetAccessible();
        panel = declared.get(gui);
      }
      return new int[] {
        ((Number) call(panel, "getX")).intValue(),
        ((Number) call(panel, "getY")).intValue(),
        ((Number) call(panel, "getWidth")).intValue(),
        ((Number) call(panel, "getHeight")).intValue()
      };
    } catch (ReflectiveOperationException | RuntimeException e) {
      return null;
    }
  }

  private static String click(long expected, int index, boolean semantic) {
    var fresh = rows();
    if (expected != view
        || System.currentTimeMillis() - at > 15000
        || Minecraft.getInstance().screen != observedScreen
        || !signature(fresh).equals(stamp)
        || index < 0
        || index >= observed.size())
      throw new IllegalArgumentException(
          ViewReject.stale("请重新quest_book_state，不能复用旧控件", expected, view));
    Row r = observed.get(index);
    if (fresh.get(index).widget != r.widget
        || !r.data.get("button").getAsBoolean()
        || !r.data.get("active").getAsBoolean()
        || (semantic
            ? !r.data.get("semantic_open").getAsBoolean()
            : !r.data.get("visible").getAsBoolean()))
      throw new IllegalArgumentException("[GUI_TARGET_INVALID] 目标不是当前可见启用按钮");
    String role = r.data.get("role").getAsString();
    String label = r.data.get("label").getAsString();
    if (label.isEmpty()
        && !Set.of("task_action", "claim_reward", "claim_all_rewards", "open_quest", "open_chapter")
            .contains(role))
      throw new IllegalArgumentException(
          "[GUI_TARGET_INVALID] 该控件没有可见标题且不是任务/章节/奖励按钮，未点击；先 quest_book_state 核对控件语义后再选择 widget");
    long viewed = viewedQuestId();
    String buttonQuest =
        r.data.has("object_quest_id") ? r.data.get("object_quest_id").getAsString() : "";
    if (viewed > 0
        && (role.equals("task_action") || role.equals("claim_reward"))
        && !buttonQuest.isEmpty()
        && !buttonQuest.equals(String.valueOf(viewed)))
      throw new IllegalArgumentException(
          "[GUI_TARGET_INVALID] 目标按钮不属于当前打开的详情页（按钮章节#"
              + buttonQuest
              + "，当前详情#"
              + viewed
              + "）；请先 quest_book_state 打开目标任务后再选择 widget");
    view++;
    observedScreen = null; // Consume before invocation: never replay after an exception.
    try {
      Class<?> mouse = Class.forName("dev.ftb.mods.ftblibrary.ui.input.MouseButton");
      Object left = mouse.getMethod("get", int.class).invoke(null, 0);
      var click = r.widget.getClass().getMethod("onClicked", mouse);
      click.trySetAccessible();
      click.invoke(r.widget, left);
      String detail = "";
      if (semantic) {
        try {
          Object gui = call(Minecraft.getInstance().screen, "getGui");
          Object quest = call(gui, "getViewedQuest");
          detail =
              "；客户端当前详情="
                  + (quest != null && call(quest, "getTitle") instanceof Component title
                      ? title.getString()
                      : "未观察到已打开详情");
        } catch (ReflectiveOperationException | RuntimeException e) {
          detail = "；详情状态尚未确认，请重新quest_book_state";
        }
      }
      String shown = label;
      if (shown.isEmpty())
        shown =
            role
                + (r.data.has("object_title")
                    ? "《" + r.data.get("object_title").getAsString() + "》"
                    : "");
      return "已触发FTB客户端按钮一次：" + shown + detail + "；仅确认点击调用，奖励领取未核验。请重新观察任务书和背包，勿盲目重试。";
    } catch (ReflectiveOperationException | LinkageError e) {
      throw new IllegalArgumentException("[QUEST_CLICK_UNVERIFIED] 控件调用异常，可能已生效；先观察，不重复点击");
    }
  }

  /**
   * Sends FTB's own task submit packet for one observed task button, then schedules a delayed
   * client-side progress/completion re-read. Same one-shot freshness contract as {@link #click}.
   */
  public static String submit(long expected, int index) {
    var fresh = rows();
    if (expected != view
        || System.currentTimeMillis() - at > 15000
        || Minecraft.getInstance().screen != observedScreen
        || !signature(fresh).equals(stamp)
        || index < 0
        || index >= observed.size())
      throw new IllegalArgumentException(
          ViewReject.stale("请重新quest_book_state，不能复用旧控件", expected, view));
    Row r = observed.get(index);
    if (fresh.get(index).widget != r.widget
        || !r.data.get("button").getAsBoolean()
        || !r.data.get("active").getAsBoolean()
        || !r.data.get("visible").getAsBoolean()
        || !"task_action".equals(r.data.get("role").getAsString()))
      throw new IllegalArgumentException("[GUI_TARGET_INVALID] 目标不是当前可见启用的FTB任务勾选按钮");
    long viewed = viewedQuestId();
    String buttonQuest =
        r.data.has("object_quest_id") ? r.data.get("object_quest_id").getAsString() : "";
    if (viewed > 0 && !buttonQuest.isEmpty() && !buttonQuest.equals(String.valueOf(viewed)))
      throw new IllegalArgumentException(
          "[GUI_TARGET_INVALID] 目标按钮不属于当前打开的详情页（按钮章节#"
              + buttonQuest
              + "，当前详情#"
              + viewed
              + "）；请先 quest_book_state 打开目标任务后再提交");
    view++;
    observedScreen = null; // Consume before sending: never replay after an exception.
    Object gui, file, team;
    try {
      gui = call(Minecraft.getInstance().screen, "getGui");
      file = readField(gui, "file");
      team = readField(file, "selfTeamData");
    } catch (ReflectiveOperationException | LinkageError e) {
      throw new IllegalArgumentException(
          "[QUEST_API_UNSUPPORTED] FTB客户端任务数据不可读：" + e.getClass().getSimpleName());
    }
    return sendSubmit(r.widget, file, team);
  }

  private record SubmitPlan(Object task, Object quest, JsonObject snapshot) {}

  private static SubmitPlan prepareSubmit(Object widget, Object file, Object team) {
    Object task, quest;
    long taskId, questId;
    try {
      task = readField(widget, "task");
      quest = call(task, "getQuest");
      taskId = ((Number) call(task, "getId")).longValue();
      questId = ((Number) call(quest, "getId")).longValue();
    } catch (ReflectiveOperationException | LinkageError e) {
      throw new IllegalArgumentException(
          "[QUEST_API_UNSUPPORTED] 无法读取FTB任务对象：" + e.getClass().getSimpleName());
    }
    var snapshot = new JsonObject();
    snapshot.addProperty("task_id", taskId);
    snapshot.addProperty("quest_id", questId);
    snap(snapshot, "task_title", () -> title(task));
    snap(snapshot, "quest_title", () -> title(quest));
    snap(snapshot, "task_valid", () -> call(task, "isValid"));
    snap(snapshot, "max_progress", () -> call(task, "getMaxProgress"));
    snap(snapshot, "progress_before", () -> invokeRead(team, "getProgress", task));
    snap(snapshot, "completed_before", () -> invokeRead(team, "isCompleted", task));
    snap(snapshot, "quest_completed_before", () -> invokeRead(team, "isCompleted", quest));
    snap(snapshot, "can_start", () -> invokeRead(team, "canStartTasks", quest));
    snap(snapshot, "deps_complete", () -> invokeRead(team, "areDependenciesComplete", quest));
    snap(snapshot, "locked", () -> call(team, "isLocked"));
    snap(snapshot, "editable", () -> call(file, "canEdit"));
    snap(snapshot, "auto_submit", () -> call(task, "autoSubmitOnPlayerTick"));
    return new SubmitPlan(task, quest, snapshot);
  }

  private static void sendSubmitPacket(Object widget, JsonObject snapshot) {
    long taskId = snapshot.get("task_id").getAsLong();
    try {
      Class<?> message = Class.forName("dev.ftb.mods.ftbquests.net.SubmitTaskMessage");
      Object packet;
      try {
        packet = message.getConstructor(long.class).newInstance(taskId);
      } catch (NoSuchMethodException fallback) {
        var constructor = message.getDeclaredConstructor(long.class);
        constructor.trySetAccessible();
        packet = constructor.newInstance(taskId);
      }
      send(packet);
    } catch (ReflectiveOperationException | LinkageError e) {
      throw new IllegalArgumentException(
          "[QUEST_API_UNSUPPORTED] 无法发送FTB任务提交包：" + e.getClass().getSimpleName());
    }
    try {
      call(widget, "playClickSound");
    } catch (ReflectiveOperationException | RuntimeException ignored) {
      /* Click sound is cosmetic only. */
    }
  }

  private static Object[] fileTeam() {
    try {
      Object gui = call(Minecraft.getInstance().screen, "getGui");
      Object file = readField(gui, "file");
      Object team = readField(file, "selfTeamData");
      return new Object[] {file, team};
    } catch (ReflectiveOperationException | LinkageError e) {
      throw new IllegalArgumentException(
          "[QUEST_API_UNSUPPORTED] FTB客户端任务数据不可读：" + e.getClass().getSimpleName());
    }
  }

  private static String sendSubmit(Object widget, Object file, Object team) {
    SubmitPlan plan = prepareSubmit(widget, file, team);
    sendSubmitPacket(widget, plan.snapshot());
    var pending = new PendingSubmit(team, plan.task(), plan.quest(), plan.snapshot());
    pending.view =
        QuestTaskReceipt.lastSubmit(plan.snapshot(), null, null, null, "提交已发送，等待客户端复核进度/完成状态");
    pendingSubmit = pending;
    return QuestTaskReceipt.receipt(plan.snapshot(), SUBMIT_RECHECK_TICKS);
  }

  /**
   * One-step quest action: open the uniquely matched quest by query, then submit all of its task
   * buttons or claim all of its rewards. Only FTB's own packets are sent; every result is a client
   * view confirmation, never a server-side guarantee.
   */
  public static Actions.Job act(JsonObject a, Consumer<String> done) {
    String query = arg(a, "query").trim();
    String action = arg(a, "action").trim().toLowerCase(Locale.ROOT);
    if (query.isEmpty())
      throw new IllegalArgumentException(
          "[ARGUMENT_REQUIRED] 需要 query：给出任务标题一步式定位（多匹配会列出候选 widget）");
    if (!action.equals("submit") && !action.equals("claim_all"))
      throw new IllegalArgumentException(
          "[ARGUMENT_REQUIRED] action 仅支持 submit 或 claim_all（query 必填：任务标题一步式定位）");
    if (!available()) throw new IllegalArgumentException("[QUEST_SCREEN_REQUIRED] 请先打开FTB任务书主界面");
    return new ActJob(query, action, done);
  }

  private static void sendClaim(long rewardId) {
    try {
      Class<?> message = Class.forName("dev.ftb.mods.ftbquests.net.ClaimRewardMessage");
      Object packet;
      try {
        packet = message.getConstructor(long.class, boolean.class).newInstance(rewardId, true);
      } catch (NoSuchMethodException fallback) {
        var constructor = message.getDeclaredConstructor(long.class, boolean.class);
        constructor.trySetAccessible();
        packet = constructor.newInstance(rewardId, true);
      }
      send(packet);
    } catch (ReflectiveOperationException | LinkageError e) {
      throw new IllegalArgumentException(
          "[QUEST_API_UNSUPPORTED] 无法发送FTB奖励领取包：" + e.getClass().getSimpleName());
    }
  }

  private static Long asLong(Object value) {
    return value instanceof Number number ? number.longValue() : null;
  }

  private static Boolean asBool(Object value) {
    return value instanceof Boolean flag ? flag : null;
  }

  private static final class ActJob extends Actions.Job {
    private final String query;
    private final String action;
    private int phase;
    private int wait;
    private long questId = -1;
    private String questTitle = "";
    private Object team;
    private final List<SubmitPlan> submits = new ArrayList<>();
    private final List<String> skipped = new ArrayList<>();
    private final List<JsonObject> results = new ArrayList<>();
    private final List<Row> rewards = new ArrayList<>();
    private int rewardIndex;
    private Row verifying;

    ActJob(String query, String action, Consumer<String> done) {
      super(done);
      this.query = query;
      this.action = action;
    }

    @Override
    void tick() {
      try {
        if (ticks > 60 + 12 * (submits.size() + rewards.size())) {
          finish(partialSummary());
          return;
        }
        if (wait > 0) {
          wait--;
          return;
        }
        switch (phase) {
          case 0 -> openPhase();
          case 1 -> readPhase();
          case 2 -> verifySubmit();
          default -> verifyClaim();
        }
      } catch (RuntimeException e) {
        String message = e.getMessage();
        finish(
            message == null || message.isBlank()
                ? "失败：[QUEST_API_UNSUPPORTED] 任务书操作异常"
                : message.startsWith("失败") ? message : "失败：" + message);
      }
    }

    private void openPhase() {
      if (!available()) throw new IllegalArgumentException("[QUEST_SCREEN_REQUIRED] 请先打开FTB任务书主界面");
      refresh();
      questId = viewedQuestId();
      questTitle = viewedQuestTitle();
      if (questId > 0 && QuestMatch.matches(questTitle, query)) {
        phase = 1;
        wait = 1;
        return;
      }
      QuestMatch.Resolution match = QuestMatch.resolve(entriesFrom(observed), query);
      if (!match.matched()) throw new IllegalArgumentException(match.message());
      click(view, match.widget(), true);
      phase = 1;
      wait = 3;
    }

    private void readPhase() {
      refresh();
      questId = viewedQuestId();
      questTitle = viewedQuestTitle();
      if (questId <= 0)
        throw new IllegalArgumentException(
            "[GUI_TARGET_INVALID] 未能打开FTB任务详情页；请重新 quest_book_state 后用"
                + " quest_book_open/quest_task_submit 指定 widget");
      if (!QuestMatch.matches(questTitle, query))
        throw new IllegalArgumentException(
            "[QUEST_AMBIGUOUS] 打开的详情页《"
                + questTitle
                + "》与 query《"
                + query
                + "》不匹配；先 quest_book_state 核对后再操作");
      if (action.equals("submit")) submitPhase();
      else claimPhase();
    }

    private void submitPhase() {
      Object[] ft = fileTeam();
      team = ft[1];
      for (Row row : observed) {
        JsonObject data = row.data;
        if (!"task_action".equals(data.get("role").getAsString())
            || !data.get("active").getAsBoolean()) continue;
        if (data.has("completed_client") && data.get("completed_client").getAsBoolean()) {
          skipped.add(
              data.has("object_title")
                  ? data.get("object_title").getAsString()
                  : data.has("object_id") ? "task#" + data.get("object_id").getAsString() : "未知任务");
          continue;
        }
        SubmitPlan plan = prepareSubmit(row.widget, ft[0], team);
        sendSubmitPacket(row.widget, plan.snapshot());
        submits.add(plan);
      }
      if (submits.isEmpty()) {
        if (!skipped.isEmpty()) {
          finish(summary("submit"));
          return;
        }
        throw new IllegalArgumentException(
            "[GUI_TARGET_INVALID] 当前详情页没有可提交的任务勾选按钮（可能已完成/自动提交/未解锁）；先 quest_book_state 核对详情页"
                + " widget");
      }
      phase = 2;
      wait = SUBMIT_RECHECK_TICKS;
    }

    private void claimPhase() {
      if (rewards.isEmpty()) {
        boolean anyClaimed = false;
        for (Row row : observed) {
          JsonObject data = row.data;
          if (!"claim_reward".equals(data.get("role").getAsString())) continue;
          if (data.has("claimed_client") && data.get("claimed_client").getAsBoolean())
            anyClaimed = true;
          if (!data.get("active").getAsBoolean()) continue;
          if (!data.has("can_claim_client") || !data.get("can_claim_client").getAsBoolean())
            continue;
          if (!data.has("object_id")) continue;
          rewards.add(row);
        }
        if (rewards.isEmpty()) {
          if (anyClaimed) {
            finish(summary("claim_all"));
            return;
          }
          throw new IllegalArgumentException(
              "[GUI_TARGET_INVALID] 当前详情页没有可领取的奖励按钮（可能未完成/已领取）；先 quest_book_state 核对详情页 widget");
        }
      }
      if (rewardIndex >= rewards.size()) {
        finish(summary("claim_all"));
        return;
      }
      Row row = rewards.get(rewardIndex);
      long rewardId;
      try {
        rewardId = Long.parseLong(row.data.get("object_id").getAsString());
      } catch (RuntimeException e) {
        throw new IllegalArgumentException("[QUEST_API_UNSUPPORTED] 奖励按钮缺少可读取的 reward id，未发送领取包");
      }
      sendClaim(rewardId);
      verifying = row;
      phase = 3;
      wait = 5;
    }

    private void verifySubmit() {
      for (SubmitPlan plan : submits) {
        Long progressAfter = null;
        Boolean completedAfter = null;
        Boolean depsAfter = null;
        String note = "已发送提交包并复核客户端状态；依赖未完成时FTB只记进度不标记完成，服务端结果另核验";
        try {
          progressAfter = asLong(invokeRead(team, "getProgress", plan.task()));
          completedAfter = asBool(invokeRead(team, "isCompleted", plan.task()));
          depsAfter = asBool(invokeRead(team, "areDependenciesComplete", plan.quest()));
        } catch (ReflectiveOperationException | RuntimeException e) {
          note = "提交已发送，但客户端复核读取失败：" + e.getClass().getSimpleName() + "；服务端结果另核验";
        }
        results.add(
            QuestTaskReceipt.lastSubmit(
                plan.snapshot(), progressAfter, completedAfter, depsAfter, note));
      }
      finish(summary("submit"));
    }

    private void verifyClaim() {
      boolean confirmed = false;
      String rewardId = "";
      String rewardTitle = "";
      if (verifying != null) {
        JsonObject data = verifying.data;
        if (data.has("object_id")) rewardId = data.get("object_id").getAsString();
        if (data.has("object_title")) rewardTitle = data.get("object_title").getAsString();
        try {
          for (Row row : rows()) {
            if (!"claim_reward".equals(row.data.get("role").getAsString())) continue;
            if (!row.data.has("object_id")
                || !rewardId.equals(row.data.get("object_id").getAsString())) continue;
            boolean canClaim =
                row.data.has("can_claim_client") && row.data.get("can_claim_client").getAsBoolean();
            confirmed = !canClaim;
            break;
          }
        } catch (RuntimeException ignored) {
          /* Client view can lag a tick; the summary reports it as unconfirmed. */
        }
      }
      var item = new JsonObject();
      item.addProperty("reward_id", rewardId);
      item.addProperty("title", rewardTitle);
      item.addProperty("claimed_confirmed", confirmed);
      results.add(item);
      verifying = null;
      rewardIndex++;
      if (rewardIndex < rewards.size()) {
        phase = 1;
        wait = 1;
      } else finish(summary("claim_all"));
    }

    private String summary(String act) {
      var out = new JsonObject();
      out.addProperty("action", act);
      out.addProperty("query", query);
      if (questId > 0) out.addProperty("quest_id", questId);
      if (!questTitle.isEmpty()) out.addProperty("quest_title", questTitle);
      var array = new JsonArray();
      for (JsonObject result : results) array.add(result);
      out.add(act.equals("submit") ? "submitted" : "claimed", array);
      if (act.equals("submit") && !skipped.isEmpty()) {
        var already = new JsonArray();
        for (String title : skipped) already.add(title);
        out.add("skipped_completed", already);
      }
      out.addProperty(
          "note",
          act.equals("submit")
              ? "已发送提交包并复核客户端状态；依赖未完成时FTB只记进度不标记完成，服务端结果另核验。"
              : "客户端领取状态已重新读取；服务器/背包结果另核验。");
      return out.toString();
    }

    private String partialSummary() {
      var out = new JsonObject();
      out.addProperty("action", action);
      out.addProperty("query", query);
      if (questId > 0) out.addProperty("quest_id", questId);
      if (!questTitle.isEmpty()) out.addProperty("quest_title", questTitle);
      out.addProperty("note", "部分完成：动作已发送但未在时限内完成全部复核；先 quest_book_state 核对任务书/奖励状态，勿盲目重试。");
      return out.toString();
    }
  }

  private static void snap(JsonObject out, String key, Probe probe) {
    try {
      Object value = probe.read();
      if (value == null) out.add(key, JsonNull.INSTANCE);
      else if (value instanceof Number number) out.addProperty(key, number);
      else if (value instanceof Boolean flag) out.addProperty(key, flag);
      else out.addProperty(key, Rules.clean(String.valueOf(value), 200));
    } catch (Exception | LinkageError e) {
      out.add(key, JsonNull.INSTANCE);
    }
  }

  private static String title(Object object) throws ReflectiveOperationException {
    Object title = call(object, "getTitle");
    return title instanceof Component component ? Rules.clean(component.getString(), 200) : "";
  }

  private static void send(Object packet) throws ReflectiveOperationException {
    for (Class<?> c = packet.getClass(); c != null; c = c.getSuperclass())
      try {
        var method = c.getDeclaredMethod("sendToServer");
        method.trySetAccessible();
        method.invoke(packet);
        return;
      } catch (NoSuchMethodException ignored) {
      }
    throw new NoSuchMethodException("sendToServer");
  }

  /** Client tick hook: re-read FTB's client team data once the server round trip has settled. */
  static void tick() {
    PendingSubmit pending = pendingSubmit;
    if (pending == null || pending.done) return;
    if (--pending.ticksLeft > 0) return;
    pending.done = true;
    Long progressAfter = null;
    Boolean completedAfter = null;
    Boolean depsAfter = null;
    try {
      progressAfter = ((Number) invokeRead(pending.team, "getProgress", pending.task)).longValue();
    } catch (ReflectiveOperationException | RuntimeException ignored) {
      /* Unknown after-value is reported as null in last_submit. */
    }
    try {
      completedAfter = (Boolean) invokeRead(pending.team, "isCompleted", pending.task);
    } catch (ReflectiveOperationException | RuntimeException ignored) {
    }
    try {
      depsAfter = (Boolean) invokeRead(pending.team, "areDependenciesComplete", pending.quest);
    } catch (ReflectiveOperationException | RuntimeException ignored) {
    }
    pending.view =
        QuestTaskReceipt.lastSubmit(
            pending.snapshot,
            progressAfter,
            completedAfter,
            depsAfter,
            QuestTaskReceipt.verificationNote(
                pending.snapshot, progressAfter, completedAfter, depsAfter));
  }
}
