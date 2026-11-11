package dev.mibao.companion;

import java.util.Set;

public final class TestCommandPolicy {
  private static final Set<String> GAMERULES = Set.of("keepInventory", "mobGriefing", "doFireTick");

  public record Recovery(
      String command, int x1, int y1, int z1, int x2, int y2, int z2, boolean teleport) {}

  public static boolean isRecovery(String input) {
    String s = input.startsWith("/") ? input.substring(1) : input;
    return Set.of("setblock", "fill", "tp").contains(s.strip().split("\\s+", 2)[0]);
  }

  public static Recovery recovery(String input, boolean confirm, int px, int py, int pz) {
    if (!confirm)
      throw new IllegalArgumentException("[CONFIRM_REQUIRED] 恢复指令需要confirm=true，仅用于用户授权的测试准备");
    String s = input.startsWith("/") ? input.substring(1) : input;
    if (s.length() > 256 || s.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("[ARGUMENT_INVALID] 单行恢复指令长度超限");
    String[] p = s.strip().split("\\s+");
    try {
      int start = p[0].equals("tp") ? 2 : 1;
      boolean tp = p[0].equals("tp"), fill = p[0].equals("fill");
      if (tp
          ? (p.length != 5 || !p[1].equals("@s"))
          : fill
              ? (p.length != 8 || !p[7].matches("(?:minecraft:)?air"))
              : (p.length != 5 || !p[0].equals("setblock") || !p[4].matches("(?:minecraft:)?air")))
        throw new IllegalArgumentException();
      int x = Integer.parseInt(p[start]),
          y = Integer.parseInt(p[start + 1]),
          z = Integer.parseInt(p[start + 2]);
      int x2 = fill ? Integer.parseInt(p[4]) : x,
          y2 = fill ? Integer.parseInt(p[5]) : y,
          z2 = fill ? Integer.parseInt(p[6]) : z;
      for (int[] v : new int[][] {{x, y, z}, {x2, y2, z2}})
        if (Math.abs((long) v[0] - px) > 64
            || Math.abs((long) v[1] - py) > 64
            || Math.abs((long) v[2] - pz) > 64) throw new IllegalArgumentException();
      long volume =
          (Math.abs((long) x - x2) + 1)
              * (Math.abs((long) y - y2) + 1)
              * (Math.abs((long) z - z2) + 1);
      if (!tp && volume > 64) throw new IllegalArgumentException();
      return new Recovery(String.join(" ", p), x, y, z, x2, y2, z2, tp);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException(
          "[RECOVERY_COMMAND_BOUNDS] 仅支持绝对整数坐标、64格内tp @s、setblock air或最多64格fill"
              + " air；不接受选择器/相对坐标/其他命令");
    }
  }

  public static String validate(String input) {
    String s = input.startsWith("/") ? input.substring(1) : input;
    if (s.isBlank() || s.length() > 256 || s.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("指令须为单行1..256字符");
    if (s.startsWith("gamerule ")) {
      String[] p = s.strip().split("\\s+");
      if (p.length == 2 && GAMERULES.contains(p[1])) return s;
      if (p.length == 3
          && GAMERULES.contains(p[1])
          && (p[2].equals("true") || p[2].equals("false"))) return s;
      throw new IllegalArgumentException(
          "[UNSUPPORTED_COMMAND] 仅开放keepInventory/mobGriefing/doFireTick的只读查询与布尔设置");
    }
    if (!Set.of("give", "gamemode", "difficulty", "time", "weather").contains(s.split(" ", 2)[0]))
      throw new IllegalArgumentException(
          "[UNSUPPORTED_COMMAND]"
              + " 测试指令支持give/gamemode/difficulty/time/weather；恢复指令另需confirm与范围检查，其他指令尚未开放");
    return s;
  }

  /**
   * @return the gamerule name when {@code input} is one of the allowed read-only queries; else "".
   */
  public static String readOnlyGamerule(String input) {
    if (input == null) return "";
    String s = input.startsWith("/") ? input.substring(1) : input;
    String[] p = s.strip().split("\\s+");
    if (p.length != 2 || !p[0].equals("gamerule") || !GAMERULES.contains(p[1])) return "";
    return p[1];
  }
}
