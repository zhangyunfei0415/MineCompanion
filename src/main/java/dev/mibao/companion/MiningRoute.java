package dev.mibao.companion;

import java.util.*;
import java.util.function.Function;

/** Pure geometry. Coordinates are standing cells, and all floors are reserved before work. */
record MiningRoute(List<Step> steps, Set<Cell> floors, int plannedClearance,
    List<Branch> branches) {
  record Cell(int x, int y, int z) {
    Cell offset(int dx, int dy, int dz) {
      return new Cell(Math.addExact(x, dx), Math.addExact(y, dy), Math.addExact(z, dz));
    }
  }

  enum Heading {
    NORTH(0, -1), EAST(1, 0), SOUTH(0, 1), WEST(-1, 0);
    final int x, z;
    Heading(int x, int z) {
      this.x = x;
      this.z = z;
    }
    Heading turn(int quarterTurns) {
      return values()[Math.floorMod(ordinal() + quarterTurns, 4)];
    }
  }

  enum Preset {
    TUNNEL_1X2("1×2 两格高直巷", 12, 1, 2),
    TUNNEL_2X2("2×2 方巷", 8, 2, 2),
    TUNNEL_3X3("3×3 运输主巷", 6, 3, 3),
    STAIRS_STRAIGHT("直台阶", 8, 1, 3),
    STAIRS_SWITCHBACK("折返台阶", 8, 1, 3),
    STAIRS_SPIRAL_3X3("3×3 螺旋台阶", 8, 1, 3),
    BRANCH_1X2("鱼骨分支", 12, 1, 2);

    final String label;
    final int length, width, height;
    Preset(String label, int length, int width, int height) {
      this.label = label;
      this.length = length;
      this.width = width;
      this.height = height;
    }

    String id() {
      return name().toLowerCase(Locale.ROOT);
    }

    static Preset forObjective(String objective, int width, int length, int maxStraightRun) {
      return switch (objective) {
        case "tunnel" -> switch (width) {
          case 1 -> TUNNEL_1X2;
          case 2 -> TUNNEL_2X2;
          case 3 -> TUNNEL_3X3;
          default -> throw new IllegalArgumentException("[INVALID_ARGUMENT] width 须为 1..3");
        };
        case "descend" -> length <= maxStraightRun ? STAIRS_STRAIGHT
            : maxStraightRun < 4 ? STAIRS_SPIRAL_3X3 : STAIRS_SWITCHBACK;
        case "branches" -> BRANCH_1X2;
        default -> throw new IllegalArgumentException("[INVALID_ARGUMENT] 无此类矿道目标");
      };
    }
  }

  record Step(Cell from, Cell to, List<Cell> clearance, List<Cell> floor) {}

  record Branch(Cell junction, Heading heading, int requested, int previewLimit, int planned,
      Cell blockedFloor, String limitReason) {}

  static MiningRoute plan(Cell entry, Heading heading, Preset preset, int length, int spacing, int branch) {
    return plan(entry, heading, preset, length, spacing, branch, List.of(), floor -> "");
  }

  static MiningRoute plan(Cell entry, Heading heading, Preset preset, int length, int spacing,
      int branch, List<Integer> branchLimits, Function<Cell, String> supportProblem) {
    if (length < 1 || length > 24 || spacing < 3 || spacing > 7 || branch < 1 || branch > 8)
      throw new IllegalArgumentException("[INVALID_ARGUMENT] length=1..24, branch_spacing=3..7, branch_length=1..8");
    if (!branchLimits.isEmpty() && (preset != Preset.BRANCH_1X2
        || branchLimits.size() != length / spacing
        || branchLimits.stream().anyMatch(limit -> limit < 0 || limit > branch))) {
      throw new IllegalArgumentException("[INVALID_ARGUMENT] branch_limits须与支路数量相同，每项为0到branch_length");
    }
    var steps = new ArrayList<Step>();
    var branches = new ArrayList<Branch>();
    var cursor = entry;
    var direction = heading;
    for (int i = 0; i < length; i++) {
      if (preset == Preset.STAIRS_SWITCHBACK && i > 0 && i % 4 == 0) {
        // Cross a level landing into a separate lane, then change the downhill direction.
        var across = heading.turn(1);
        cursor = append(steps, cursor, across, 0, 1, 2);
        cursor = append(steps, cursor, across, 0, 1, 2);
        direction = direction.turn(2);
      }
      if (preset == Preset.STAIRS_SPIRAL_3X3 && i > 0 && i % 2 == 0)
        direction = direction.turn(1);
      boolean downhill = preset == Preset.STAIRS_STRAIGHT
          || preset == Preset.STAIRS_SWITCHBACK || preset == Preset.STAIRS_SPIRAL_3X3;
      cursor = append(steps, cursor, direction, downhill ? -1 : 0, preset.width, preset.height);
      if (preset == Preset.BRANCH_1X2 && (i + 1) % spacing == 0) {
        int index = branches.size();
        var side = heading.turn(index % 2 == 0 ? 1 : -1);
        var frontier = cursor;
        int first = steps.size();
        int limit = branchLimits.isEmpty() ? branch : branchLimits.get(index);
        Cell blockedFloor = null;
        String reason = limit < branch ? "PREVIEW_LIMIT" : "";
        for (int j = 0; j < limit; j++) {
          var floor = frontier.offset(side.x, -1, side.z);
          String problem = supportProblem.apply(floor);
          if (!problem.isEmpty()) {
            blockedFloor = floor;
            reason = problem;
            break;
          }
          frontier = append(steps, frontier, side, 0, 1, 2);
        }
        int end = steps.size();
        branches.add(new Branch(cursor, side, branch, limit, end - first, blockedFloor, reason));
        for (int j = end - 1; j >= first; j--) {
          var outbound = steps.get(j);
          var previousFloor = j == first ? List.of(cursor.offset(0, -1, 0)) : steps.get(j - 1).floor();
          steps.add(new Step(outbound.to(), outbound.from(), List.of(), previousFloor));
        }
      }
    }
    var floors = new HashSet<Cell>();
    floors.add(entry.offset(0, -1, 0));
    var clearance = new HashSet<Cell>();
    for (var step : steps) {
      floors.addAll(step.floor());
      clearance.addAll(step.clearance());
    }
    if (!Collections.disjoint(floors, clearance))
      throw new IllegalArgumentException("[ROUTE_CONFLICT] 截面会破坏已计划的回程支撑");
    if (clearance.size() > 384)
      throw new IllegalArgumentException("[ROUTE_TOO_LARGE] 一段最多规划清理384格，请缩短路线");
    return new MiningRoute(List.copyOf(steps), Set.copyOf(floors), clearance.size(),
        List.copyOf(branches));
  }

  private static Cell append(List<Step> steps, Cell from, Heading direction, int dy, int width, int height) {
    var to = from.offset(direction.x, dy, direction.z);
    var side = direction.turn(1);
    var clearance = new ArrayList<Cell>();
    var floor = new ArrayList<Cell>();
    for (int column : width == 1 ? new int[] {0} : width == 2 ? new int[] {0, 1} : new int[] {0, -1, 1}) {
      var base = to.offset(side.x * column, 0, side.z * column);
      floor.add(base.offset(0, -1, 0));
      for (int y = height - 1; y >= 0; y--) clearance.add(base.offset(0, y, 0));
    }
    steps.add(new Step(from, to, List.copyOf(clearance), List.copyOf(floor)));
    return to;
  }
}
