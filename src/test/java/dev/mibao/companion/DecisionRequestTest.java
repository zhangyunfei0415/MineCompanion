package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DecisionRequestTest {
  @Test
  void delayedOldReplyCannotConsumeTheNewRequestAfterInvalidation() {
    var request = new DecisionRequest();
    var submitted = new AtomicInteger();
    long old = request.begin();
    var delayed = new CompletableFuture<String>();
    delayed.thenAccept(result -> {
      if (request.finish(old)) submitted.incrementAndGet();
    });

    request.invalidate();
    assertFalse(request.pending());
    long current = request.begin();
    delayed.complete("old response");
    assertTrue(request.pending());
    assertEquals(0, submitted.get());
    assertTrue(request.finish(current));
    assertFalse(request.pending());
    assertFalse(request.finish(current));
  }

  @Test
  void onlyTheOwnerOrExplicitPlayerPermissionCanResume() {
    assertFalse(PauseControl.mayResume(PauseControl.Source.HUMAN, false));
    assertTrue(PauseControl.mayResume(PauseControl.Source.HUMAN, true));
    assertTrue(PauseControl.mayResume(PauseControl.Source.HARNESS, false));
    assertFalse(PauseControl.mayResume(PauseControl.Source.SYSTEM, true));
    assertFalse(PauseControl.mayResume(PauseControl.Source.DEATH_RECOVERY, true));
  }

  @Test
  void resumeReceiptMatchesObservedControlState() {
    var state = new JsonObject();
    state.addProperty("paused_by", "none");
    state.addProperty("resume_requirement", "none");
    var resumed = PauseControl.resumeReceipt(state, true);
    assertEquals("verified", resumed.get("status").getAsString());
    assertEquals("NONE", resumed.get("reason_code").getAsString());
    assertFalse(Rules.actionFailed(resumed.toString()));
    assertFalse(PauseControl.resumeReceipt(state, false).get("changed").getAsBoolean());

    state.addProperty("paused_by", "human");
    state.addProperty("resume_requirement", "game_continue_or_allow_harness_resume");
    state.addProperty("resume_hint", "F8继续");
    var refused = PauseControl.resumeReceipt(state, false);
    assertEquals("RESUME_REQUIRES_PLAYER", refused.get("reason_code").getAsString());
    assertTrue(Rules.actionFailed(refused.toString()));
  }
}
