package dev.mibao.companion;

import com.google.gson.*;
import java.util.function.Consumer;

/** Native JEI handler once, then a fresh observation; never claims encoding from a preview. */
final class JeiTransferJob extends Actions.Job {
  private final String token;
  private final boolean execute, max;
  private JsonObject result;

  JeiTransferJob(String token, boolean execute, boolean max, Consumer<String> done) {
    super(done);
    this.token = token;
    this.execute = execute;
    this.max = max;
  }

  @Override
  void tick() {
    if (result == null) {
      result = RecipeBridge.require().transfer(token, execute, max);
      if (!result.get("dispatched").getAsBoolean()) {
        finish(result.toString());
        return;
      }
    }
    if (++ticks < 12) return;
    try {
      result.add("after", GuiContext.observe());
    } catch (IllegalArgumentException e) {
      result.addProperty("after_unavailable", e.getMessage());
    }
    finish(result.toString());
  }
}
