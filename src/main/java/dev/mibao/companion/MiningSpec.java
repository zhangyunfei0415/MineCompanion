package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.util.Set;

/** Bounded public mining request, independent of Minecraft runtime state. */
record MiningSpec(
    String block,
    String item,
    String goal,
    String mode,
    String terrain,
    int count,
    int radius,
    int vertical,
    int maxBlocks,
    int seconds,
    int durability,
    int freeSlots,
    String delivery,
    String source,
    String toolPolicy,
    boolean craft,
    boolean lighting,
    boolean returnHome) {
  static MiningSpec parse(JsonObject args) {
    String block = SkillCatalog.required(args, "block");
    String goal = choice(args, "goal", "items", Set.of("blocks", "items", "deliver"));
    String policy = choice(args, "tool_policy", "auto", Set.of("auto", "fortune", "silk"));
    String fallbackDrop = GatheringCargo.commonDrop(block);
    String defaultItem =
        policy.equals("silk") && !block.startsWith("#")
            ? block
            : !fallbackDrop.isBlank() ? fallbackDrop : (!block.startsWith("#") ? block : "");
    String item = text(args, "item", defaultItem);
    String delivery = text(args, "delivery", "");
    if (!block.matches("#?[a-z0-9_.-]+:[a-z0-9_./-]+"))
      throw new IllegalArgumentException("[INVALID_ARGUMENT] block须为完整ID或#标签");
    if (!goal.equals("blocks") && item.isBlank())
      throw new IllegalArgumentException("[ITEM_REQUIRED] 产量/交付目标请提供item，例如minecraft:raw_iron");
    if (!item.isBlank() && !item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+(?:#sha256:[a-f0-9]{64})?"))
      throw new IllegalArgumentException("[INVALID_ARGUMENT] item须为物品ID或完整NBT指纹");
    if (goal.equals("deliver") && delivery.isBlank())
      throw new IllegalArgumentException("[DELIVERY_REQUIRED] 交付目标需要本世界命名交付箱");
    int count = SkillCatalog.bounded(args, "count", 32, 1, 4096);
    int limit =
        SkillCatalog.bounded(args, "max_blocks", Math.min(4096, Math.max(64, count * 4)), 1, 4096);
    if (goal.equals("blocks") && count > limit)
      throw new IllegalArgumentException("[INVALID_ARGUMENT] 方块目标超过max_blocks预算");
    return new MiningSpec(
        block,
        item,
        goal,
        choice(args, "mode", "vein", Set.of("surface", "vein", "baritone")),
        choice(args, "terrain", "detour", Set.of("detour", "modify")),
        count,
        SkillCatalog.bounded(args, "radius", 16, 2, 32),
        SkillCatalog.bounded(args, "vertical_range", 12, 1, 64),
        limit,
        SkillCatalog.bounded(args, "seconds", 1800, 30, 7200),
        SkillCatalog.bounded(args, "min_durability", 16, 1, 10000),
        SkillCatalog.bounded(args, "free_slots", 2, 1, 12),
        delivery,
        text(args, "source", ""),
        policy,
        flag(args, "auto_craft", true),
        flag(args, "lighting", true),
        flag(args, "return_home", true));
  }

  boolean contains(int originX, int originY, int originZ, int x, int y, int z) {
    return Math.abs((long) x - originX) <= radius
        && Math.abs((long) z - originZ) <= radius
        && Math.abs((long) y - originY) <= vertical;
  }

  String capacityBlockDetail(int available) {
    return "主背包空槽 " + available + " / 作业要求 " + freeSlots + "。"
        + (delivery.isBlank() ? "当前作业未配置交付箱。"
            : "已配置交付箱，但当前没有本次作业核实入包、可归仓的产物。");
  }

  private static String choice(JsonObject args, String key, String fallback, Set<String> allowed) {
    String value = text(args, key, fallback);
    if (!allowed.contains(value))
      throw new IllegalArgumentException("[INVALID_ARGUMENT] " + key + "允许值=" + allowed);
    return value;
  }

  private static String text(JsonObject args, String key, String fallback) {
    String value = args.has(key) ? args.get(key).getAsString().strip() : fallback;
    if (value.length() > 160)
      throw new IllegalArgumentException("[INVALID_ARGUMENT] " + key + "过长");
    return value;
  }

  private static boolean flag(JsonObject args, String key, boolean fallback) {
    return args.has(key) ? args.get(key).getAsBoolean() : fallback;
  }
}
