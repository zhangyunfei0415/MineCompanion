package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import org.junit.jupiter.api.Test;

class GoalBoardTest {
  @Test
  void managesStepsCheckpointsAndProducesCompatibleSnapshot() {
    var state = new GoalBoard.State();
    var setArgs = new JsonObject();
    setArgs.addProperty("goal", "建起全自动原木厂");
    var steps = new JsonArray();
    steps.add("备齐圆石与原木");
    steps.add("平整施工地基");
    steps.add("运行 blueprint_build");
    setArgs.add("steps", steps);
    GoalBoard.apply(state, "set", setArgs, 1000L);

    var upd1 = new JsonObject();
    upd1.addProperty("step_index", 1);
    upd1.addProperty("status", "done");
    GoalBoard.apply(state, "update", upd1, 2000L);

    var upd2 = new JsonObject();
    upd2.addProperty("step_id", "s2");
    upd2.addProperty("status", "in_progress");
    upd2.addProperty("note", "差 12 个台阶");
    GoalBoard.apply(state, "update", upd2, 3000L);

    var cp = new JsonObject();
    cp.addProperty("checkpoint_key", "anchor");
    cp.addProperty("checkpoint_value", "-120,64,88");
    GoalBoard.apply(state, "checkpoint", cp, 4000L);

    var snap = GoalBoard.toSnapshot(state);
    assertTrue(snap.get("persistent").getAsBoolean());
    assertEquals("建起全自动原木厂", snap.get("goal").getAsString());
    assertEquals("平整施工地基", snap.get("current").getAsString());
    assertEquals("运行 blueprint_build", snap.get("next").getAsString());
    assertEquals(1, snap.get("steps_done").getAsInt());
    assertEquals(3, snap.get("steps_total").getAsInt());
    assertEquals("-120,64,88", snap.getAsJsonObject("checkpoints").get("anchor").getAsString());

    GoalBoard.apply(state, "clear", new JsonObject(), 5000L);
    assertEquals(0, GoalBoard.toSnapshot(state).size());
  }
}
