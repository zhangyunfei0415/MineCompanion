package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GuiGuardTest {
  @Test
  void replacedOrMissingScreenAndOldViewCannotBeUsed() {
    Object screen = new Object();
    assertTrue(GuiGuard.valid(4, 4, screen, screen, "same", "same"));
    assertFalse(GuiGuard.valid(3, 4, screen, screen, "same", "same"));
    assertFalse(GuiGuard.valid(0, 0, screen, screen, "same", "same"));
    assertFalse(GuiGuard.valid(4, 4, screen, new Object(), "same", "same"));
  }

  @Test
  void changedMenuContentsCursorGeometryOrWidgetStateInvalidatesView() {
    Object screen = new Object();
    for (String state :
        new String[] {"slot_changed", "cursor_changed", "resized", "disabled", "label_changed"})
      assertFalse(GuiGuard.valid(1, 1, screen, screen, "before", state));
  }

  @Test
  void genericGuiClicksAreNeverReplayedFromSavedSkills() {
    for (String tool :
        new String[] {"gui_slot", "gui_button", "gui_text", "gui_scroll", "wait_menu"}) {
      JsonArray a =
          JsonParser.parseString("[{\"tool\":\"" + tool + "\",\"args\":{\"gui_view\":2}}]")
              .getAsJsonArray();
      assertThrows(
          IllegalArgumentException.class,
          () -> SkillSteps.prepare(a, true, Set.of(), new JsonObject()));
    }
  }
}
