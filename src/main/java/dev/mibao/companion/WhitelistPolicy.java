package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Pure-Java validation and matching for agent-editable compatibility whitelists persisted in
 * Store.Config. Zero {@code net.minecraft.*} imports.
 */
public final class WhitelistPolicy {
  public static final int MAX_ENTRIES = 64;
  public static final Set<String> CATEGORIES =
      Set.of("left_tap", "generator_blocks", "sound_warn", "sound_info", "sound_ignore");

  private static final Pattern ENTRY_PATTERN =
      Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]*$");

  private static final Set<String> FORBIDDEN_LEFT_TAP =
      Set.of(
          "minecraft:air",
          "minecraft:cave_air",
          "minecraft:void_air",
          "minecraft:tnt",
          "minecraft:bedrock",
          "minecraft:command_block",
          "minecraft:repeating_command_block",
          "minecraft:chain_command_block",
          "minecraft:barrier",
          "minecraft:lava",
          "minecraft:water",
          "minecraft:spawner",
          "minecraft:dragon_egg");

  private static final Set<String> FORBIDDEN_GENERATOR =
      Set.of(
          "minecraft:air",
          "minecraft:cave_air",
          "minecraft:void_air",
          "minecraft:tnt",
          "minecraft:bedrock",
          "minecraft:command_block",
          "minecraft:repeating_command_block",
          "minecraft:chain_command_block",
          "minecraft:barrier",
          "minecraft:lava",
          "minecraft:water",
          "minecraft:chest",
          "minecraft:trapped_chest",
          "minecraft:barrel",
          "minecraft:hopper",
          "minecraft:shulker_box",
          "minecraft:spawner");

  private static final Set<String> FORBIDDEN_SOUND_IGNORE =
      Set.of(
          "minecraft:entity.creeper.primed",
          "minecraft:entity.tnt.primed",
          "minecraft:entity.generic.explode");

  private WhitelistPolicy() {}

  public static String normalizeEntry(String raw) {
    if (raw == null) return "";
    return raw.strip().toLowerCase(Locale.ROOT);
  }

  public static String validate(String category, String rawEntry) {
    if (category == null || !CATEGORIES.contains(category))
      throw new IllegalArgumentException(
          "[INVALID_ARGUMENT] category须为 left_tap/generator_blocks/sound_warn/sound_info/sound_ignore");
    String entry = normalizeEntry(rawEntry);
    if (entry.isEmpty())
      throw new IllegalArgumentException("[INVALID_ARGUMENT] 白名单条目不能为空");
    if (entry.contains("*"))
      throw new IllegalArgumentException("[WHITELIST_GUARD] 禁止使用通配符 *，请填写精确ID或命名空间前缀（如 modid:）");
    if (!ENTRY_PATTERN.matcher(entry).matches())
      throw new IllegalArgumentException(
          "[INVALID_ARGUMENT] 白名单格式须为 namespace:path 或 namespace: 前缀（仅小写字母/数字/_.-/）");
    if (entry.equals("minecraft:"))
      throw new IllegalArgumentException("[WHITELIST_GUARD] 禁止把整个 minecraft: 命名空间加入自定义白名单");
    if (category.equals("left_tap") && FORBIDDEN_LEFT_TAP.contains(entry))
      throw new IllegalArgumentException("[WHITELIST_GUARD] 禁止将危险或不可左键交互的方块加入 left_tap：" + entry);
    if (category.equals("generator_blocks") && FORBIDDEN_GENERATOR.contains(entry))
      throw new IllegalArgumentException(
          "[WHITELIST_GUARD] 禁止将空气/容器/危险方块加入 generator_blocks：" + entry);
    if (category.equals("sound_ignore") && FORBIDDEN_SOUND_IGNORE.contains(entry))
      throw new IllegalArgumentException("[WHITELIST_GUARD] 禁止忽略致命爆炸预警音效：" + entry);
    return entry;
  }

  public static boolean matchesPrefixOrExact(String id, Collection<String> entries) {
    if (id == null || id.isBlank() || entries == null || entries.isEmpty()) return false;
    String norm = normalizeEntry(id);
    for (String raw : entries) {
      String e = normalizeEntry(raw);
      if (e.isEmpty()) continue;
      if (e.endsWith(":") || e.endsWith(".")) {
        if (norm.startsWith(e)) return true;
      } else if (norm.equals(e)) {
        return true;
      }
    }
    return false;
  }

  public static void normalizeList(List<String> list, String category) {
    if (list == null) return;
    LinkedHashSet<String> clean = new LinkedHashSet<>();
    for (String raw : list) {
      try {
        clean.add(validate(category, raw));
      } catch (IllegalArgumentException ignored) {
      }
      if (clean.size() >= MAX_ENTRIES) break;
    }
    list.clear();
    list.addAll(clean);
  }

  public static boolean mutate(List<String> list, String category, String action, String rawEntry) {
    if (list == null) throw new IllegalArgumentException("[INVALID_ARGUMENT] 目标列表未初始化");
    String act = action == null ? "list" : action.strip().toLowerCase(Locale.ROOT);
    switch (act) {
      case "add" -> {
        String valid = validate(category, rawEntry);
        if (list.contains(valid)) return false;
        if (list.size() >= MAX_ENTRIES)
          throw new IllegalArgumentException("[WHITELIST_GUARD] 单类白名单最多 " + MAX_ENTRIES + " 项");
        list.add(valid);
        return true;
      }
      case "remove" -> {
        String norm = normalizeEntry(rawEntry);
        if (norm.isEmpty())
          throw new IllegalArgumentException("[INVALID_ARGUMENT] remove 需要提供 entry");
        return list.removeIf(s -> normalizeEntry(s).equals(norm));
      }
      case "reset" -> {
        if (list.isEmpty()) return false;
        list.clear();
        return true;
      }
      default -> throw new IllegalArgumentException(
          "[INVALID_ARGUMENT] action须为 list/add/remove/reset");
    }
  }

  public static JsonObject describe(
      List<String> leftTap,
      List<String> generatorBlocks,
      List<String> soundWarn,
      List<String> soundInfo,
      List<String> soundIgnore) {
    var out = new JsonObject();
    out.add("builtin_left_tap", jsonArray(List.of("functionalstorage:", "storagedrawers:")));
    out.add("builtin_generator_blocks", jsonArray(new TreeSet<>(GeneratorPolicy.GENERATOR_BLOCKS)));
    out.add("custom_left_tap", jsonArray(leftTap));
    out.add("custom_generator_blocks", jsonArray(generatorBlocks));
    out.add("custom_sound_warn", jsonArray(soundWarn));
    out.add("custom_sound_info", jsonArray(soundInfo));
    out.add("custom_sound_ignore", jsonArray(soundIgnore));
    out.addProperty("max_entries_per_category", MAX_ENTRIES);
    return out;
  }

  private static JsonArray jsonArray(Collection<String> items) {
    var arr = new JsonArray();
    if (items != null) for (String s : items) arr.add(s);
    return arr;
  }
}
