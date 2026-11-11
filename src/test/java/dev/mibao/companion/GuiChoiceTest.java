package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class GuiChoiceTest {
  @Test
  void singleMatchNeedsNoSelector() {
    assertEquals(0, GuiChoice.choose(1, -1, ""));
    assertFalse(GuiChoice.specified(-1, ""));
  }

  @Test
  void multipleMatchesAreRefusedWithoutSelector() {
    assertEquals(-1, GuiChoice.choose(2, -1, ""));
    assertEquals(-1, GuiChoice.choose(3, -1, null));
  }

  @Test
  void indexSelectsExactMatch() {
    assertEquals(0, GuiChoice.choose(3, 0, ""));
    assertEquals(2, GuiChoice.choose(3, 2, ""));
    assertTrue(GuiChoice.specified(1, ""));
    var error = assertThrows(IllegalArgumentException.class, () -> GuiChoice.choose(2, 2, ""));
    assertTrue(error.getMessage().startsWith("[ARGUMENT_INVALID]"));
  }

  @Test
  void nthSelectsFirstOrLast() {
    assertEquals(0, GuiChoice.choose(4, -1, "first"));
    assertEquals(3, GuiChoice.choose(4, -1, "last"));
    assertEquals(3, GuiChoice.choose(4, -1, " LAST "));
    assertThrows(IllegalArgumentException.class, () -> GuiChoice.choose(4, -1, "middle"));
  }

  @Test
  void emptyMatchListIsRefused() {
    assertEquals(-1, GuiChoice.choose(0, 0, "first"));
  }

  @Test
  void candidatesMessageListsSlotsAndSelectors() {
    String text =
        GuiChoice.candidates(
            2,
            "物品槽",
            List.of(
                new GuiChoice.Slot(12, "minecraft:lava_bucket", 2),
                new GuiChoice.Slot(30, "minecraft:lava_bucket", 1)));
    assertTrue(text.startsWith("[GUI_MULTIPLE_MATCHES]"));
    assertTrue(text.contains("槽12"));
    assertTrue(text.contains("槽30"));
    assertTrue(text.contains("index=0..1"));
    assertTrue(text.contains("nth=first/last"));
    assertFalse(text.contains("\n"));
  }

  @Test
  void candidatesMessageCapsLongList() {
    var slots =
        java.util.stream.IntStream.range(0, 12)
            .mapToObj(i -> new GuiChoice.Slot(i, "minecraft:stick", 1))
            .toList();
    String text = GuiChoice.candidates(12, "控件", slots);
    assertTrue(text.contains("另有 4 个"));
  }
}
