package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.util.*;

/** Quantities are updated only by executor receipts. Model summaries cannot change these totals. */
public final class LogisticsRecord {
  public String continuedBy = "";
  public String id = UUID.randomUUID().toString(),
      dimension = "",
      title = "",
      state = "RUNNING",
      phase = "准备",
      note = "";
  public JsonObject plan = new JsonObject();
  public int target, prepared, produced, fetched, delivered, carrying, destinationCount = -1;
  public long started = System.currentTimeMillis(), ended;
  public boolean uncertain;
  public List<String> receipts = new ArrayList<>();
  public List<String> events = new ArrayList<>();
  public Map<String, Integer> received = new LinkedHashMap<>(), sent = new LinkedHashMap<>();

  public void transfer(String receipt, boolean deposit, int moved) {
    transfer(receipt, deposit, moved, plan.has("item") ? plan.get("item").getAsString() : "");
  }

  public void transfer(String receipt, boolean deposit, int moved, String item) {
    if (moved < 0 || receipt == null || receipt.isBlank())
      throw new IllegalArgumentException("无效转移证据");
    if (receipts.contains(receipt)) throw new IllegalArgumentException("重复证据，不累计数量");
    receipts.add(receipt);
    (deposit ? sent : received).merge(item, moved, Integer::sum);
    if (plan.has("item") && plan.get("item").getAsString().equals(item)) {
      if (deposit) delivered += moved;
      else fetched += moved;
    }
  }

  public void produced(String receipt, int quantity) {
    if (quantity < 1 || receipt == null || receipt.isBlank() || receipts.contains(receipt))
      throw new IllegalArgumentException("新增产物证据无效或重复");
    receipts.add(receipt);
    produced += quantity;
  }

  public void event(String s) {
    events.add(Rules.clean(s, 220));
    while (events.size() > 32) events.remove(0);
  }

  public String summary() {
    boolean stock = plan.has("kind") && plan.get("kind").getAsString().equals("stock");
    return title
        + " · "
        + state
        + (plan.has("item")
            ? (stock
                ? " · 本轮补入 " + delivered + " · 库存目标 " + target
                : " · 已交付 " + delivered + " / " + target)
            : " ")
        + (destinationCount >= 0 ? " · 箱内确认 " + destinationCount : "")
        + (note.isBlank() ? "" : " · " + note);
  }
}
