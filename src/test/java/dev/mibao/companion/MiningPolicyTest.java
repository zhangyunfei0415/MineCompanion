package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MiningPolicyTest {
  private MiningSpec parse(String value) {
    return MiningSpec.parse(JsonParser.parseString(value).getAsJsonObject());
  }

  @Test
  void goalsHaveIndependentQuantityAndTerrainBudgets() {
    assertEquals("minecraft:raw_iron", parse("{block:'minecraft:iron_ore'}").item());
    assertEquals("vein", parse("{block:'minecraft:iron_ore'}").mode());
    assertEquals(4096, parse("{block:'minecraft:stone',goal:'blocks',count:4096}").maxBlocks());
    assertEquals(
        "minecraft:iron_ore", parse("{block:'minecraft:iron_ore',tool_policy:'silk'}").item());
    assertThrows(
        IllegalArgumentException.class, () -> parse("{block:'#forge:ores/iron',goal:'deliver'}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> parse("{block:'minecraft:stone',goal:'blocks',count:65,max_blocks:64}"));
    assertThrows(
        IllegalArgumentException.class, () -> parse("{block:'minecraft:stone',mode:'anything'}"));
  }

  @Test
  void capacityBlockDistinguishesConfiguredDeliveryFromLackOfVerifiedCargo() {
    var configured = parse("{block:'minecraft:iron_ore',delivery:'mine chest',free_slots:3}");
    assertTrue(configured.capacityBlockDetail(1).contains("主背包空槽 1 / 作业要求 3"));
    assertTrue(configured.capacityBlockDetail(1).contains("已配置交付箱"));
    assertTrue(configured.capacityBlockDetail(1).contains("没有本次作业核实入包"));
    assertFalse(configured.capacityBlockDetail(1).contains("未配置交付箱"));
    assertTrue(parse("{block:'minecraft:iron_ore'}").capacityBlockDetail(0).contains("未配置交付箱"));
  }

  @Test
  void fixedWorkAreaUsesIndependentVerticalBoundsAndCannotOverflow() {
    var spec = parse("{block:'minecraft:iron_ore',radius:8,vertical_range:3}");
    assertTrue(spec.contains(0, -59, 0, 8, -62, -8));
    assertFalse(spec.contains(0, -59, 0, 9, -59, 0));
    assertFalse(spec.contains(0, -59, 0, 0, -55, 0));
    assertFalse(spec.contains(Integer.MIN_VALUE, 0, 0, Integer.MAX_VALUE, 0, 0));
  }

  @Test
  void outputVariantsAndTransferDirectionRemainSeparate() {
    var ledger = new MiningLedger();
    ledger.credit(Map.of(), Map.of("ore#A", 3, "ore#B", 8), Map.of("ore#A", 3), true);
    assertEquals(0, ledger.progress("items", 1, s -> s.equals("ore#B")));
    assertFalse(ledger.transfer("ore#A", 3, 3, false));
    assertFalse(ledger.transfer("ore#A", 3, 4, true));
    assertTrue(ledger.delivered.isEmpty());
    assertEquals(3, ledger.progress("items", 1, s -> s.equals("ore#A")));
  }

  @Test
  void supplyAndUnverifiedInventoryCannotCreateProductionCredit() {
    var ledger = new MiningLedger();
    assertTrue(ledger.credit(Map.of(), Map.of("iron", 64), Map.of(), true).isEmpty());
    assertTrue(ledger.credit(Map.of(), Map.of("iron", 64), Map.of("iron", 1), false).isEmpty());
    assertEquals(
        Map.of("iron", 2),
        ledger.credit(Map.of("iron", 4), Map.of("iron", 10), Map.of("iron", 2), true));
  }

  @Test
  void onlyCreditedSurplusCanLeaveAndPartialTransfersKeepTheirProof() {
    var ledger = new MiningLedger();
    ledger.received.put("ore#A", 4);
    assertEquals(
        Map.of("ore#A", 2), ledger.cargo(Map.of("ore#A", 10), Map.of("ore#A", 12, "ore#B", 64)));
    assertFalse(ledger.transfer("ore#A", 4, 2, true));
    assertEquals(2, ledger.delivered.get("ore#A"));
    assertFalse(ledger.transfer("ore#A", 4, 3, true));
    assertTrue(ledger.transfer("ore#A", 2, 2, true));
    assertEquals(4, ledger.progress("items", 7, s -> true));
    assertEquals(4, ledger.progress("deliver", 7, s -> true));
    assertEquals(7, ledger.progress("blocks", 7, s -> true));
  }
}
