package dev.mibao.companion;

import java.util.*;

/**
 * Bounded inventory simulation; produces ordinary client crafting requests, never items. Upgrades
 * planning to forward-state Dijkstra over target closure and variant matching with MRV + memo.
 */
public final class RecipePlanner {
  public static final String TABLE_ITEM = "minecraft:crafting_table";

  public record Recipe(
      String id, String output, int amount, List<Set<String>> ingredients, boolean table) {}

  public record Plan(
      List<String> batches,
      Map<String, Integer> remaining,
      boolean needsTable,
      Map<String, Integer> consumedStock) {}

  public enum Objective {
    FEWEST_BATCHES,
    LEAST_INITIAL_STOCK
  }

  public enum Status {
    OPTIMAL,
    FEASIBLE_LIMIT,
    IMPOSSIBLE,
    SEARCH_LIMIT
  }

  public record Limits(int maxStates, int maxBatches) {
    public Limits {
      if (maxStates < 1 || maxBatches < 1) {
        throw new IllegalArgumentException("Limits must be positive");
      }
    }

    public static Limits defaults() {
      return new Limits(4096, 128);
    }
  }

  public record Batch(String recipeId, List<String> ingredients, boolean usesTable) {}

  public record Use(String item, int fromInitial, int fromCrafted) {
    public int total() {
      return fromInitial + fromCrafted;
    }
  }

  public record DetailedPlan(
      List<String> batches,
      List<Batch> steps,
      Map<String, Integer> remaining,
      boolean needsTable,
      Map<String, Integer> consumedStock,
      Map<String, Use> uses,
      int totalInitialConsumed) {}

  public record SearchResult(Status status, Optional<DetailedPlan> plan, int expandedStates) {}

  public enum PickStatus {
    FEASIBLE,
    INFEASIBLE,
    UNKNOWN_RECIPE,
    SEARCH_LIMIT
  }

  public record PickResult(PickStatus status, List<String> picks, int statesExplored) {}

  private static final class IndexedRecipe {
    final String id;
    final String output;
    final int amount;
    final boolean table;
    final List<Set<String>> ingredients;
    final List<List<String>> sortedSlots;

    IndexedRecipe(Recipe r) {
      this.id = r.id();
      this.output = r.output();
      this.amount = r.amount();
      this.table = r.table();
      this.ingredients = r.ingredients();
      var slots = new ArrayList<List<String>>(r.ingredients().size());
      for (var set : r.ingredients()) {
        var list = new ArrayList<>(set);
        Collections.sort(list);
        slots.add(List.copyOf(list));
      }
      this.sortedSlots = List.copyOf(slots);
    }
  }

  private final List<IndexedRecipe> recipes;
  private final Map<String, IndexedRecipe> byId;
  private final Map<String, List<IndexedRecipe>> byOutput;

  public RecipePlanner(List<Recipe> recipes) {
    var sorted = recipes.stream().sorted(Comparator.comparing(Recipe::id)).toList();
    var indexed = new ArrayList<IndexedRecipe>(sorted.size());
    var idMap = new HashMap<String, IndexedRecipe>();
    var outMap = new HashMap<String, List<IndexedRecipe>>();
    for (var r : sorted) {
      var ir = new IndexedRecipe(r);
      indexed.add(ir);
      idMap.putIfAbsent(ir.id, ir);
      outMap.computeIfAbsent(ir.output, k -> new ArrayList<>()).add(ir);
    }
    this.recipes = List.copyOf(indexed);
    this.byId = Map.copyOf(idMap);
    this.byOutput = Map.copyOf(outMap);
  }

  public Plan plan(String item, int additional, Map<String, Integer> stock, boolean table) {
    if (additional < 1 || additional > 64) {
      throw new IllegalArgumentException("制作数量须为1..64");
    }
    var res =
        planDetailed(item, additional, stock, table, Objective.FEWEST_BATCHES, Limits.defaults());
    if (res.status() == Status.SEARCH_LIMIT) {
      throw new IllegalArgumentException("配方搜索达到4096步上限，请拆成小任务");
    }
    if (res.plan().isEmpty()) {
      throw new IllegalArgumentException(
          "现有普通材料无法完成整份计划（含工作台、工具和中间材料）；可设置本世界生产补料来源，否则提供材料后重新下令；不自动采矿");
    }
    var d = res.plan().get();
    return new Plan(d.batches(), d.remaining(), d.needsTable(), d.consumedStock());
  }

  public SearchResult planDetailed(
      String target,
      int additional,
      Map<String, Integer> stock,
      boolean initialTable,
      Objective objective,
      Limits limits) {
    if (target == null || stock == null || objective == null || limits == null) {
      throw new IllegalArgumentException("Null planner argument");
    }
    if (additional < 1 || additional > 64) {
      throw new IllegalArgumentException("制作数量须为1..64");
    }

    Closure closure = buildClosure(target, stock, initialTable);
    if (closure.targetIndex < 0 || closure.candidateRecipes.isEmpty()) {
      return new SearchResult(Status.IMPOSSIBLE, Optional.empty(), 0);
    }

    int nItems = closure.items.length;
    int[] startStock = new int[nItems];
    int[] startInit = new int[nItems];
    for (int i = 0; i < nItems; i++) {
      int q = Math.max(0, stock.getOrDefault(closure.items[i], 0));
      startStock[i] = q;
      startInit[i] = q;
    }
    int goalStock = startStock[closure.targetIndex] + additional;

    var startNode =
        new SearchNode(
            0,
            startStock,
            startInit,
            initialTable,
            false,
            0,
            0,
            -1,
            null,
            null,
            false);
    var nodes = new ArrayList<SearchNode>();
    nodes.add(startNode);
    var bestCost = new HashMap<StateKey, long[]>();
    bestCost.put(startNode.key(), new long[] {0L, 0L});

    var pq =
        new PriorityQueue<Integer>(
            Comparator.<Integer>comparingLong(idx -> primaryCost(nodes.get(idx), objective))
                .thenComparingLong(idx -> secondaryCost(nodes.get(idx), objective))
                .thenComparingInt(idx -> nodes.get(idx).batches)
                .thenComparingInt(idx -> idx));
    pq.add(0);

    SearchNode incumbent = null;
    int expanded = 0;
    int[] workBudget = {Math.max(4096, limits.maxStates() * 8)};
    boolean[] truncated = {false};

    while (!pq.isEmpty()) {
      int curIdx = pq.poll();
      SearchNode cur = nodes.get(curIdx);
      long[] recorded = bestCost.get(cur.key());
      if (recorded != null
          && compareCost(
                  cur.batches, cur.initConsumed, (int) recorded[0], (int) recorded[1], objective)
              > 0) {
        continue;
      }

      if (cur.stock[closure.targetIndex] >= goalStock) {
        return new SearchResult(
            Status.OPTIMAL,
            Optional.of(replayPlan(cur, nodes, closure, stock)),
            expanded);
      }
      if (expanded >= limits.maxStates() || workBudget[0] <= 0) {
        if (incumbent != null) {
          return new SearchResult(
              Status.FEASIBLE_LIMIT,
              Optional.of(replayPlan(incumbent, nodes, closure, stock)),
              expanded);
        }
        return new SearchResult(Status.SEARCH_LIMIT, Optional.empty(), expanded);
      }
      expanded++;

      // Transition 1: activate crafting table if needed and available.
      if (!cur.tableActive && closure.tableIndex >= 0 && cur.stock[closure.tableIndex] > 0) {
        int[] ns = cur.stock.clone();
        int[] ni = cur.initLeft.clone();
        int craftedTables = ns[closure.tableIndex] - ni[closure.tableIndex];
        int fromInit = craftedTables > 0 ? 0 : (ni[closure.tableIndex] > 0 ? 1 : 0);
        ni[closure.tableIndex] -= fromInit;
        ns[closure.tableIndex] -= 1;
        int newInitConsumed = cur.initConsumed + fromInit;
        var next =
            new SearchNode(
                nodes.size(),
                ns,
                ni,
                true,
                true,
                cur.batches,
                newInitConsumed,
                curIdx,
                null,
                null,
                true);
        incumbent = enqueueIfImproved(next, nodes, bestCost, pq, closure, goalStock, objective, incumbent);
      }

      if (cur.batches >= limits.maxBatches()) {
        truncated[0] = true;
        continue;
      }

      // Transition 2: execute one crafting batch from relevant recipes.
      for (int[] recData : closure.recipeSlotOptions) {
        if (workBudget[0] <= 0) {
          truncated[0] = true;
          break;
        }
        int rIdx = recData[0];
        IndexedRecipe r = recipes.get(rIdx);
        if (r.table && !cur.tableActive) continue;
        int outIdx = closure.itemToIndex.get(r.output);
        if (r.output.equals(target) && cur.stock[outIdx] >= goalStock) continue;

        List<int[]> assignments =
            findDistinctAssignments(closure, rIdx, cur.stock, workBudget, truncated);
        for (int[] chosenItems : assignments) {
          int[] ns = cur.stock.clone();
          int[] ni = cur.initLeft.clone();
          int extraInit = 0;
          boolean valid = true;
          for (int itemIdx : chosenItems) {
            if (ns[itemIdx] <= 0) {
              valid = false;
              break;
            }
            int craftedAvail = ns[itemIdx] - ni[itemIdx];
            if (craftedAvail <= 0 && ni[itemIdx] > 0) {
              ni[itemIdx]--;
              extraInit++;
            }
            ns[itemIdx]--;
          }
          if (!valid) continue;
          ns[outIdx] += r.amount;

          // Cap irrelevant intermediate over-stock so cyclic surplus cannot inflate indefinitely.
          if (ns[outIdx] > 128 && outIdx != closure.targetIndex) {
            truncated[0] = true;
            continue;
          }

          int newBatches = cur.batches + 1;
          int newInitConsumed = cur.initConsumed + extraInit;
          var next =
              new SearchNode(
                  nodes.size(),
                  ns,
                  ni,
                  cur.tableActive,
                  cur.needsTable,
                  newBatches,
                  newInitConsumed,
                  curIdx,
                  r,
                  chosenItems,
                  false);
          incumbent =
              enqueueIfImproved(next, nodes, bestCost, pq, closure, goalStock, objective, incumbent);
        }
      }
      if (workBudget[0] <= 0) {
        if (incumbent != null) {
          return new SearchResult(
              Status.FEASIBLE_LIMIT,
              Optional.of(replayPlan(incumbent, nodes, closure, stock)),
              expanded);
        }
        return new SearchResult(Status.SEARCH_LIMIT, Optional.empty(), expanded);
      }
    }

    if (incumbent != null) {
      return new SearchResult(
          Status.OPTIMAL, Optional.of(replayPlan(incumbent, nodes, closure, stock)), expanded);
    }
    return new SearchResult(
        truncated[0] ? Status.SEARCH_LIMIT : Status.IMPOSSIBLE, Optional.empty(), expanded);
  }

  /**
   * Ingredient item ids (non-empty ingredients, recipe order) for the first of {@code batches}
   * (recipe ids in execution order) that keep every later batch craftable from {@code stock} plus
   * earlier outputs. Empty list: the remaining batches are not feasible. Null: unknown recipe or
   * the bounded search could not decide.
   */
  public List<String> firstBatchPicks(List<String> batches, Map<String, Integer> stock) {
    PickResult res = firstBatchPicksDetailed(batches, stock);
    return switch (res.status()) {
      case FEASIBLE -> res.picks();
      case INFEASIBLE -> List.of();
      case UNKNOWN_RECIPE, SEARCH_LIMIT -> null;
    };
  }

  public PickResult firstBatchPicksDetailed(List<String> batches, Map<String, Integer> stock) {
    if (batches == null || batches.isEmpty() || stock == null) {
      return new PickResult(PickStatus.UNKNOWN_RECIPE, List.of(), 0);
    }
    var planRecipes = new IndexedRecipe[batches.size()];
    for (int i = 0; i < batches.size(); i++) {
      var r = byId.get(batches.get(i));
      if (r == null) return new PickResult(PickStatus.UNKNOWN_RECIPE, List.of(), 0);
      planRecipes[i] = r;
    }

    // Build compact item universe for these batches + stock.
    var items = new ArrayList<String>();
    var idxMap = new HashMap<String, Integer>();
    for (var r : planRecipes) {
      idxMap.computeIfAbsent(
          r.output,
          k -> {
            items.add(k);
            return items.size() - 1;
          });
      for (var opts : r.sortedSlots) {
        for (String s : opts) {
          idxMap.computeIfAbsent(
              s,
              k -> {
                items.add(k);
                return items.size() - 1;
              });
        }
      }
    }
    int[] curStock = new int[items.size()];
    for (int i = 0; i < items.size(); i++) {
      curStock[i] = Math.max(0, stock.getOrDefault(items.get(i), 0));
    }

    int[][][] slotOptions = new int[planRecipes.length][][];
    for (int b = 0; b < planRecipes.length; b++) {
      var r = planRecipes[b];
      slotOptions[b] = new int[r.sortedSlots.size()][];
      for (int s = 0; s < r.sortedSlots.size(); s++) {
        var opts = r.sortedSlots.get(s);
        slotOptions[b][s] = new int[opts.size()];
        for (int k = 0; k < opts.size(); k++) {
          slotOptions[b][s][k] = idxMap.get(opts.get(k));
        }
      }
    }

    int[] outIdx = new int[planRecipes.length];
    int[] outAmt = new int[planRecipes.length];
    for (int b = 0; b < planRecipes.length; b++) {
      outIdx[b] = idxMap.get(planRecipes[b].output);
      outAmt[b] = planRecipes[b].amount;
    }

    int[] firstBatchSlots = new int[slotOptions[0].length];
    Arrays.fill(firstBatchSlots, -1);
    var solver =
        new PickSolver(
            slotOptions, outIdx, outAmt, curStock, firstBatchSlots, 4096);
    try {
      boolean ok = solver.solveBatch(0);
      if (!ok) return new PickResult(PickStatus.INFEASIBLE, List.of(), solver.states);
      var picks = new ArrayList<String>(firstBatchSlots.length);
      for (int itemId : firstBatchSlots) picks.add(items.get(itemId));
      return new PickResult(PickStatus.FEASIBLE, List.copyOf(picks), solver.states);
    } catch (BudgetException e) {
      return new PickResult(PickStatus.SEARCH_LIMIT, List.of(), solver.states);
    }
  }

  private static final class BudgetException extends RuntimeException {}

  private static final class PickSolver {
    final int[][][] slotOptions;
    final int[] outIdx;
    final int[] outAmt;
    final int[] stock;
    final int[] firstBatchPicks;
    final int budget;
    final Set<FailedKey> failedStates = new HashSet<>();
    int states;

    PickSolver(
        int[][][] slotOptions,
        int[] outIdx,
        int[] outAmt,
        int[] stock,
        int[] firstBatchPicks,
        int budget) {
      this.slotOptions = slotOptions;
      this.outIdx = outIdx;
      this.outAmt = outAmt;
      this.stock = stock;
      this.firstBatchPicks = firstBatchPicks;
      this.budget = budget;
    }

    boolean solveBatch(int batch) {
      if (++states > budget) throw new BudgetException();
      if (batch == slotOptions.length) return true;
      var key = new FailedKey(batch, stock.clone());
      if (failedStates.contains(key)) return false;

      int slotCount = slotOptions[batch].length;
      int[] assigned = new int[slotCount];
      Arrays.fill(assigned, -1);
      boolean ok = assignSlotMrv(batch, assigned, 0);
      if (!ok) {
        failedStates.add(key);
      }
      return ok;
    }

    private boolean assignSlotMrv(int batch, int[] assigned, int filled) {
      if (++states > budget) throw new BudgetException();
      int[][] slots = slotOptions[batch];
      if (filled == slots.length) {
        if (batch == 0) {
          System.arraycopy(assigned, 0, firstBatchPicks, 0, assigned.length);
        }
        stock[outIdx[batch]] += outAmt[batch];
        boolean ok = solveBatch(batch + 1);
        stock[outIdx[batch]] -= outAmt[batch];
        return ok;
      }

      // Pick unassigned slot with fewest currently in-stock candidates (MRV).
      int bestSlot = -1;
      int bestAvail = Integer.MAX_VALUE;
      for (int s = 0; s < slots.length; s++) {
        if (assigned[s] != -1) continue;
        int avail = 0;
        for (int opt : slots[s]) {
          if (stock[opt] > 0) avail++;
        }
        if (avail == 0) return false;
        if (avail < bestAvail) {
          bestAvail = avail;
          bestSlot = s;
          if (avail == 1) break;
        }
      }

      for (int opt : slots[bestSlot]) {
        if (stock[opt] <= 0) continue;
        stock[opt]--;
        assigned[bestSlot] = opt;
        if (assignSlotMrv(batch, assigned, filled + 1)) return true;
        assigned[bestSlot] = -1;
        stock[opt]++;
      }
      return false;
    }
  }

  private record FailedKey(int batch, int[] stock) {
    @Override
    public boolean equals(Object o) {
      return o instanceof FailedKey k && batch == k.batch && Arrays.equals(stock, k.stock);
    }

    @Override
    public int hashCode() {
      return 31 * batch + Arrays.hashCode(stock);
    }
  }

  private record StateKey(int[] stock, int[] initLeft, boolean tableActive) {
    @Override
    public boolean equals(Object o) {
      return o instanceof StateKey k
          && tableActive == k.tableActive
          && Arrays.equals(stock, k.stock)
          && Arrays.equals(initLeft, k.initLeft);
    }

    @Override
    public int hashCode() {
      int h = Boolean.hashCode(tableActive);
      h = 31 * h + Arrays.hashCode(stock);
      h = 31 * h + Arrays.hashCode(initLeft);
      return h;
    }
  }

  private record SearchNode(
      int index,
      int[] stock,
      int[] initLeft,
      boolean tableActive,
      boolean needsTable,
      int batches,
      int initConsumed,
      int parentIdx,
      IndexedRecipe recipe,
      int[] chosenItems,
      boolean activatedTable) {
    StateKey key() {
      return new StateKey(stock, initLeft, tableActive);
    }
  }

  private static final class Closure {
    final String[] items;
    final Map<String, Integer> itemToIndex;
    final int targetIndex;
    final int tableIndex;
    final List<Integer> candidateRecipes;
    final int[][] recipeSlotOptions; // [candidatePos] -> [rIdx], per-recipe options in slotItemIds
    final int[][][] slotItemIds;

    Closure(
        String[] items,
        Map<String, Integer> itemToIndex,
        int targetIndex,
        int tableIndex,
        List<Integer> candidateRecipes,
        int[][] recipeSlotOptions,
        int[][][] slotItemIds) {
      this.items = items;
      this.itemToIndex = itemToIndex;
      this.targetIndex = targetIndex;
      this.tableIndex = tableIndex;
      this.candidateRecipes = candidateRecipes;
      this.recipeSlotOptions = recipeSlotOptions;
      this.slotItemIds = slotItemIds;
    }
  }

  private Closure buildClosure(String target, Map<String, Integer> stock, boolean initialTable) {
    var visitedItems = new LinkedHashSet<String>();
    var relevantRecipes = new LinkedHashSet<Integer>();
    var queue = new ArrayDeque<String>();
    visitedItems.add(target);
    queue.add(target);

    boolean addedTable = false;
    while (!queue.isEmpty()) {
      String out = queue.removeFirst();
      var list = byOutput.get(out);
      if (list == null) continue;
      for (var r : list) {
        if (r.amount < 1) continue;
        relevantRecipes.add(recipes.indexOf(r));
        if (r.table && !initialTable && !addedTable) {
          addedTable = true;
          if (visitedItems.add(TABLE_ITEM)) queue.add(TABLE_ITEM);
        }
        for (var opts : r.sortedSlots) {
          for (String ing : opts) {
            boolean producible = byOutput.containsKey(ing);
            boolean stocked = stock.getOrDefault(ing, 0) > 0;
            if ((producible || stocked) && visitedItems.add(ing)) {
              queue.add(ing);
            }
          }
        }
      }
    }

    var sortedItems = new ArrayList<>(visitedItems);
    Collections.sort(sortedItems);
    String[] items = sortedItems.toArray(String[]::new);
    var idxMap = new HashMap<String, Integer>(items.length * 2);
    for (int i = 0; i < items.length; i++) idxMap.put(items[i], i);

    var recList = new ArrayList<Integer>(relevantRecipes);
    Collections.sort(recList);
    int[][] recMeta = new int[recList.size()][1];
    int[][][] slotIds = new int[recList.size()][][];
    for (int p = 0; p < recList.size(); p++) {
      int rIdx = recList.get(p);
      recMeta[p][0] = rIdx;
      var r = recipes.get(rIdx);
      slotIds[p] = new int[r.sortedSlots.size()][];
      for (int s = 0; s < r.sortedSlots.size(); s++) {
        var filtered = new ArrayList<Integer>();
        for (String opt : r.sortedSlots.get(s)) {
          Integer id = idxMap.get(opt);
          if (id != null) filtered.add(id);
        }
        slotIds[p][s] = filtered.stream().mapToInt(Integer::intValue).toArray();
      }
    }

    int targetIdx = idxMap.getOrDefault(target, -1);
    int tableIdx = idxMap.getOrDefault(TABLE_ITEM, -1);
    return new Closure(items, idxMap, targetIdx, tableIdx, recList, recMeta, slotIds);
  }

  private List<int[]> findDistinctAssignments(
      Closure closure, int rIdx, int[] stock, int[] workBudget, boolean[] truncated) {
    int pos = Collections.binarySearch(closure.candidateRecipes, rIdx);
    int[][] slots = closure.slotItemIds[pos];
    if (slots.length == 0) return List.of(new int[0]);
    for (int[] s : slots) {
      boolean has = false;
      for (int id : s) {
        if (stock[id] > 0) {
          has = true;
          break;
        }
      }
      if (!has) return List.of();
    }

    // Group identical slot option lists (e.g., 8 identical plank slots -> 1 group with multiplicity 8)
    // so symmetric mixtures enumerate multiset counts instead of O(K^S) slot permutations.
    record SlotGroup(int[] options, int[] slotIndices) {}
    var groupMap = new LinkedHashMap<String, List<Integer>>();
    var optsByKey = new HashMap<String, int[]>();
    for (int s = 0; s < slots.length; s++) {
      String key = Arrays.toString(slots[s]);
      optsByKey.putIfAbsent(key, slots[s]);
      groupMap.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
    }
    var groups = new ArrayList<SlotGroup>(groupMap.size());
    for (var entry : groupMap.entrySet()) {
      int[] idxArr = entry.getValue().stream().mapToInt(Integer::intValue).toArray();
      int[] inStockOpts =
          Arrays.stream(optsByKey.get(entry.getKey())).filter(id -> stock[id] > 0).toArray();
      if (inStockOpts.length == 0) return List.of();
      groups.add(new SlotGroup(inStockOpts, idxArr));
    }
    // Order smallest-option groups first to prune dead branches immediately.
    groups.sort(Comparator.comparingInt(g -> g.options().length));

    var out = new ArrayList<int[]>();
    var seenMultisets = new HashSet<Long>();
    int[] cur = new int[slots.length];
    int[] used = new int[closure.items.length];
    final int maxVariantsPerBatch = 64;

    class GroupEnumerator {
      void runGroup(int gIdx) {
        if (--workBudget[0] <= 0 || out.size() >= maxVariantsPerBatch) {
          truncated[0] = true;
          return;
        }
        if (gIdx == groups.size()) {
          long hash = 1469598103934665603L;
          for (int i = 0; i < used.length; i++) {
            if (used[i] > 0) {
              hash ^= (i * 1000003L + used[i]);
              hash *= 1099511628211L;
            }
          }
          if (seenMultisets.add(hash)) {
            out.add(cur.clone());
          }
          return;
        }
        SlotGroup group = groups.get(gIdx);
        assignWithinGroup(gIdx, group.options(), group.slotIndices(), 0, 0);
      }

      void assignWithinGroup(int gIdx, int[] opts, int[] slotIndices, int optIdx, int slotOffset) {
        if (--workBudget[0] <= 0 || out.size() >= maxVariantsPerBatch) {
          truncated[0] = true;
          return;
        }
        if (slotOffset == slotIndices.length) {
          runGroup(gIdx + 1);
          return;
        }
        if (optIdx >= opts.length) return;
        int id = opts[optIdx];
        int avail = stock[id] - used[id];
        int needLeft = slotIndices.length - slotOffset;
        int maxTake = Math.min(avail, needLeft);
        // Prefer taking more of an already-available single variant first so simple mixtures converge cleanly.
        for (int take = maxTake; take >= 0; take--) {
          for (int k = 0; k < take; k++) {
            cur[slotIndices[slotOffset + k]] = id;
          }
          used[id] += take;
          assignWithinGroup(gIdx, opts, slotIndices, optIdx + 1, slotOffset + take);
          used[id] -= take;
          if (workBudget[0] <= 0 || out.size() >= maxVariantsPerBatch) return;
        }
      }
    }

    new GroupEnumerator().runGroup(0);
    return out;
  }

  private static long primaryCost(SearchNode node, Objective objective) {
    return objective == Objective.FEWEST_BATCHES ? node.batches : node.initConsumed;
  }

  private static long secondaryCost(SearchNode node, Objective objective) {
    return objective == Objective.FEWEST_BATCHES ? node.initConsumed : node.batches;
  }

  private static int compareCost(
      int b1, int i1, int b2, int i2, Objective objective) {
    if (objective == Objective.FEWEST_BATCHES) {
      int c = Integer.compare(b1, b2);
      return c != 0 ? c : Integer.compare(i1, i2);
    }
    int c = Integer.compare(i1, i2);
    return c != 0 ? c : Integer.compare(b1, b2);
  }

  private static SearchNode enqueueIfImproved(
      SearchNode next,
      List<SearchNode> nodes,
      Map<StateKey, long[]> bestCost,
      PriorityQueue<Integer> pq,
      Closure closure,
      int goalStock,
      Objective objective,
      SearchNode incumbent) {
    StateKey k = next.key();
    long[] old = bestCost.get(k);
    if (old != null
        && compareCost(next.batches, next.initConsumed, (int) old[0], (int) old[1], objective) >= 0) {
      return incumbent;
    }
    bestCost.put(k, new long[] {next.batches, next.initConsumed});
    nodes.add(next);
    pq.add(next.index);
    if (next.stock[closure.targetIndex] >= goalStock) {
      if (incumbent == null
          || compareCost(
                  next.batches, next.initConsumed, incumbent.batches, incumbent.initConsumed, objective)
              < 0) {
        return next;
      }
    }
    return incumbent;
  }

  private static DetailedPlan replayPlan(
      SearchNode goal, List<SearchNode> nodes, Closure closure, Map<String, Integer> initialStock) {
    var chain = new ArrayList<SearchNode>();
    for (int idx = goal.index; idx > 0; ) {
      SearchNode node = nodes.get(idx);
      chain.add(node);
      idx = node.parentIdx;
    }
    Collections.reverse(chain);

    var simInit = new HashMap<String, Integer>(initialStock);
    var simCrafted = new HashMap<String, Integer>();
    var usedInit = new HashMap<String, Integer>();
    var usedCrafted = new HashMap<String, Integer>();
    var batches = new ArrayList<String>();
    var steps = new ArrayList<Batch>();

    for (SearchNode step : chain) {
      if (step.activatedTable) {
        consumeTracked(TABLE_ITEM, simInit, simCrafted, usedInit, usedCrafted);
        continue;
      }
      var r = step.recipe;
      var ingList = new ArrayList<String>(step.chosenItems.length);
      for (int id : step.chosenItems) {
        String item = closure.items[id];
        ingList.add(item);
        consumeTracked(item, simInit, simCrafted, usedInit, usedCrafted);
      }
      simCrafted.merge(r.output, r.amount, Integer::sum);
      batches.add(r.id);
      steps.add(new Batch(r.id, List.copyOf(ingList), r.table));
    }

    var remaining = new HashMap<String, Integer>();
    for (var e : simInit.entrySet()) {
      if (e.getValue() > 0 || initialStock.containsKey(e.getKey())) {
        remaining.merge(e.getKey(), e.getValue(), Integer::sum);
      }
    }
    for (var e : simCrafted.entrySet()) {
      remaining.merge(e.getKey(), e.getValue(), Integer::sum);
    }

    var consumed = new HashMap<String, Integer>();
    for (var e : usedInit.entrySet()) {
      if (e.getValue() > 0) consumed.put(e.getKey(), e.getValue());
    }

    var uses = new HashMap<String, Use>();
    var allUsed = new HashSet<String>(usedInit.keySet());
    allUsed.addAll(usedCrafted.keySet());
    for (String item : allUsed) {
      uses.put(
          item,
          new Use(item, usedInit.getOrDefault(item, 0), usedCrafted.getOrDefault(item, 0)));
    }

    return new DetailedPlan(
        List.copyOf(batches),
        List.copyOf(steps),
        Map.copyOf(remaining),
        goal.needsTable,
        Map.copyOf(consumed),
        Map.copyOf(uses),
        goal.initConsumed);
  }

  private static void consumeTracked(
      String item,
      Map<String, Integer> simInit,
      Map<String, Integer> simCrafted,
      Map<String, Integer> usedInit,
      Map<String, Integer> usedCrafted) {
    int craftedAvail = simCrafted.getOrDefault(item, 0);
    int initAvail = simInit.getOrDefault(item, 0);
    if (craftedAvail > 0) {
      simCrafted.put(item, craftedAvail - 1);
      usedCrafted.merge(item, 1, Integer::sum);
    } else if (initAvail > 0) {
      simInit.put(item, initAvail - 1);
      usedInit.merge(item, 1, Integer::sum);
    }
  }
}
