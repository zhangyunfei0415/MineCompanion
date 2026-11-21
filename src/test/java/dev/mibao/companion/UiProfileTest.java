package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class UiProfileTest {
  private static UiProfile sampleProfile() {
    String json =
        """
        {
          "id": "mekanism:enrichment_ui",
          "name": "富集仓界面",
          "match": {
            "portable_signatures": ["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"],
            "layout_signatures": ["bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"],
            "menu_classes": ["mekanism.EnrichmentMenu"],
            "block_ids": ["mekanism:enrichment_chamber"],
            "image_width": 176,
            "image_height": 166
          },
          "slots": [
            {"role": "input", "x": 64, "y": 17, "menu_slot": 0, "virtual": false},
            {"role": "output", "x": 116, "y": 35, "menu_slot": 2, "virtual": false},
            {"role": "ghost_filter", "menu_slot": 4, "virtual": true}
          ],
          "controls": [
            {
              "name": "side_config",
              "kind": "relative_point",
              "dx": -14,
              "dy": 16,
              "button": 0,
              "shift": false,
              "expected_tooltip_contains": ["Side Config", "侧面配置"]
            },
            {
              "name": "confirm_btn",
              "kind": "widget_label",
              "label": "确认"
            }
          ]
        }
        """;
    return new UiProfile(JsonParser.parseString(json).getAsJsonObject());
  }

  @Test
  void matchesByPortableSignatureBeforeLayoutAndBlockMenu() {
    var profile = sampleProfile();
    assertEquals(
        1000,
        profile.matchScore(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "other",
            "other:block",
            "other.Menu"));
    assertEquals(
        650,
        profile.matchScore(
            "other",
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            "mekanism:enrichment_chamber",
            "other.Menu"));
    assertEquals(
        300,
        profile.matchScore("other", "other", "mekanism:enrichment_chamber", "mekanism.EnrichmentMenu"));
    assertEquals(0, profile.matchScore("other", "other", "other:block", "mekanism.EnrichmentMenu"));
  }

  @Test
  void resolvesSlotByCoordinatesFirstThenToleranceAndMenuSlotFallback() {
    var profile = sampleProfile();
    // Suppose menu slot 0 moved to index 1 in a newer version, and inventory occupies earlier indices
    var live =
        List.of(
            new UiProfile.LiveSlot(0, 8, 84, true, "minecraft:air", 0, ""),
            new UiProfile.LiveSlot(1, 64, 17, false, "minecraft:air", 0, ""),
            new UiProfile.LiveSlot(2, 117, 36, false, "mekanism:dust_iron", 4, ""), // 2px manhattan offset
            new UiProfile.LiveSlot(4, 150, 15, false, "minecraft:air", 0, ""));

    assertEquals(1, profile.resolveSlotIndex("input", 0, live));
    assertEquals(2, profile.resolveSlotIndex("output", 0, live));
    assertEquals(4, profile.resolveSlotIndex("ghost_filter", 0, live));
    assertEquals(-1, profile.resolveSlotIndex("nonexistent", 0, live));
  }

  @Test
  void patchesSignaturesSlotsAndControlsForLocalAdaptation() {
    var profile = sampleProfile();
    var patch =
        JsonParser.parseString(
                """
                {
                  "add_portable_signatures": ["cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"],
                  "slots": [
                    {"role": "input", "x": 66, "y": 19, "menu_slot": 0, "virtual": false}
                  ],
                  "controls": [
                    {
                      "name": "side_config",
                      "kind": "relative_point",
                      "dx": -18,
                      "dy": 20,
                      "button": 0,
                      "shift": false,
                      "expected_tooltip_contains": ["Config"]
                    }
                  ]
                }
                """)
            .getAsJsonObject();

    UiProfile patched = profile.withPatch(patch);
    assertEquals(2, patched.match.portableSignatures().size());
    assertEquals(-18, patched.findControl("side_config").dx());
    assertEquals(20, patched.findControl("side_config").dy());
    assertEquals("确认", patched.findControl("confirm_btn").label());

    var liveNewCoords = List.of(new UiProfile.LiveSlot(3, 66, 19, false, "minecraft:air", 0, ""));
    assertEquals(3, patched.resolveSlotIndex("input", 0, liveNewCoords));
  }

  @Test
  void rejectsInvalidHashesMissingCoordsAndInvalidControls() {
    var badSig =
        JsonParser.parseString(
                """
                {
                  "id": "bad:ui",
                  "match": {"portable_signatures": ["not-a-sha256"]}
                }
                """)
            .getAsJsonObject();
    assertThrows(IllegalArgumentException.class, () -> new UiProfile(badSig));

    var badCtrl =
        JsonParser.parseString(
                """
                {
                  "id": "bad:ctrl",
                  "controls": [{"name": "btn", "kind": "widget_label"}]
                }
                """)
            .getAsJsonObject();
    assertThrows(IllegalArgumentException.class, () -> new UiProfile(badCtrl));
  }
}
