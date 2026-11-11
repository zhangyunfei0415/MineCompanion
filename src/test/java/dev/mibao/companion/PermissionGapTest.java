package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PermissionGapTest {
  private static JsonObject json(String s) {
    return JsonParser.parseString(s).getAsJsonObject();
  }

  @Test
  void decorationsAreNotConfusedWithStoredInventory() {
    for (String id :
        List.of(
            "minecraft:oak_sign",
            "minecraft:oak_wall_sign",
            "minecraft:oak_hanging_sign",
            "minecraft:white_banner",
            "minecraft:white_bed",
            "minecraft:flower_pot"))
      assertEquals("", BreakPolicy.denial(id, true, false, false, false));
  }

  @Test
  void containerRemovalRequiresTargetConfirmationAndRecentEmptyEvidence() {
    assertTrue(
        BreakPolicy.denial("minecraft:chest", true, false, true, true)
            .contains("BLOCK_ENTITY_PROTECTED"));
    assertTrue(
        BreakPolicy.denial("minecraft:chest", true, true, false, true)
            .contains("CONFIRM_REQUIRED"));
    assertTrue(
        BreakPolicy.denial("minecraft:chest", true, true, true, false)
            .contains("CONTAINER_OBSERVATION_REQUIRED"));
    assertEquals("", BreakPolicy.denial("minecraft:chest", true, true, true, true));
  }

  @Test
  void unknownMachinesCannotBeUnlockedByFullPermissionOrConfirm() {
    assertTrue(
        BreakPolicy.denial("ae2:drive", true, true, true, true).contains("UNSUPPORTED_MACHINE"));
    assertTrue(
        BreakPolicy.denial("minecraft:spawner", true, true, true, true)
            .contains("BLOCK_PROTECTED"));
  }

  @Test
  void oneLineSignPadsRemainingLines() {
    assertArrayEquals(
        new String[] {"建材", "", "", ""},
        SignLines.normalize(JsonParser.parseString("[\"建材\"]").getAsJsonArray()));
  }

  @Test
  void signLinesRejectOversizedAndNonTextPayloads() {
    for (String raw :
        List.of("[]", "[1]", "[null]", "[\"a\",\"b\",\"c\",\"d\",\"e\"]", "[\"a\\nb\"]"))
      assertThrows(
          IllegalArgumentException.class,
          () -> SignLines.normalize(JsonParser.parseString(raw).getAsJsonArray()));
  }

  @Test
  void structuredCodesDistinguishPermissionCapabilityGeometryAndCursor() {
    for (String code :
        List.of(
            "TAKEOVER_DISABLED",
            "LEASE_EXPIRED",
            "UNSUPPORTED_MACHINE",
            "DOOR_BLOCKED",
            "NO_SUPPORT_FACE",
            "BLOCK_ENTITY_PROTECTED"))
      assertEquals(
          code, ReasonCodes.error("[" + code + "] detail").get("reason_code").getAsString());
    assertEquals(
        "PROTECTED_KEY",
        ReasonCodes.describe("失败：PROTECTED_KEY:protected").get("reason_code").getAsString());
    assertEquals(
        "CURSOR_OCCUPIED", ReasonCodes.describe("失败：鼠标持有物品").get("reason_code").getAsString());
  }

  @Test
  void successAndObservationTextDoNotBecomeDenials() {
    for (String text : List.of("成功：已核对菜单同步", "{\"message\":\"权限与槽位\"}"))
      assertEquals("NONE", ReasonCodes.describe(text).get("reason_code").getAsString());
    assertEquals("TOOL_FAILED", ReasonCodes.error("无法处理参数").get("reason_code").getAsString());
  }

  @Test
  void diagnosisNeverPermitsBlindReplay() {
    var d = ReasonCodes.error("[RESULT_UNCONFIRMED] 操作可能生效");
    assertFalse(d.get("retryable").getAsBoolean());
    assertFalse(d.get("requires_human").getAsBoolean());
    assertFalse(d.getAsJsonArray("suggested_alternatives").isEmpty());
  }

  @Test
  void resourceHuntingRequiresExplicitTypeAndItsOwnSwitch() {
    assertTrue(CombatPolicy.accepted("minecraft:squid", true, false));
    assertFalse(CombatPolicy.accepted("minecraft:squid", false, false));
    assertTrue(CombatPolicy.accepted("hostile", false, false));
    assertFalse(CombatPolicy.HUNTABLE.contains("minecraft:cow"));
    assertFalse(CombatPolicy.accepted("any_entity", true, false));
    assertFalse(CombatPolicy.accepted("minecraft:wither", true, false));
    assertFalse(CombatPolicy.accepted("minecraft:player", true, false));
  }

  @Test
  void recoveryCommandsRequireConfirmAndStaySmallAndSelfTargeted() {
    assertThrows(
        IllegalArgumentException.class,
        () -> TestCommandPolicy.recovery("setblock 1 64 1 air", false, 0, 64, 0));
    var r = TestCommandPolicy.recovery("fill 0 64 0 3 67 3 air", true, 0, 64, 0);
    assertFalse(r.teleport());
    assertTrue(TestCommandPolicy.recovery("tp @s 1 64 1", true, 0, 64, 0).teleport());
  }

  @Test
  void recoveryCannotBecomeAnArbitraryServerCommand() {
    for (String command :
        List.of(
            "tp @a 1 64 1",
            "tp @s ~ ~ ~",
            "setblock 1 64 1 minecraft:diamond_block",
            "fill 0 64 0 4 68 4 air",
            "setblock 100 64 1 air",
            "execute run say bad",
            "clear @s"))
      assertThrows(
          IllegalArgumentException.class,
          () -> TestCommandPolicy.recovery(command, true, 0, 64, 0));
  }

  @Test
  void longLeaseRemainsBoundedAndRevocable() {
    var s = new HarnessSession();
    s.durationSeconds(3600);
    String lease = s.connect("world", 0);
    s.check(lease, "world", 300_000);
    assertTrue(s.active(3_899_999));
    assertFalse(s.active(3_900_000));
    s.interrupt("user stop");
    assertThrows(IllegalArgumentException.class, () -> s.check(lease, "world", 300_001));
  }

  @Test
  void epochInterruptionDoesNotRevokeRetainedControlLease() {
    var s = new HarnessSession();
    s.durationSeconds(3600);
    String lease = s.connect("world", 0);
    var receipt = s.begin("r", "fetch", 1, "fetch");
    s.invalidateEpoch(2);
    assertEquals("interrupted", receipt.get("state").getAsString());
    s.check(lease, "world", 100);
    assertTrue(s.active(101));
  }

  @Test
  void leaseErrorsHaveSpecificCodes() {
    var s = new HarnessSession();
    String lease = s.connect("A", 0);
    assertTrue(
        assertThrows(IllegalArgumentException.class, () -> s.check(lease, "B", 1))
            .getMessage()
            .contains("WORLD_CHANGED"));
    assertTrue(
        assertThrows(IllegalArgumentException.class, () -> s.check("wrong", "A", 1))
            .getMessage()
            .contains("LEASE_INVALID"));
    assertTrue(
        assertThrows(IllegalArgumentException.class, () -> s.check(lease, "A", 60_000))
            .getMessage()
            .contains("LEASE_EXPIRED"));
  }

  @Test
  void gatePredictionAloneIsNotEvidenceToWalkThrough() {
    assertFalse(PassageProof.open(true, false, false, false));
    assertFalse(PassageProof.open(true, true, true, false));
    assertTrue(PassageProof.open(true, false, true, false));
    assertTrue(PassageProof.open(false, false, false, false));
  }

  @Test
  void scopedEventReadDoesNotNeedOrRenewWriteLease() {
    var events = new ArrayDeque<JsonObject>();
    events.add(json("{\"sequence\":5,\"scope\":\"A\",\"text\":\"a\"}"));
    events.add(json("{\"sequence\":6,\"scope\":\"B\",\"text\":\"b\"}"));
    var page = HarnessEventPage.read(events, "A", 0, 6);
    assertEquals(1, page.getAsJsonArray("events").size());
    assertTrue(page.get("read_only").getAsBoolean());
    assertTrue(page.get("truncated").getAsBoolean());
    assertEquals(6, page.get("next").getAsInt());
    assertEquals(0, HarnessEventPage.read(events, "C", 0, 6).getAsJsonArray("events").size());
  }

  @Test
  void adapterDescriptorsMatchBlocksButOnlyReferenceExistingSkills() {
    var d =
        AdapterDescriptor.parse(
            json(
                "{\"id\":\"test_oven\",\"blocks\":[\"test:oven\",\"sample:*\"],\"skill\":\"cook_demo\"}"));
    assertTrue(d.matches("test:oven"));
    assertTrue(d.matches("sample:a"));
    assertFalse(d.matches("other:oven"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AdapterDescriptor.parse(
                json("{\"id\":\"../script\",\"blocks\":[\"sample:*\"],\"skill\":\"bad\"}")));
  }

  @Test
  void cachedWorldQueryCanReachBeyondOldFirstSixtyFour() {
    var world = new WorldMemory();
    for (int i = 0; i < 100; i++)
      world.observe("A", i, 64, 0, "minecraft:chest", "chest", "箱子", "storage", 1);
    assertEquals(100, world.query("A", 0, 64, 0, "", "all", 2048, 0).size());
    assertEquals(64, world.query("A", 0, 64, 0, "", "all", 64, 0).size());
    assertTrue(world.query("B", 0, 64, 0, "", "all", 2048, 0).isEmpty());
  }

  @Test
  void newToolsRemainInTheSharedPermissionAndSchemaContract() {
    for (String tool : List.of("break_block", "attack_block", "swim_out", "unstick")) {
      assertTrue(Rules.WRITE.contains(tool));
      assertTrue(CapabilityCatalog.mutates(tool));
    }
    assertTrue(Rules.limitedDenial("break_block", false, true, true).contains("禁止采集"));
    var tools = ToolSchemas.all().toString();
    assertTrue(tools.contains("capability_probe"));
    assertTrue(tools.contains("machine_adapters"));
    assertTrue(tools.contains("face"));
  }

  @Test
  void malformedRecipeDoesNotAbortOtherOutputMatches() {
    var result =
        RecipeLookupPage.page(
            List.of("wood", "bad", "iron"),
            v -> {
              if (v.equals("bad")) throw new IllegalStateException();
              return true;
            },
            v -> {
              var r = new JsonObject();
              r.addProperty("id", v);
              return r;
            },
            0,
            10);
    assertEquals(2, result.getAsJsonArray("recipes").size());
    assertTrue(result.get("incomplete_due_to_recipe_errors").getAsBoolean());
  }

  @Test
  void recipePagingCountsValidResultsAfterBrokenRecipe() {
    var result =
        RecipeLookupPage.page(
            List.of("bad", "a", "b", "c"),
            v -> true,
            v -> {
              if (v.equals("bad")) throw new IllegalArgumentException();
              var r = new JsonObject();
              r.addProperty("id", v);
              return r;
            },
            1,
            1);
    assertEquals(
        "b", result.getAsJsonArray("recipes").get(0).getAsJsonObject().get("id").getAsString());
    assertTrue(result.get("has_more").getAsBoolean());
  }
}
