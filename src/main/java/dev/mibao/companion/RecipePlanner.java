package dev.mibao.companion;

import java.util.*;

/** Bounded inventory simulation; produces ordinary client crafting requests, never items. */
public final class RecipePlanner {
  public record Recipe(
      String id, String output, int amount, List<Set<String>> ingredients, boolean table) {}

  public record Plan(
      List<String> batches,
      Map<String, Integer> remaining,
      boolean needsTable,
      Map<String, Integer> consumedStock) {}

  private final List<Recipe> recipes;
  private int work;

  private static final class State {
    Map<String, Integer> stock, initial;
    List<String> batches;
    boolean table, needsTable;

    State(Map<String, Integer> stock, boolean table) {
      this.stock = new HashMap<>(stock);
      initial = new HashMap<>(stock);
      this.table = table;
      batches = new ArrayList<>();
    }

    State copy() {
      var c = new State(stock, table);
      c.batches = new ArrayList<>(batches);
      c.needsTable = needsTable;
      c.initial = new HashMap<>(initial);
      return c;
    }
  }

  public RecipePlanner(List<Recipe> recipes) {
    this.recipes = recipes.stream().sorted(Comparator.comparing(Recipe::id)).toList();
  }

  public Plan plan(String item, int additional, Map<String, Integer> stock, boolean table) {
    if (additional < 1 || additional > 64) throw new IllegalArgumentException("制作数量须为1..64");
    work = 0;
    State s = make(item, additional, new State(stock, table), new HashSet<>(), 0);
    if (s == null)
      throw new IllegalArgumentException(
          "现有普通材料无法完成整份计划（含工作台、工具和中间材料）；可设置本世界生产补料来源，否则提供材料后重新下令；不自动采矿");
    Map<String, Integer> consumed = new HashMap<>();
    stock.forEach(
        (id, n) -> {
          int used = n - s.initial.getOrDefault(id, 0);
          if (used > 0) consumed.put(id, used);
        });
    return new Plan(
        List.copyOf(s.batches), Map.copyOf(s.stock), s.needsTable, Map.copyOf(consumed));
  }

  private void budget() {
    if (++work > 4096) throw new IllegalArgumentException("配方搜索达到4096步上限，请拆成小任务");
  }

  private State need(String item, int n, State s, Set<String> path, int depth) {
    budget();
    int have = s.stock.getOrDefault(item, 0);
    if (have < n) {
      s = make(item, n - have, s, path, depth + 1);
      if (s == null) return null;
    }
    int fromInitial = Math.min(n, s.initial.getOrDefault(item, 0));
    s.initial.merge(item, -fromInitial, Integer::sum);
    s.stock.merge(item, -n, Integer::sum);
    return s;
  }

  private State make(String item, int n, State state, Set<String> path, int depth) {
    budget();
    if (depth > 8 || path.contains(item) || state.batches.size() >= 128) return null;
    var nextPath = new HashSet<>(path);
    nextPath.add(item);
    for (var r : recipes) {
      if (!r.output.equals(item) || r.amount < 1) continue;
      State s = state.copy();
      int batches = (n + r.amount - 1) / r.amount;
      if (batches + s.batches.size() > 128) continue;
      if (r.table && !s.table) {
        s = need("minecraft:crafting_table", 1, s, nextPath, depth + 1);
        if (s == null) continue;
        s.table = true;
        s.needsTable = true;
      }
      for (int batch = 0; batch < batches && s != null; batch++) {
        s = ingredients(r.ingredients, 0, s, nextPath, depth);
        if (s != null) {
          s.stock.merge(item, r.amount, Integer::sum);
          s.batches.add(r.id);
        }
      }
      if (s != null && s.batches.size() <= 128) return s;
    }
    return null;
  }

  private State ingredients(
      List<Set<String>> list, int index, State s, Set<String> path, int depth) {
    budget();
    if (index == list.size()) return s;
    var choices =
        list.get(index).stream()
            .sorted(
                Comparator.<String>comparingInt(k -> s.stock.getOrDefault(k, 0) > 0 ? 0 : 1)
                    .thenComparing(k -> k))
            .toList();
    for (String id : choices) {
      State c = need(id, 1, s.copy(), path, depth);
      if (c != null) {
        c = ingredients(list, index + 1, c, path, depth);
        if (c != null) return c;
      }
    }
    return null;
  }
}
