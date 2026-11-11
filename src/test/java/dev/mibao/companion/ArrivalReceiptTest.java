package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonNull;
import org.junit.jupiter.api.Test;

class ArrivalReceiptTest {
  @Test
  void landingReportsDeviationFromTheBlockCenter() {
    var landing = ArrivalReceipt.landing(4145.42, 71.0, 29.60, 4145, 71, 31, 71.0);
    assertEquals(4145.42, landing.get("x").getAsDouble(), 1e-9);
    assertEquals(4145.5, landing.get("target_x").getAsDouble(), 1e-9);
    assertEquals(31.5, landing.get("target_z").getAsDouble(), 1e-9);
    assertEquals(71.0, landing.get("target_y").getAsDouble(), 1e-9);
    assertEquals(1.9, landing.get("horizontal_deviation").getAsDouble(), 0.01);
    assertEquals(0.0, landing.get("vertical_deviation").getAsDouble(), 1e-9);

    String text = ArrivalReceipt.landingText(4145.42, 71.0, 29.60, 4145, 71, 31, 71.0);
    assertTrue(text.startsWith("落点=(4145.42,71.00,29.60)"), text);
    assertTrue(text.contains("水平偏差=1.90格"), text);
    assertTrue(text.contains("垂直偏差=0.00格"), text);
  }

  @Test
  void landingFallsBackToTheCellHeightWithoutASupportSurface() {
    var landing = ArrivalReceipt.landing(1, 2, 3, 1, 2, 3, Double.NaN);
    assertEquals(2.0, landing.get("target_y").getAsDouble(), 1e-9);
    assertEquals(0.71, landing.get("horizontal_deviation").getAsDouble(), 0.01);
    assertEquals(0.0, landing.get("vertical_deviation").getAsDouble(), 1e-9);
  }

  @Test
  void stanceCarriesCandidateCountsAndNearestCell() {
    var stance = ArrivalReceipt.stance(5, true, 196, 12, 8, 7, 3, 40, 2, 1, 10, 20, 30, 2.5);
    assertEquals(5, stance.get("max_radius").getAsInt());
    assertTrue(stance.get("exact_stance").getAsBoolean());
    assertEquals(196, stance.get("scanned").getAsInt());
    assertEquals(12, stance.get("stand_cells").getAsInt());
    assertEquals(8, stance.get("visible_from_stand").getAsInt());
    assertEquals(7, stance.get("both").getAsInt());
    assertEquals(3, stance.get("visible_only").getAsInt());
    var rejected = stance.getAsJsonObject("rejected");
    assertEquals(40, rejected.get("blocked").getAsInt());
    assertEquals(2, rejected.get("unloaded").getAsInt());
    assertEquals(1, rejected.get("hazard").getAsInt());
    var nearest = stance.getAsJsonObject("nearest");
    assertEquals(10, nearest.get("x").getAsInt());
    assertEquals(2.5, nearest.get("distance").getAsDouble(), 1e-9);
    assertTrue(ArrivalReceipt.candidatesText(stance).startsWith("candidates={"));
  }

  @Test
  void stanceOmitsNearestWhenNothingWasFound() {
    var stance = ArrivalReceipt.stance(3, false, 196, 0, 0, 0, 0, 196, 0, 0, null, null, null, 0);
    assertTrue(stance.get("nearest") instanceof JsonNull);
    assertFalse(stance.get("exact_stance").getAsBoolean());
  }
}
