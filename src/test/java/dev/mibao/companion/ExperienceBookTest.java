package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

class ExperienceBookTest {
  @Test
  void oldExperienceCanLoadAndEnvironmentCountsRemainSeparate() {
    var book =
        new Gson()
            .fromJson(
                "{\"schema\":1,\"outcomes\":{\"preset:mine@4\":{\"verified\":1}},\"interfaces\":{}}",
                Learning.Book.class);
    Learning.validate(book);
    var o = book.outcomes.get("preset:mine@4");
    assertEquals(1, o.verified);
    assertTrue(o.contexts.isEmpty());
    var c = new Learning.ContextOutcome();
    c.verified = 2;
    o.contexts.put("a".repeat(64), c);
    Learning.validate(book);
    assertEquals(1, o.verified);
    assertEquals(2, o.contexts.get("a".repeat(64)).verified);
    c.failed = -1;
    assertThrows(IllegalArgumentException.class, () -> Learning.validate(book));
  }

  @Test
  void portableExperienceHasNoMapBindingsAndKeepsVerificationSeparate() {
    var b = new Learning.Book();
    var o = new Learning.Outcome();
    o.stepsCompleted = 2;
    b.outcomes.put("procedure:demo@v1", o);
    Learning.validate(b);
    String json = new Gson().toJson(b);
    var reload = new Gson().fromJson(json, Learning.Book.class);
    assertEquals(0, reload.outcomes.get("procedure:demo@v1").verified);
    assertEquals(2, reload.outcomes.get("procedure:demo@v1").stepsCompleted);
    for (String forbidden : new String[] {"apiKey", "places", "originScope", "inventory", "world"})
      assertFalse(json.contains(forbidden));
  }

  @Test
  void malformedOrOversizedExperienceCannotReplaceExistingFile() {
    var b = new Learning.Book();
    var o = new Learning.Outcome();
    o.verified = -1;
    b.outcomes.put("bad", o);
    assertThrows(IllegalArgumentException.class, () -> Learning.validate(b));
    b.outcomes.clear();
    for (int i = 0; i < 513; i++) b.outcomes.put("p" + i, new Learning.Outcome());
    assertThrows(IllegalArgumentException.class, () -> Learning.validate(b));
  }

  @Test
  void sharedInterfaceNotesExcludeUnverifiedWorldProposals() {
    var b = new Learning.Book();
    var note = new Learning.InterfaceNote();
    b.interfaces.put("a".repeat(64), note);
    Learning.validate(b);
    assertEquals("", note.confirmed);
    assertFalse(new Gson().toJson(b).contains("proposal"));
    note.confirmed = null;
    Learning.validate(b);
    assertEquals("", note.confirmed);
    b.interfaces.put("old coordinates", note);
    assertThrows(IllegalArgumentException.class, () -> Learning.validate(b));
  }
}
