package dev.mibao.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure receipt text for interaction evidence. No Minecraft types, so offline tests can exercise the
 * confirmed/unconfirmed wording without a running game.
 */
final class InteractionReceipt {
  private InteractionReceipt() {}

  /**
   * True only for causally linked evidence: target block/machine change, or a drop that appeared
   * right at the target soon after the action. Inventory deltas and unrelated nearby drops are
   * notes, never confirmation.
   */
  static boolean confirmed(JsonObject evidence) {
    return !confirmations(evidence).isEmpty();
  }

  /** Chinese single-line receipt: confirmed facts, or why nothing changed. */
  static String summarize(JsonObject evidence) {
    List<String> parts = confirmations(evidence);
    List<String> notes = notes(evidence);
    String suffix = notes.isEmpty() ? "" : "；附注（无因果归因，不作成功依据）：" + String.join("，", notes);
    if (parts.isEmpty()) return "未观察到目标方块/机器变化或目标处新掉落" + suffix;
    return "已确认：" + String.join("；", parts) + suffix;
  }

  /** Delayed readout of the two evidence samples, appended to unconfirmed receipts. */
  static String readout(JsonObject evidence) {
    if (evidence == null) return "读数=(无观测数据)";
    List<String> parts = new ArrayList<>();
    String before = text(evidence, "block_before");
    String after = text(evidence, "block_after");
    if (!before.isBlank() || !after.isBlank())
      parts.add("方块 " + (before.isBlank() ? "?" : before) + "→" + (after.isBlank() ? "?" : after));
    if (flag(evidence, "adjacent_block_changed")) {
      String adjPos = text(evidence, "adjacent_position");
      String adjAfter = text(evidence, "adjacent_block_after");
      parts.add("邻格(" + adjPos + ")→" + adjAfter);
    }
    for (JsonElement element : array(evidence, "inventory")) {
      JsonObject row = object(element);
      long delta = number(row, "delta");
      if (delta != 0) parts.add("背包 " + text(row, "id") + (delta > 0 ? "+" : "") + delta);
    }
    for (JsonElement element : array(evidence, "dropped")) {
      JsonObject row = object(element);
      long count = number(row, "count");
      if (count > 0) parts.add("落地 " + text(row, "id") + "×" + count);
    }
    long ticks = number(evidence, "ticks");
    if (parts.isEmpty()) return "读数=无变化（观测 " + ticks + " tick）";
    parts.add("观测 " + ticks + " tick");
    return "读数=" + String.join("，", parts);
  }

  private static List<String> confirmations(JsonObject evidence) {
    List<String> parts = new ArrayList<>();
    if (evidence == null) return parts;
    if (flag(evidence, "block_changed")) {
      String after = text(evidence, "block_after");
      parts.add(after.isBlank() ? "目标方块变化" : "方块变为 " + after);
    }
    if (flag(evidence, "machine_changed")) parts.add("目标机器状态变化");
    for (JsonElement element : array(evidence, "dropped")) {
      JsonObject row = object(element);
      long count = number(row, "count");
      if (count > 0 && flag(row, "linked"))
        parts.add("目标处掉落 " + text(row, "id") + "×" + count + " 在地上（尚未入包，落地10tick后才可拾取）");
    }
    return parts;
  }

  /** Observations without causal attribution: inventory gains and drops away from the target. */
  private static List<String> notes(JsonObject evidence) {
    List<String> parts = new ArrayList<>();
    if (evidence == null) return parts;
    for (JsonElement element : array(evidence, "inventory")) {
      JsonObject row = object(element);
      long delta = number(row, "delta");
      if (delta > 0) parts.add("背包 " + text(row, "id") + "+" + delta);
    }
    for (JsonElement element : array(evidence, "dropped")) {
      JsonObject row = object(element);
      long count = number(row, "count");
      if (count > 0 && !flag(row, "linked")) parts.add("附近掉落 " + text(row, "id") + "×" + count);
    }
    if (flag(evidence, "adjacent_block_changed")
        && !flag(evidence, "block_changed")
        && !flag(evidence, "machine_changed")) {
      parts.add(
          "相邻格 "
              + text(evidence, "adjacent_position")
              + " 变为 "
              + text(evidence, "adjacent_block_after"));
    }
    return parts;
  }

  private static JsonArray array(JsonObject o, String key) {
    if (o == null) return new JsonArray();
    JsonElement e = o.get(key);
    return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
  }

  private static JsonObject object(JsonElement e) {
    return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
  }

  private static long number(JsonObject o, String key) {
    if (o == null) return 0;
    JsonElement e = o.get(key);
    if (e == null || !e.isJsonPrimitive()) return 0;
    try {
      return e.getAsLong();
    } catch (RuntimeException ex) {
      return 0;
    }
  }

  private static boolean flag(JsonObject o, String key) {
    if (o == null) return false;
    JsonElement e = o.get(key);
    return e != null
        && e.isJsonPrimitive()
        && e.getAsJsonPrimitive().isBoolean()
        && e.getAsBoolean();
  }

  private static String text(JsonObject o, String key) {
    if (o == null) return "";
    JsonElement e = o.get(key);
    return e == null || !e.isJsonPrimitive() ? "" : e.getAsString();
  }
}
