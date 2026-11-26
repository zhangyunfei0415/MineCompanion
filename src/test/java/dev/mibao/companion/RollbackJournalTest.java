package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class RollbackJournalTest {
  @Test
  void boundsRingBufferAndSelectsLatestInReverseOrder() {
    var log = new RollbackJournal.Log();
    for (int i = 0; i < RollbackJournal.MAX_ENTRIES + 25; i++) {
      RollbackJournal.record(
          log,
          "minecraft:overworld",
          i,
          64,
          0,
          RollbackJournal.Action.BREAK,
          "minecraft:oak_planks",
          "minecraft:air",
          Map.of(),
          false,
          "MiningJob",
          "t1",
          "",
          1000L + i);
    }
    assertEquals(RollbackJournal.MAX_ENTRIES, log.entries.size());
    assertEquals(26L, log.entries.get(0).id);

    var restore = RollbackJournal.selectForRestore(log, "minecraft:overworld", 3, List.of(), "");
    assertEquals(3, restore.size());
    assertEquals(525L, restore.get(0).id);
    assertEquals(523L, restore.get(2).id);

    RollbackJournal.removeRestored(log, List.of(525L, 524L));
    assertEquals(RollbackJournal.MAX_ENTRIES - 2, log.entries.size());

    // Interleaved jobs & countRetained accuracy at 499 / 500 / 501 boundary
    var log2 = new RollbackJournal.Log();
    for (int i = 0; i < 499; i++) {
      RollbackJournal.record(
          log2,
          "minecraft:overworld",
          i,
          64,
          0,
          RollbackJournal.Action.BREAK,
          "minecraft:stone",
          "minecraft:air",
          Map.of(),
          false,
          "terraform_run",
          "jobA",
          "",
          2000L + i);
    }
    assertEquals(499, RollbackJournal.countRetained(log2, "minecraft:overworld", "jobA"));
    RollbackJournal.record(
        log2,
        "minecraft:overworld",
        500,
        64,
        0,
        RollbackJournal.Action.PLACE,
        "minecraft:air",
        "minecraft:dirt",
        Map.of(),
        false,
        "PlaceJob",
        "jobB",
        "",
        3000L);
    assertEquals(499, RollbackJournal.countRetained(log2, "minecraft:overworld", "jobA"));
    assertEquals(1, RollbackJournal.countRetained(log2, "minecraft:overworld", "jobB"));
    RollbackJournal.record(
        log2,
        "minecraft:overworld",
        501,
        64,
        0,
        RollbackJournal.Action.PLACE,
        "minecraft:air",
        "minecraft:dirt",
        Map.of(),
        false,
        "terraform_run",
        "jobB",
        "",
        3001L);
    assertEquals(498, RollbackJournal.countRetained(log2, "minecraft:overworld", "jobA"));
    assertEquals(2, RollbackJournal.countRetained(log2, "minecraft:overworld", "jobB"));
    assertEquals(0, RollbackJournal.countRetained(log2, "minecraft:the_nether", "jobB"));
  }
}
