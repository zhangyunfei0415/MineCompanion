package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlaceCandidatesTest {
  private static PlaceCandidates.Site site(int x, int y, int z, double d, String reason) {
    return new PlaceCandidates.Site(x, y, z, d, reason);
  }

  @Test
  void usableSitesAreCountedAndNearestIsReported() {
    var report =
        PlaceCandidates.build(
            List.of(
                site(1, 0, 0, 1.0, "目标格不是空气：minecraft:stone"),
                site(2, 0, 0, 2.0, ""),
                site(3, 0, 0, 3.0, "目标区块未加载"),
                site(0, 1, 0, 1.5, "")));
    assertEquals(4, report.checked());
    assertEquals(2, report.usable());
    assertEquals(0, report.nearest().x());
    assertEquals(1, report.nearest().y());
    assertEquals(2, report.rejected().size());
    assertEquals(1.0, report.rejected().get(0).distance(), 0.001);
  }

  @Test
  void textListsRejectedReasonsOnOneLine() {
    var report =
        PlaceCandidates.build(
            List.of(
                site(1, 0, 0, 1.0, "目标格不是空气：minecraft:stone"),
                site(2, 0, 0, 2.0, "目标格存在流体"),
                site(3, 0, 0, 3.0, "")));
    String text = PlaceCandidates.text(report);
    assertFalse(text.startsWith("["));
    assertTrue(text.contains("checked=3"));
    assertTrue(text.contains("可用=1"));
    assertTrue(text.contains("(1,0,0)目标格不是空气"));
    assertTrue(text.contains("(2,0,0)目标格存在流体"));
    assertFalse(text.contains("\n"));
  }

  @Test
  void jsonCarriesCandidateRejectionDetails() {
    var report =
        PlaceCandidates.build(List.of(site(1, 0, 0, 1.5, "目标格被玩家/实体占用"), site(2, 0, 0, 2.0, "")));
    JsonObject json = PlaceCandidates.json(report);
    assertEquals(2, json.get("checked").getAsInt());
    assertEquals(1, json.get("usable").getAsInt());
    assertEquals(2, json.getAsJsonObject("nearest").get("x").getAsInt());
    JsonObject rejected = json.getAsJsonArray("rejected").get(0).getAsJsonObject();
    assertEquals("目标格被玩家/实体占用", rejected.get("reason").getAsString());
  }

  @Test
  void reportIsCappedButCountsEverything() {
    List<PlaceCandidates.Site> sites = new ArrayList<>();
    for (int i = 0; i < 12; i++) sites.add(site(i, 0, 0, i, "目标区块未加载"));
    var report = PlaceCandidates.build(sites);
    assertEquals(12, report.checked());
    assertEquals(0, report.usable());
    assertNull(report.nearest());
    String text = PlaceCandidates.text(report);
    assertTrue(text.contains("最近可用=无"));
    assertTrue(text.contains("另有 4 个未列出"));
    assertEquals(
        PlaceCandidates.REPORT_LIMIT,
        PlaceCandidates.json(report).getAsJsonArray("rejected").size());
  }

  @Test
  void emptyInputIsReportedWithoutCrashing() {
    var report = PlaceCandidates.build(List.of());
    assertEquals(0, report.checked());
    assertNull(report.nearest());
    assertTrue(PlaceCandidates.text(report).contains("最近可用=无"));
  }
}
