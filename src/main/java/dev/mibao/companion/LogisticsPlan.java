package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;

/**
 * An explicit, bounded objective. It contains named bindings, never old menu views or coordinates.
 */
public record LogisticsPlan(
    String kind, String item, String input, int count, String source, String destination, int low) {
  public LogisticsPlan {
    if (!Set.of("supply", "craft", "smelt", "deliver", "stock").contains(kind))
      throw new IllegalArgumentException("kind=supply/craft/smelt/deliver/stock");
    if (!item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+(#sha256:[a-f0-9]{64})?"))
      throw new IllegalArgumentException("指定注册ID或完整NBT指纹");
    if (count < 1 || count > (kind.equals("stock") ? 256 : 64) || low < 0 || low > count)
      throw new IllegalArgumentException("数量1..64（库存目标最多256），下限0..目标数");
    source = source == null ? "" : source.trim();
    destination = destination == null ? "" : destination.trim();
    input = input == null ? "" : input.trim();
    if (destination.isBlank() || destination.length() > 128 || source.length() > 128)
      throw new IllegalArgumentException("需要当前世界的交付地点，名称最多128字符");
    if (Set.of("supply", "stock").contains(kind) && source.isBlank())
      throw new IllegalArgumentException("搬运/维护需明确来源");
    if (!source.isBlank() && source.equals(destination))
      throw new IllegalArgumentException("来源与交付不能相同");
    if (Set.of("craft", "smelt").contains(kind) && item.contains("#"))
      throw new IllegalArgumentException("首版生产委托仅支持普通无NBT产物");
    if (kind.equals("smelt") && !input.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
      throw new IllegalArgumentException("熔炼需要明确输入注册ID");
  }

  public static LogisticsPlan from(JsonObject o, String fallback) {
    String kind = str(o, "kind", "supply");
    int count = num(o, "count", 32);
    return new LogisticsPlan(
        kind,
        str(o, "item", ""),
        str(o, "input", ""),
        count,
        str(o, "source", ""),
        str(o, "destination", fallback),
        num(o, "low", kind.equals("stock") ? count : 0));
  }

  public boolean needsRefill(int observed) {
    if (observed < 0) throw new IllegalArgumentException("先同步观察库存");
    return observed < low;
  }

  public int nextBatch(int observed) {
    if (observed < 0) throw new IllegalArgumentException("先同步观察库存");
    return Math.min(64, Math.max(0, count - observed));
  }

  public JsonObject json() {
    var o = new JsonObject();
    o.addProperty("kind", kind);
    o.addProperty("item", item);
    o.addProperty("input", input);
    o.addProperty("count", count);
    o.addProperty("source", source);
    o.addProperty("destination", destination);
    o.addProperty("low", low);
    return o;
  }

  private static String str(JsonObject o, String k, String d) {
    return o.has(k) ? o.get(k).getAsString() : d;
  }

  private static int num(JsonObject o, String k, int d) {
    try {
      return o.has(k) ? o.get(k).getAsBigDecimal().intValueExact() : d;
    } catch (Exception e) {
      throw new IllegalArgumentException(k + "必须为整数");
    }
  }
}
