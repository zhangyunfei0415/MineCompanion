package dev.mibao.companion;

import java.util.*;

/** Pure validation. Server permission is decided by the server, not guessed from OP level. */
public final class GameCommandPolicy {
  public record Plan(
      String command,
      String root,
      int[] bounds,
      String expectedBlock,
      String expectedMode,
      boolean teleport,
      long volume) {
    public double[] targetPosition() {
      if (!root.equals("tp") && !root.equals("teleport")) return null;
      String[] parts = command.split("\\s+");
      if (parts.length != 5 || !parts[1].equals("@s")) return null;
      return new double[] {Double.parseDouble(parts[2]), Double.parseDouble(parts[3]), Double.parseDouble(parts[4])};
    }
  }

  private static final Set<String> BASIC =
      Set.of("give", "gamemode", "difficulty", "time", "weather", "gamerule");

  public static boolean playerCommand(String input, CommandProfile profile) {
    if (input == null) return false;
    String command = input.strip();
    if (command.startsWith("/")) command = command.substring(1);
    String root = command.split("\\s+", 2)[0];
    String name = root.startsWith("minecraft:") ? root.substring(10) : root;
    // Administrative names cannot be reclassified by adding an alias to this list.
    return !BASIC.contains(name)
        && !Set.of("tp", "teleport", "setblock", "fill", "clone", "summon").contains(name)
        && profile.playerCommands != null
        && Arrays.asList(profile.playerCommands.split("[,，\\s]+")).contains(root);
  }

  public static Plan parse(String input, CommandProfile profile, double x, double y, double z) {
    int[] blockOrigin = {(int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)};
    if (input == null
        || input.isBlank()
        || input.length() > 1024
        || input.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("[INVALID_ARGUMENT] 指令须为单行1..1024字符");
    String s = input.strip();
    if (s.startsWith("/")) s = s.substring(1);
    String[] p = s.split("\\s+");
    String root = p[0];
    if (!root.matches("[a-z0-9_.:-]+"))
      throw new IllegalArgumentException("[INVALID_ARGUMENT] 指令名不合法");
    String name = root.startsWith("minecraft:") ? root.substring(10) : root;
    if (!profile.broadSelectors && s.matches("(?s).*@[aerp](?:\\b|\\[).*"))
      throw new IllegalArgumentException("[COMMAND_SELECTOR_DENIED] 当前未允许广泛对象选择器；@s可用");
    Set<String> players =
        new HashSet<>(
            Arrays.asList(
                (profile.playerCommands == null ? "" : profile.playerCommands).split("[,，\\s]+")));
    boolean tp = Set.of("tp", "teleport").contains(name);
    boolean area = Set.of("setblock", "fill", "clone").contains(name);
    if ((name.equals("clone") || name.equals("summon")) && !profile.advancedCommands)
      throw new IllegalArgumentException("[COMMAND_OPT_IN] 本世界未开启clone/summon，请在指令设置开启");
    if (!BASIC.contains(name)
        && !players.contains(root)
        && !tp
        && !area
        && !name.equals("summon")
        && !profile.customCommands)
      throw new IllegalArgumentException(
          "[COMMAND_NOT_ALLOWED] 本服未允许该指令：" + root + "；可由玩家添加别名或开启自定义单行指令");
    int[] bounds = null;
    String block = "", mode = "";
    long volume = 0;
    int radius = Math.max(1, Math.min(1024, profile.radius));
    int cap = Math.max(1, Math.min(32768, profile.maxBlocks));
    if (tp) {
      if (p.length != 5 || !p[1].equals("@s"))
        throw new IllegalArgumentException("[COMMAND_BOUNDS] 坐标传送使用tp @s x y z");
      double[] base = {x, y, z};
      for (int i = 0; i < 3; i++) {
        String value = p[i + 2];
        double target;
        try {
          target = value.equals("~") ? base[i] : value.startsWith("~")
              ? base[i] + Double.parseDouble(value.substring(1)) : Double.parseDouble(value);
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException("[COMMAND_BOUNDS] 传送坐标需要数字或~数字");
        }
        if (!Double.isFinite(target) || Math.abs(target - base[i]) > radius)
          throw new IllegalArgumentException("[COMMAND_BOUNDS] 传送目标超出配置范围");
        p[i + 2] = Double.toString(target);
      }
      return new Plan(String.join(" ", p), name, null, "", "", true, 0);
    }
    if (area || tp || name.equals("summon")) {
      try {
        if (tp && (p.length != 5 || !p[1].equals("@s")))
          throw new IllegalArgumentException("坐标传送使用tp @s x y z；玩家间传送用已允许的插件指令");
        int start = tp || name.equals("summon") ? 2 : 1;
        if (p.length < start + 3) throw new IllegalArgumentException("缺少坐标");
        int[] at = blockOrigin;
        int[] a = new int[3], b = new int[3];
        for (int i = 0; i < 3; i++) a[i] = coordinate(p[start + i], at[i]);
        b = a.clone();
        if (name.equals("fill") || name.equals("clone"))
          for (int i = 0; i < 3; i++) b[i] = coordinate(p[4 + i], at[i]);
        for (int i = 0; i < 3; i++) {
          if (Math.abs((long) a[i] - at[i]) > radius || Math.abs((long) b[i] - at[i]) > radius)
            throw new IllegalArgumentException("目标超出半径" + radius);
        }
        volume = 1;
        for (int i = 0; i < 3; i++) volume *= Math.abs((long) a[i] - b[i]) + 1;
        if (area && volume > cap)
          throw new IllegalArgumentException("区域" + volume + "格，超过本世界上限" + cap);
        bounds = new int[] {a[0], a[1], a[2], b[0], b[1], b[2]};
        if (name.equals("clone")) {
          for (int i = 0; i < 3; i++) {
            int d = coordinate(p[7 + i], at[i]);
            long end = (long) d + Math.abs((long) a[i] - b[i]);
            if (Math.abs((long) d - at[i]) > radius || Math.abs(end - at[i]) > radius)
              throw new IllegalArgumentException("复制目标超出范围");
          }
        }
        if (name.equals("setblock") || name.equals("fill")) {
          String spec = p[name.equals("fill") ? 7 : 4];
          if (!spec.matches("[a-z0-9_.:-]+(?:\\[[a-z0-9_=,.-]+\\])?"))
            throw new IllegalArgumentException("方块使用ID与可选[state]，不接受机器NBT模板");
          block = spec.split("\\[", 2)[0];
          if (!block.contains(":")) block = "minecraft:" + block;
        }
      } catch (IndexOutOfBoundsException | NumberFormatException e) {
        throw new IllegalArgumentException("[COMMAND_BOUNDS] 坐标需要绝对整数或~整数，参数不完整");
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("[COMMAND_BOUNDS] " + e.getMessage());
      }
    }
    if (name.equals("gamemode")) {
      if (p.length < 2) throw new IllegalArgumentException("[INVALID_ARGUMENT] 缺少游戏模式");
      if (p.length == 2 || p.length == 3 && p[2].equals("@s")) mode = p[1];
    }
    // Resolve relative coordinates at validation time so movement before dispatch cannot alter
    // bounds.
    if (bounds != null) {
      int start = tp || name.equals("summon") ? 2 : 1;
      for (int i = 0; i < 3; i++) p[start + i] = Integer.toString(bounds[i]);
      if (name.equals("fill") || name.equals("clone"))
        for (int i = 0; i < 3; i++) p[4 + i] = Integer.toString(bounds[3 + i]);
      if (name.equals("clone"))
        for (int i = 0; i < 3; i++)
          p[7 + i] = Integer.toString(coordinate(p[7 + i], blockOrigin[i]));
    }
    return new Plan(
        bounds == null ? s : String.join(" ", p),
        name,
        bounds,
        block,
        mode,
        tp || Set.of("home", "warp", "rtp", "spawn", "back", "tpa", "tpaccept").contains(name),
        volume);
  }

  private static int coordinate(String v, int base) {
    if (v.equals("~")) return base;
    return v.startsWith("~")
        ? Math.addExact(base, Integer.parseInt(v.substring(1)))
        : Integer.parseInt(v);
  }

  private GameCommandPolicy() {}
}
