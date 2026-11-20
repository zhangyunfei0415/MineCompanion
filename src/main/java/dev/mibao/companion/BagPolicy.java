package dev.mibao.companion;

import java.util.*;
import java.util.regex.Pattern;

/** World-local stock preference, with mandatory and optional entries separated. */
public final class BagPolicy {
  private static final Pattern ITEM_PATTERN =
      Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+(#sha256:[a-f0-9]{64})?");

  public boolean autoBeforeOrders = false, unload = false;
  public String name = "基地后勤", source = "", destination = "";
  public int freeSlots = 4, minDurability = 8;
  public Map<String, Integer> desired = new LinkedHashMap<>();
  public Set<String> mandatory = new LinkedHashSet<>(), protectedItems = new LinkedHashSet<>();

  public BagPolicy copy() {
    var c = new BagPolicy();
    c.autoBeforeOrders = autoBeforeOrders;
    c.unload = unload;
    c.name = name;
    c.source = source;
    c.destination = destination;
    c.freeSlots = freeSlots;
    c.minDurability = minDurability;
    if (desired != null) c.desired.putAll(desired);
    if (mandatory != null) c.mandatory.addAll(mandatory);
    if (protectedItems != null) c.protectedItems.addAll(protectedItems);
    return c;
  }

  public int reserve(String item) {
    return desired.getOrDefault(item, desired.getOrDefault(item.split("#", 2)[0], 0));
  }

  public static int deficit(int desired, int actual) {
    return Math.max(0, desired - actual);
  }

  public static int surplus(int actual, int keep, boolean protectedItem) {
    return protectedItem ? 0 : Math.max(0, actual - keep);
  }

  public void validate() {
    if (name == null
        || name.isBlank()
        || name.length() > 80
        || source == null
        || destination == null
        || source.length() > 128
        || destination.length() > 128) throw new IllegalArgumentException("模板名1..80字符，地点名最多128字符");
    if (unload && destination.isBlank()) throw new IllegalArgumentException("启用归仓时需指定归仓地点");
    if (freeSlots < 0
        || freeSlots > 20
        || minDurability < 0
        || minDurability > 10000
        || desired.size() > 16) throw new IllegalArgumentException("空位0..20，耐久0..10000，物资最多16种");
    for (var e : desired.entrySet())
      if (!ITEM_PATTERN.matcher(e.getKey()).matches()
          || e.getValue() < 1
          || e.getValue() > 256) throw new IllegalArgumentException("常备物资需注册ID/完整指纹，数量1..256");
    if (!desired.keySet().containsAll(mandatory)) throw new IllegalArgumentException("必要项必须在常备清单内");
  }
}
