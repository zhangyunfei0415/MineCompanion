package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class QuestWidgetPolicyTest {
  @Test
  void readOnlyFtbTitleDoesNotBlockWholeQuestDetails() {
    assertFalse(
        QuestWidgetPolicy.privateInput(
            List.of("dev.ftb.mods.ftblibrary.ui.TextField", "dev.ftb.mods.ftblibrary.ui.Widget")));
    assertFalse(
        QuestWidgetPolicy.privateInput(
            List.of(
                "dev.ftb.mods.ftblibrary.ui.DisabledButtonTextField",
                "dev.ftb.mods.ftblibrary.ui.TextField")));
  }

  @Test
  void actualEditableInputsRemainPrivate() {
    assertTrue(QuestWidgetPolicy.privateInput(List.of("dev.ftb.mods.ftblibrary.ui.TextBox")));
    assertTrue(
        QuestWidgetPolicy.privateInput(List.of("net.minecraft.client.gui.components.EditBox")));
    assertTrue(QuestWidgetPolicy.privateInput(List.of("other.PasswordField")));
    assertTrue(QuestWidgetPolicy.privateInput(List.of("other.TextField")));
  }

  @Test
  void rolesDescribeButtonsWithoutGuessingCompletion() {
    String prefix = "dev.ftb.mods.ftbquests.client.gui.quests.";
    assertEquals("task_action", QuestWidgetPolicy.role(prefix + "TaskButton"));
    assertEquals("claim_reward", QuestWidgetPolicy.role(prefix + "RewardButton"));
    assertEquals("claim_all_rewards", QuestWidgetPolicy.role(prefix + "CollectRewardsButton"));
    assertEquals("other_widget", QuestWidgetPolicy.role("unknown.SomeButton"));
  }
}
