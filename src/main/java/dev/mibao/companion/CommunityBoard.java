package dev.mibao.companion;

import java.util.*;

/** World-local goal queue. No automatic replay of persisted work after reconnect. */
public final class CommunityBoard {
  public static class Member {
    public String id = UUID.randomUUID().toString(),
        name = "",
        uuid = "",
        role = "MEMBER",
        delivery = "",
        report = "DEFAULT";
    public boolean enabled = true;
  }

  public static class Task {
    public String id = "",
        sender = "",
        name = "",
        text = "",
        dimension = "",
        delivery = "",
        state = "WAITING",
        note = "";
    public long created;
    public Map<String, Integer> requires = new LinkedHashMap<>();
    public boolean resume;
    public List<String> orders = new ArrayList<>();
  }

  public List<Task> tasks = new ArrayList<>();

  public Task add(
      String sender, String name, String text, String dimension, String delivery, long now) {
    text = Rules.clean(text, 2000).trim();
    if (text.isBlank()) throw new IllegalArgumentException("任务为空");
    for (var t : tasks)
      if (t.sender.equals(sender)
          && t.text.equals(text)
          && t.dimension.equals(dimension)
          && now - t.created < 30_000
          && Set.of("WAITING", "ACTIVE").contains(t.state)) return t;
    if (tasks.stream().filter(t -> Set.of("WAITING", "WAITING_MATERIALS").contains(t.state)).count()
            >= 24
        || tasks.stream()
                .filter(
                    t ->
                        t.sender.equals(sender)
                            && Set.of("WAITING", "WAITING_MATERIALS").contains(t.state))
                .count()
            >= 6) throw new IllegalArgumentException("待办已满：总计24项，每人6项");
    while (tasks.size() >= 64) {
      var old =
          tasks.stream()
              .filter(t -> !Set.of("WAITING", "ACTIVE", "WAITING_MATERIALS").contains(t.state))
              .findFirst();
      if (old.isEmpty()) throw new IllegalArgumentException("队列记录已满");
      tasks.remove(old.get());
    }
    var t = new Task();
    t.id = UUID.randomUUID().toString().substring(0, 12);
    t.sender = sender;
    t.name = name;
    t.text = text;
    t.dimension = dimension;
    t.delivery = delivery;
    t.created = now;
    tasks.add(t);
    return t;
  }

  public static boolean wake(
      Task t, String dimension, java.util.function.ToIntFunction<String> count) {
    if (!t.state.equals("WAITING_MATERIALS")
        || !t.dimension.equals(dimension)
        || t.requires.isEmpty()
        || !t.requires.entrySet().stream()
            .allMatch(e -> count.applyAsInt(e.getKey()) >= e.getValue())) return false;
    t.state = "WAITING";
    t.resume = true;
    return true;
  }

  public static int wakeAvailable(List<Task> tasks, String dimension, Map<String, Integer> stock) {
    var remaining = new LinkedHashMap<>(stock);
    for (var t : tasks)
      if (t.state.equals("WAITING") && t.resume && t.dimension.equals(dimension))
        t.requires.forEach((id, n) -> remaining.merge(id, -n, Integer::sum));
    int woke = 0;
    for (var t : tasks)
      if (wake(t, dimension, id -> remaining.getOrDefault(id, 0))) {
        woke++;
        t.requires.forEach((id, n) -> remaining.merge(id, -n, Integer::sum));
      }
    return woke;
  }

  public static int reserved(List<Task> tasks, String dimension, String item) {
    return tasks.stream()
        .filter(t -> t.state.equals("WAITING") && t.resume && t.dimension.equals(dimension))
        .mapToInt(t -> t.requires.getOrDefault(item, 0))
        .sum();
  }

  public Task active() {
    return tasks.stream().filter(t -> t.state.equals("ACTIVE")).findFirst().orElse(null);
  }

  public Task next(String dimension) {
    return tasks.stream()
        .filter(t -> t.state.equals("WAITING") && t.dimension.equals(dimension))
        .findFirst()
        .orElse(null);
  }

  public boolean change(String id, String requester, boolean admin, String replacement) {
    var t =
        tasks.stream()
            .filter(x -> x.id.equals(id))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("任务ID不存在"));
    if (!admin && !t.sender.equals(requester)) throw new IllegalArgumentException("只能修改自己尚未执行的任务");
    if (!Set.of("WAITING", "WAITING_MATERIALS").contains(t.state))
      throw new IllegalArgumentException("任务已开始或结束，先核实现状，不能当待办重放");
    if (replacement == null) t.state = "CANCELLED";
    else {
      String v = Rules.clean(replacement, 2000).trim();
      if (v.isBlank()) throw new IllegalArgumentException("修改内容为空");
      t.text = v;
      t.requires.clear();
      t.state = "WAITING";
    }
    return true;
  }

  public void stop() {
    for (var t : tasks)
      if (Set.of("ACTIVE", "WAITING", "WAITING_MATERIALS").contains(t.state)) {
        t.state = "STOPPED";
        t.note = "控制已停止，未自动恢复";
      }
  }

  public void reconnect() {
    for (var t : tasks)
      if (Set.of("ACTIVE", "WAITING", "WAITING_MATERIALS").contains(t.state)) {
        t.state = "SUSPENDED";
        t.note = "旧连接记录，仅作参考；重新观察后另下目标，不重放";
      }
  }

  public static Member resolve(List<Member> members, String uuid, String name) {
    if (uuid == null || uuid.isBlank()) return null;
    for (var m : members)
      if (m.enabled && (m.uuid.isBlank() ? m.name.equalsIgnoreCase(name) : m.uuid.equals(uuid)))
        return m;
    return null;
  }
}
