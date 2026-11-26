package dev.mibao.companion;

import java.util.*;

/**
 * Polynomial-time bounded multiset matching via max-flow feasibility pruning: preserves exact
 * deterministic candidate order while eliminating exponential backtracking on overlapping tags.
 */
public final class CraftAllocation {
  public static List<Integer> choose(
      List<List<Integer>> alternatives, Map<Integer, Integer> counts) {
    if (alternatives.isEmpty()) return List.of();
    var remaining = new HashMap<>(counts);
    if (!canFill(alternatives, 0, remaining)) {
      throw new IllegalArgumentException("[CRAFT_MATERIALS] 当前普通背包不能匹配完整一批材料");
    }
    var out = new ArrayList<Integer>(alternatives.size());
    for (int i = 0; i < alternatives.size(); i++) {
      boolean picked = false;
      for (int slot : alternatives.get(i)) {
        int left = remaining.getOrDefault(slot, 0);
        if (left <= 0) continue;
        remaining.put(slot, left - 1);
        if (i + 1 == alternatives.size() || canFill(alternatives, i + 1, remaining)) {
          out.add(slot);
          picked = true;
          break;
        }
        remaining.put(slot, left);
      }
      if (!picked) {
        throw new IllegalArgumentException("[CRAFT_MATERIALS] 当前普通背包不能匹配完整一批材料");
      }
    }
    return List.copyOf(out);
  }

  private static boolean canFill(
      List<List<Integer>> alternatives, int startIdx, Map<Integer, Integer> remaining) {
    int ingCount = alternatives.size() - startIdx;
    if (ingCount <= 0) return true;

    // Collect distinct active slots and caps from remaining ingredients.
    var slotToNode = new HashMap<Integer, Integer>();
    var slotCaps = new ArrayList<Integer>();
    for (int i = startIdx; i < alternatives.size(); i++) {
      for (int slot : alternatives.get(i)) {
        int avail = remaining.getOrDefault(slot, 0);
        if (avail > 0 && !slotToNode.containsKey(slot)) {
          slotToNode.put(slot, 1 + ingCount + slotCaps.size());
          slotCaps.add(Math.min(ingCount, avail));
        }
      }
    }
    if (slotCaps.isEmpty()) return false;

    int source = 0;
    int sink = 1 + ingCount + slotCaps.size();
    int nodeCount = sink + 1;
    var graph = new FlowGraph(nodeCount);

    for (int i = 0; i < ingCount; i++) {
      int ingNode = 1 + i;
      graph.addEdge(source, ingNode, 1);
      int prev = Integer.MIN_VALUE;
      var seenSlots = new HashSet<Integer>();
      for (int slot : alternatives.get(startIdx + i)) {
        Integer slotNode = slotToNode.get(slot);
        if (slotNode == null) continue;
        if (slot != prev && seenSlots.add(slot)) {
          graph.addEdge(ingNode, slotNode, 1);
          prev = slot;
        }
      }
    }
    for (int s = 0; s < slotCaps.size(); s++) {
      graph.addEdge(1 + ingCount + s, sink, slotCaps.get(s));
    }
    return graph.maxFlow(source, sink, ingCount) == ingCount;
  }

  private static final class FlowGraph {
    private final List<int[]> edges = new ArrayList<>();
    private final List<List<Integer>> adj;
    private final int[] parentNode;
    private final int[] parentEdge;
    private final int[] queue;

    FlowGraph(int n) {
      adj = new ArrayList<>(n);
      for (int i = 0; i < n; i++) adj.add(new ArrayList<>());
      parentNode = new int[n];
      parentEdge = new int[n];
      queue = new int[n];
    }

    void addEdge(int from, int to, int cap) {
      int fwd = edges.size();
      edges.add(new int[] {to, cap, 0});
      adj.get(from).add(fwd);
      edges.add(new int[] {from, 0, 0});
      adj.get(to).add(fwd + 1);
    }

    int maxFlow(int source, int sink, int goal) {
      int flow = 0;
      while (flow < goal) {
        Arrays.fill(parentNode, -1);
        parentNode[source] = source;
        int head = 0, tail = 0;
        queue[tail++] = source;
        while (head < tail && parentNode[sink] == -1) {
          int u = queue[head++];
          for (int eIdx : adj.get(u)) {
            int[] e = edges.get(eIdx);
            int v = e[0];
            if (parentNode[v] == -1 && e[1] - e[2] > 0) {
              parentNode[v] = u;
              parentEdge[v] = eIdx;
              queue[tail++] = v;
              if (v == sink) break;
            }
          }
        }
        if (parentNode[sink] == -1) break;
        int push = goal - flow;
        for (int v = sink; v != source; v = parentNode[v]) {
          int[] e = edges.get(parentEdge[v]);
          push = Math.min(push, e[1] - e[2]);
        }
        for (int v = sink; v != source; v = parentNode[v]) {
          int fwd = parentEdge[v];
          edges.get(fwd)[2] += push;
          edges.get(fwd ^ 1)[2] -= push;
        }
        flow += push;
      }
      return flow;
    }
  }

  public static int cell(int ingredient, int recipeWidth, int gridWidth) {
    return 1 + (ingredient / recipeWidth) * gridWidth + ingredient % recipeWidth;
  }

  private CraftAllocation() {}
}
