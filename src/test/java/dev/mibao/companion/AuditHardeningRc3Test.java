package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class AuditHardeningRc3Test {
  @Test
  void explorationGraphPinsWalkedEndpointsAtTrimCapacityAndDropsDanglingEdges() {
    var g = new ExplorationGraph();
    // Populate 4096 separate PATH nodes so MAX_NODES trim capacity is reached on the next mark/walk.
    var firstAnchor = new ExplorationGraph.Cell(0, 64, 2);
    g.mark(firstAnchor, ExplorationGraph.Kind.PATH, "", List.of(), 10L);
    for (int i = 1; i < ExplorationGraph.MAX_NODES; i++) {
      g.mark(new ExplorationGraph.Cell(i * 2, 64, 0), ExplorationGraph.Kind.PATH, "", List.of(), 10L + i);
    }
    assertEquals(ExplorationGraph.MAX_NODES, g.nodes().size());
    // Walking from the oldest PATH node 'firstAnchor' to a brand-new adjacent cell pins both during trim.
    var extra = new ExplorationGraph.Cell(1, 64, 2);
    g.walk(firstAnchor, extra, false, 5000L);
    assertTrue(g.nodes().size() <= ExplorationGraph.MAX_NODES);
    assertNotNull(g.node(firstAnchor));
    assertNotNull(g.node(extra));
    assertFalse(g.edges().isEmpty());

    // Corrupt a snapshot with a dangling edge (adjacent geometry, missing node 'ghost'); from() skips it safely.
    var snap = g.snapshot();
    var ghost = new ExplorationGraph.Cell(1, 64, 3);
    var badEdges = new java.util.ArrayList<>(snap.edges());
    badEdges.add(
        new ExplorationGraph.Edge(
            ghost,
            extra,
            "",
            ExplorationGraph.EdgeKind.BIDIRECTIONAL,
            ExplorationGraph.State.VERIFIED,
            List.of(ghost, extra),
            false,
            5001L,
            ""));
    var corrupted = new ExplorationGraph.Snapshot(snap.nodes(), badEdges);
    var reloaded = ExplorationGraph.from(corrupted);
    assertNotNull(reloaded.node(extra));
    assertNull(reloaded.node(ghost));
  }

  @Test
  void smeltFuelPlanMinimizesWasteBeforeAlphabeticalOrder() {
    // 200 ticks (1 item): coal block (16000) wastes far more than coal (1600).
    var plan =
        SmeltFuelPlan.choose(
            200,
            "",
            0,
            Map.of("minecraft:coal_block", 1, "minecraft:coal", 4, "minecraft:iron_ore", 1),
            Map.of("minecraft:coal_block", 16000, "minecraft:coal", 1600),
            "minecraft:iron_ore",
            1);
    assertEquals("minecraft:coal", plan.fuel());
    assertEquals(1, plan.toAdd());
    assertEquals(0, plan.missing());
  }

  @Test
  void versionRangeHandlesFourSegmentsAndMinecraftPrefix() {
    assertTrue(VersionRange.matches(">=15.2.0.20", "15.2.0.27").matched());
    assertFalse(VersionRange.matches(">=15.2.0.30", "15.2.0.27").matched());
    assertTrue(VersionRange.matches(">=3.20.0", "mc1.20.1-3.20.5").matched());
    assertFalse(VersionRange.matches(">=4.0.0", "1.20.1-3.20.5").matched());
  }

  @Test
  void gameCommandPolicyGatesSummonGiveNbtAndQuotedNbtSelectors() {
    var profile = new CommandProfile();
    profile.advancedCommands = true;
    profile.customCommands = false;
    profile.broadSelectors = false;

    // summon/give without NBT is allowed under existing toggles; adding NBT requires customCommands.
    assertEquals(
        "summon minecraft:zombie 1 64 0",
        GameCommandPolicy.parse("summon minecraft:zombie ~1 ~ ~", profile, 0, 64, 0).command());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            GameCommandPolicy.parse(
                "summon minecraft:command_block_minecart ~ ~ ~ {Command:\"kill @e\"}",
                profile,
                0,
                64,
                0));
    assertThrows(
        IllegalArgumentException.class,
        () -> GameCommandPolicy.parse("give @s minecraft:stone{Custom:1}", profile, 0, 64, 0));

    // Even when customCommands=true, selectors inside quoted NBT remain blocked unless broadSelectors=true.
    profile.customCommands = true;
    assertThrows(
        IllegalArgumentException.class,
        () ->
            GameCommandPolicy.parse(
                "summon minecraft:command_block_minecart ~ ~ ~ {Command:\"kill @e\"}",
                profile,
                0,
                64,
                0));
    profile.broadSelectors = true;
    assertEquals(
        "give @s minecraft:stone{Custom:1}",
        GameCommandPolicy.parse("give @s minecraft:stone{Custom:1}", profile, 0, 64, 0).command());
  }

  @Test
  void chatOutboxGiveOutcomeAndInjectiveBundleStemHoldInvariants() {
    assertEquals("hello world!", ChatOutbox.chatSafe("hello\nworld\u00a7\u007f!"));

    var expected =
        GiveOutcome.parse("/give @s minecraft:diamond_sword{display:{Name:'\"Blade\"'}} 1", "Tester");
    assertNotNull(expected);
    assertEquals("minecraft:diamond_sword", expected.itemId());
    assertEquals(1, expected.count());

    assertNotEquals(
        BundleAdaptation.uniqueFileStem("a:b_c"), BundleAdaptation.uniqueFileStem("a_b:c"));
    assertEquals("builtin_cminecraft_sfoo", BundleAdaptation.uniqueFileStem("builtin:minecraft/foo"));
  }
}
