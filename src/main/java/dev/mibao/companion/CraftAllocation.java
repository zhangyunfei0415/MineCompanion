package dev.mibao.companion;

import java.util.*;

/** Bounded multiset matching: flexible ingredients cannot steal the only specific material. */
public final class CraftAllocation {
  public static List<Integer> choose(
      List<List<Integer>> alternatives, Map<Integer, Integer> counts) {
    var out = new ArrayList<Integer>();
    int[] budget = {4096};
    if (!search(alternatives, 0, new HashMap<>(counts), out, budget))
      throw new IllegalArgumentException("[CRAFT_MATERIALS] 当前普通背包不能匹配完整一批材料");
    return List.copyOf(out);
  }

  private static boolean search(
      List<List<Integer>> a, int i, Map<Integer, Integer> left, List<Integer> out, int[] budget) {
    if (--budget[0] < 0) throw new IllegalArgumentException("[CRAFT_SEARCH_BUDGET] 配方材料匹配超出预算");
    if (i == a.size()) return true;
    for (int slot : a.get(i)) {
      int n = left.getOrDefault(slot, 0);
      if (n <= 0) continue;
      left.put(slot, n - 1);
      out.add(slot);
      if (search(a, i + 1, left, out, budget)) return true;
      out.remove(out.size() - 1);
      left.put(slot, n);
    }
    return false;
  }

  public static int cell(int ingredient, int recipeWidth, int gridWidth) {
    return 1 + (ingredient / recipeWidth) * gridWidth + ingredient % recipeWidth;
  }

  private CraftAllocation() {}
}
