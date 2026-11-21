package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class VersionRangeTest {
  @Test
  void wildcardAndExactMatching() {
    assertTrue(VersionRange.matches("*", "10.4.8").matched());
    assertFalse(VersionRange.matches("*", "10.4.8").advisory());
    assertTrue(VersionRange.matches("10.4.8", "10.4.8").matched());
    assertFalse(VersionRange.matches("10.4.8", "10.4.9").matched());
  }

  @Test
  void dotWildcardsAndCompoundComparators() {
    assertTrue(VersionRange.matches("10.4.*", "10.4.12").matched());
    assertFalse(VersionRange.matches("10.4.*", "10.5.0").matched());

    assertTrue(VersionRange.matches(">=10.4.0 <11.0.0", "10.4.8").matched());
    assertTrue(VersionRange.matches(">=10.4.0, <11.0.0", "10.99.1").matched());
    assertFalse(VersionRange.matches(">=10.4.0 <11.0.0", "10.3.9").matched());
    assertFalse(VersionRange.matches(">=10.4.0 <11.0.0", "11.0.0").matched());
    assertTrue(VersionRange.matches(">1.0.0 <=2.0.0", "2.0.0").matched());
  }

  @Test
  void caretTildeAndNonNumericAdvisoryFallback() {
    assertTrue(VersionRange.matches("^10.4.2", "10.7.0").matched());
    assertFalse(VersionRange.matches("^10.4.2", "11.0.0").matched());
    assertFalse(VersionRange.matches("^10.4.2", "10.4.1").matched());

    assertTrue(VersionRange.matches("^0.4.2", "0.4.9").matched());
    assertFalse(VersionRange.matches("^0.4.2", "0.5.0").matched());

    assertTrue(VersionRange.matches("~1.2.3", "1.2.9").matched());
    assertFalse(VersionRange.matches("~1.2.3", "1.3.0").matched());

    var nonNumeric = VersionRange.matches(">=1.0.0", "1.20.1-build.42-SNAPSHOT");
    assertTrue(nonNumeric.matched());

    var oddVersion = VersionRange.matches(">=1.0.0", "custom-nightly");
    assertTrue(oddVersion.matched());
    assertTrue(oddVersion.advisory());
  }
}
