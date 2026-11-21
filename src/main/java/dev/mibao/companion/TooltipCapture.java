package dev.mibao.companion;

import com.google.gson.*;
import java.lang.reflect.Field;
import java.util.*;

/**
 * Pure Java capture buffer, deduplicating line cleaner, and type-based reflection helper for
 * tooltip probing. Zero Minecraft/Forge imports so JUnit tests run completely offline.
 */
public final class TooltipCapture {
  private static final Map<Class<?>, Field> FIELD_CACHE = new HashMap<>();

  private static boolean armed;
  private static boolean captured;
  private static final List<String> lines = new ArrayList<>();
  private static final List<String> customComponents = new ArrayList<>();
  private static JsonObject hoveredItem;

  private TooltipCapture() {}

  public static synchronized void start() {
    armed = true;
    captured = false;
    lines.clear();
    customComponents.clear();
    hoveredItem = null;
  }

  public static synchronized void reset() {
    armed = false;
    captured = false;
    lines.clear();
    customComponents.clear();
    hoveredItem = null;
  }

  public static synchronized boolean isArmed() {
    return armed;
  }

  public static synchronized boolean hasCaptured() {
    return captured;
  }

  public static synchronized void recordGather(
      List<String> rawLines, List<String> components, JsonObject item) {
    if (!armed) return;
    lines.clear();
    lines.addAll(cleanLines(rawLines));
    customComponents.clear();
    if (components != null) {
      for (String c : components) {
        String s = Rules.clean(c, 80).trim();
        if (!s.isEmpty() && customComponents.size() < 8) customComponents.add(s);
      }
    }
    hoveredItem = item == null ? null : item.deepCopy();
    captured = true;
  }

  public static synchronized void recordFallbackPre(
      List<String> rawLines, List<String> components, JsonObject item) {
    if (!armed || captured) return;
    recordGather(rawLines, components, item);
  }

  public static synchronized JsonObject consume() {
    JsonObject out = new JsonObject();
    out.addProperty("captured", captured);
    JsonArray arr = new JsonArray();
    for (String line : lines) arr.add(line);
    out.add("lines", arr);
    JsonArray comps = new JsonArray();
    for (String c : customComponents) comps.add(c);
    out.add("custom_components", comps);
    if (hoveredItem != null) out.add("item", hoveredItem.deepCopy());
    else out.add("item", JsonNull.INSTANCE);
    if (!captured) out.addProperty("reason", "no_standard_tooltip_rendered");
    reset();
    return out;
  }

  public static List<String> cleanLines(List<String> rawLines) {
    if (rawLines == null || rawLines.isEmpty()) return List.of();
    List<String> out = new ArrayList<>(Math.min(rawLines.size(), 24));
    for (String raw : rawLines) {
      if (out.size() >= 24) break;
      String cleaned = Rules.clean(stripFormattingCodes(raw), 300).trim();
      if (!cleaned.isEmpty()) out.add(cleaned);
    }
    return List.copyOf(out);
  }

  public static String stripFormattingCodes(String text) {
    if (text == null || text.indexOf('\u00a7') < 0) return text == null ? "" : text;
    StringBuilder sb = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\u00a7' && i + 1 < text.length()) {
        i++;
        continue;
      }
      sb.append(c);
    }
    return sb.toString();
  }

  /** Finds a declared field purely by its runtime class type (obfuscation-safe across Mojmap/SRG). */
  public static synchronized Field findDeclaredFieldByType(Class<?> owner, Class<?> fieldType) {
    if (owner == null || fieldType == null) return null;
    if (FIELD_CACHE.containsKey(owner)) return FIELD_CACHE.get(owner);
    Field found = null;
    for (Field f : owner.getDeclaredFields()) {
      if (fieldType.isAssignableFrom(f.getType())) {
        f.setAccessible(true);
        found = f;
        break;
      }
    }
    FIELD_CACHE.put(owner, found);
    return found;
  }
}
