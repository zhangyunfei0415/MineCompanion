package dev.mibao.companion;

import java.util.Set;

/**
 * Explicit dismantling is separate from broad resource collection. No container-content guessing.
 */
public final class BreakPolicy {
  private static final Set<String> CONTAINERS =
      Set.of(
          "minecraft:chest",
          "minecraft:trapped_chest",
          "minecraft:barrel",
          "minecraft:furnace",
          "minecraft:blast_furnace",
          "minecraft:smoker",
          "minecraft:hopper",
          "minecraft:dispenser",
          "minecraft:dropper",
          "minecraft:brewing_stand");

  public static boolean simpleDecoration(String id) {
    return id.startsWith("minecraft:")
        && (id.endsWith("_sign")
            || id.endsWith("_banner")
            || id.endsWith("_bed")
            || id.equals("minecraft:flower_pot")
            || id.startsWith("minecraft:potted_"));
  }

  public static String denial(
      String id, boolean blockEntity, boolean targeted, boolean confirm, boolean freshEmpty) {
    if (id.equals("minecraft:spawner")
        || id.equals("minecraft:end_portal_frame")
        || id.equals("minecraft:bedrock")) return "[BLOCK_PROTECTED] 该目标不可由通用拆除执行器处理";
    if (!blockEntity || simpleDecoration(id)) return "";
    if (!targeted) return "[BLOCK_ENTITY_PROTECTED] 批量采集不拆存储/机器；使用定点break_block";
    if (!CONTAINERS.contains(id) && !(id.startsWith("minecraft:") && id.endsWith("shulker_box")))
      return "[UNSUPPORTED_MACHINE] 未识别机器的内容与拆除掉落，请使用已验证技能或人工处理";
    if (!confirm) return "[CONFIRM_REQUIRED] 拆容器需要confirm=true与刚打开核实的空库存";
    return freshEmpty ? "" : "[CONTAINER_OBSERVATION_REQUIRED] 先实际打开并核实空容器，再明确拆除；历史库存不能证明现在为空";
  }

  private BreakPolicy() {}
}
