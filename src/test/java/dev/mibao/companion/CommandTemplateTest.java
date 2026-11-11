package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CommandTemplateTest {
  @Test
  void oneDefinitionCanBindDifferentServerHomes() {
    assertEquals("home base", CommandTemplate.expand("home ${home}", Map.of("home", "base")));
    assertEquals("home island", CommandTemplate.expand("home ${home}", Map.of("home", "island")));
  }

  @Test
  void parametersAreLiteralRatherThanRegexReplacements() {
    assertEquals(
        "custom foo$1\\bar",
        CommandTemplate.expand("custom ${name}", Map.of("name", "foo$1\\bar")));
  }

  @Test
  void missingOrInvalidBindingsNeverProduceAnExecutableCommand() {
    for (Map<String, String> values :
        java.util.List.of(
            Map.<String, String>of(), Map.of("home", "x\nstop"), Map.of("home", "x".repeat(129))))
      assertThrows(
          IllegalArgumentException.class, () -> CommandTemplate.expand("home ${home}", values));
    assertThrows(
        IllegalArgumentException.class, () -> CommandTemplate.expand("home ${bad-name}", Map.of()));
  }
}
