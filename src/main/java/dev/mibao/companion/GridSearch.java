package dev.mibao.companion;

import java.util.*;
import java.util.function.*;

/**
 * Tick-budgeted A* core independent of Minecraft. World reads are supplied by the owning thread.
 */
public final class GridSearch {
  public record Cell(int x, int y, int z) {}

  public record Step(Cell cell, double cost) {}

  public enum State {
    SEARCHING,
    FOUND,
    EXHAUSTED,
    CANCELLED
  }

  private record Node(Cell cell, double g, double f) {}

  private final PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::f));
  private final Map<Cell, Double> costs = new HashMap<>();
  private final Map<Cell, Cell> parent = new HashMap<>();
  private final Cell start;
  private final Predicate<Cell> goal;
  private final Function<Cell, List<Step>> weightedNeighbours;
  private final ToDoubleFunction<Cell> heuristic;
  private final int totalBudget;
  private int expanded;
  private State state = State.SEARCHING;
  private List<Cell> path = List.of();

  public GridSearch(
      Cell start,
      Predicate<Cell> goal,
      Function<Cell, List<Cell>> neighbours,
      ToDoubleFunction<Cell> heuristic,
      int totalBudget) {
    this(
        start,
        goal,
        c -> {
          List<Cell> list = neighbours.apply(c);
          List<Step> steps = new ArrayList<>(list.size());
          for (Cell n : list) steps.add(new Step(n, 1.0 + Math.abs(n.y() - c.y()) * .5));
          return steps;
        },
        heuristic,
        totalBudget,
        true);
  }

  private GridSearch(
      Cell start,
      Predicate<Cell> goal,
      Function<Cell, List<Step>> weightedNeighbours,
      ToDoubleFunction<Cell> heuristic,
      int totalBudget,
      boolean weightedMarker) {
    this.start = start;
    this.goal = goal;
    this.weightedNeighbours = weightedNeighbours;
    this.heuristic = heuristic;
    this.totalBudget = totalBudget;
    costs.put(start, 0.0);
    open.add(new Node(start, 0, heuristic.applyAsDouble(start)));
    if (goal.test(start)) state = State.FOUND;
  }

  public static GridSearch weighted(
      Cell start,
      Predicate<Cell> goal,
      Function<Cell, List<Step>> weightedNeighbours,
      ToDoubleFunction<Cell> heuristic,
      int totalBudget) {
    return new GridSearch(start, goal, weightedNeighbours, heuristic, totalBudget, true);
  }

  public State state() {
    return state;
  }

  public List<Cell> path() {
    return path;
  }

  public int expanded() {
    return expanded;
  }

  public void cancel() {
    state = State.CANCELLED;
    open.clear();
  }

  public State advance(int nodes, long nanos) {
    if (state != State.SEARCHING) return state;
    long begin = System.nanoTime();
    int popped = 0;
    while (!open.isEmpty() && popped++ < nodes) {
      if (expanded >= totalBudget) {
        state = State.EXHAUSTED;
        return state;
      }
      Node n = open.poll();
      if (n.g > costs.getOrDefault(n.cell, Double.MAX_VALUE)) continue;
      if (goal.test(n.cell)) {
        List<Cell> result = new ArrayList<>();
        for (Cell c = n.cell; !c.equals(start); c = parent.get(c)) result.add(c);
        Collections.reverse(result);
        path = List.copyOf(result);
        state = State.FOUND;
        return state;
      }
      expanded++;
      for (Step step : weightedNeighbours.apply(n.cell)) {
        Cell next = step.cell();
        double stepCost = Math.max(0.25, step.cost());
        double g = n.g + stepCost + Math.abs(next.y() - n.cell.y()) * .35;
        if (g < costs.getOrDefault(next, Double.MAX_VALUE)) {
          costs.put(next, g);
          parent.put(next, n.cell);
          open.add(new Node(next, g, g + heuristic.applyAsDouble(next)));
        }
      }
      if (System.nanoTime() - begin >= nanos) break;
    }
    if (open.isEmpty()) state = State.EXHAUSTED;
    return state;
  }
}
