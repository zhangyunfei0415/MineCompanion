package dev.mibao.companion;

import java.util.*;

/** Bounded, deduplicated public announcements. The sidebar retains the full message. */
public final class ChatOutbox {
  private record Pending(String text, String scope) {}

  private final Deque<Pending> queue = new ArrayDeque<>();
  private final Map<String, Long> recent = new LinkedHashMap<>();
  private long lastSent = -3000;

  public void offer(String name, String owner, String text, String scope, long now) {
    String clean =
        Rules.clean(text, 1200).replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
    if (clean.isBlank()) return;
    String prefix =
        (owner == null || owner.isBlank()
                ? ""
                : "@" + Rules.clean(owner, 40).replaceAll("\\s+", " ") + " ")
            + Rules.clean(name, 40).replaceAll("\\s+", " ")
            + "：";
    String message = prefix + clean;
    if (message.length() > 250) {
      int end = 249;
      if (Character.isHighSurrogate(message.charAt(end - 1))) end--;
      message = message.substring(0, end) + "…";
    }
    String key = scope + "\n" + message;
    Long prior = recent.get(key);
    if (prior != null && now - prior < 15000) return;
    recent.put(key, now);
    while (recent.size() > 64) recent.remove(recent.keySet().iterator().next());
    if (queue.size() >= 16) queue.removeFirst();
    queue.addLast(new Pending(message, scope));
  }

  public String poll(String scope, long now) {
    while (!queue.isEmpty() && !queue.peekFirst().scope.equals(scope)) queue.removeFirst();
    if (queue.isEmpty() || now - lastSent < 3000) return null;
    lastSent = now;
    return queue.removeFirst().text;
  }

  public int size() {
    return queue.size();
  }

  public void clear() {
    queue.clear();
    recent.clear();
    lastSent = -3000;
  }
}
