package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.util.UUID;

public final class StockTarget {
  public String id = UUID.randomUUID().toString(),
      name = "库存维护",
      dimension = "",
      state = "未启用",
      lastOrder = "";
  public JsonObject plan = new JsonObject();
  public boolean enabled;
  public int intervalSeconds = 300, failures;
  public long nextCheck;

  public static boolean canDeliverCargo(int held, int retained, int cargo) {
    return retained >= 0 && cargo > 0 && held >= retained + cargo;
  }

  public void validate() {
    var p = LogisticsPlan.from(plan, "");
    if (!p.kind().equals("stock")) throw new IllegalArgumentException("库存目标须kind=stock");
    if (intervalSeconds < 60 || intervalSeconds > 86400)
      throw new IllegalArgumentException("间隔60..86400秒");
  }

  public static boolean due(
      boolean enabled, long now, long next, String expected, String current, boolean loaded) {
    return enabled && now >= next && expected.equals(current) && loaded;
  }

  public void settled(boolean success, long now) {
    failures = success ? 0 : failures + 1;
    nextCheck = now + intervalSeconds * 1000L * Math.min(4, 1 + failures);
    if (failures >= 3) {
      enabled = false;
      state = "连续3次受阻，已暂停；处理后手动启用";
    }
  }
}
