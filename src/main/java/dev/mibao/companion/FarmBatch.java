package dev.mibao.companion;

import java.util.*;

/** One procedure owns one set of plants. Neighbouring crops cannot complete later stages. */
public final class FarmBatch<T> {
  public enum Stage {
    PLANT,
    GROW,
    HARVEST
  }

  private final int wanted;
  private final boolean plant, grow, harvest;
  private final Map<Stage, Set<T>> verified = new EnumMap<>(Stage.class);

  public FarmBatch(int wanted, boolean plant, boolean grow, boolean harvest) {
    if (wanted < 1 || wanted > 64) throw new IllegalArgumentException("农作批次数量须为1..64");
    this.wanted = wanted;
    this.plant = plant;
    this.grow = grow;
    this.harvest = harvest;
    for (var stage : Stage.values()) verified.put(stage, new LinkedHashSet<>());
  }

  public boolean accepts(Stage stage, T position) {
    Objects.requireNonNull(position);
    if (stage != Stage.PLANT && plant && !verified.get(Stage.PLANT).contains(position))
      return false;
    return stage != Stage.HARVEST || !grow || verified.get(Stage.GROW).contains(position);
  }

  public void record(Stage stage, T position) {
    if (!accepts(stage, position)) throw new IllegalStateException("目标不属于本批已核实作物");
    var values = verified.get(stage);
    if (!values.contains(position) && values.size() >= wanted)
      throw new IllegalStateException("农作批次数量超出目标");
    values.add(position);
  }

  public int count(Stage stage) {
    return verified.get(stage).size();
  }

  public boolean bound(Stage stage) {
    return stage == Stage.GROW && plant || stage == Stage.HARVEST && (plant || grow);
  }

  public Set<T> targets(Stage stage) {
    return Set.copyOf(verified.get(stage == Stage.HARVEST && grow ? Stage.GROW : Stage.PLANT));
  }

  public boolean complete() {
    return (!plant || count(Stage.PLANT) == wanted)
        && (!grow || count(Stage.GROW) == wanted)
        && (!harvest || count(Stage.HARVEST) == wanted);
  }

  public String summary() {
    return "本批位置核验：播种 "
        + count(Stage.PLANT)
        + "，成熟 "
        + count(Stage.GROW)
        + "，收割补种 "
        + count(Stage.HARVEST)
        + "；目标 "
        + wanted;
  }
}
