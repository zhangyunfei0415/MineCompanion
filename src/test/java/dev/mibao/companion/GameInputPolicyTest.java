package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.Test;

class GameInputPolicyTest {
  @Test
  void ctrlSlotAndEscapeNeedNoRegisteredKey() {
    assertEquals(Set.of(341, 53), GameInputPolicy.keys(53, 2));
    assertEquals(Set.of(256), GameInputPolicy.keys(256, 0));
    assertEquals(Set.of(340, 341, 342, 65), GameInputPolicy.keys(65, 7));
  }

  @Test
  void rejectSystemKeysAndInvalidBounds() {
    for (int k : new int[] {-1, 0, 31, 343, 347, 349})
      assertThrows(IllegalArgumentException.class, () -> GameInputPolicy.key(k));
    assertThrows(IllegalArgumentException.class, () -> GameInputPolicy.modifiers(8));
    assertThrows(IllegalArgumentException.class, () -> GameInputPolicy.ticks(0));
    assertThrows(IllegalArgumentException.class, () -> GameInputPolicy.ticks(201));
  }

  @Test
  void heldChordExpiresAndCannotLeakAcrossEpochOrWindow() {
    var s = new VirtualKeyState();
    s.begin(12, 7, 100, GameInputPolicy.keys(53, 2));
    assertEquals(1, s.query(12, 7, 101, 341));
    assertEquals(-1, s.query(13, 7, 101, 341));
    assertEquals(-1, s.query(12, 8, 101, 341));
    assertFalse(s.active(12, 7, 500000101));
    s.clear();
    assertTrue(s.empty());
  }

  @Test
  void newToolsHaveCorrectWriteBoundary() {
    assertTrue(Rules.WRITE.contains("game_input"));
    assertTrue(Rules.WRITE.contains("gui_back"));
    assertFalse(Rules.WRITE.contains("inspect_screen"));
    assertTrue(Rules.TOOLS.contains("inspect_screen"));
  }

  @Test
  void openedScreenDoesNotBecomeAnError() {
    assertEquals("NONE", ReasonCodes.describe("输入已发送并释放；界面功能未核验").get("reason_code").getAsString());
    assertEquals(
        "NONE", ReasonCodes.describe("输入已执行并释放：已打开 任务书；界面结果待核验").get("reason_code").getAsString());
    assertEquals(
        "SCREEN_BLOCKING", ReasonCodes.describe("失败：界面阻塞").get("reason_code").getAsString());
  }
}
