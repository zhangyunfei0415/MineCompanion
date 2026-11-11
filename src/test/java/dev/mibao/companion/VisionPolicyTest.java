package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import org.junit.jupiter.api.Test;

class VisionPolicyTest {
  @Test
  void legacyMigrationKeepsOffAndNewModes() {
    assertEquals("OFF", VisionPolicy.mode("", false));
    assertEquals("AUTO", VisionPolicy.mode("", true));
    assertEquals("DEMO", VisionPolicy.mode("DEMO", true));
  }

  @Test
  void offDemoKnownLayoutAndNoReasonCannotUpload() {
    for (String mode : new String[] {"OFF", "DEMO"})
      assertFalse(VisionPolicy.allowed(mode, "execution_failed", false, 0, 100, 0));
    assertFalse(VisionPolicy.allowed("AUTO", "", false, 0, 100, 0));
    assertFalse(VisionPolicy.allowed("AUTO", "unknown_menu", true, 0, 100, 0));
    assertTrue(VisionPolicy.allowed("AUTO", "target_ambiguous", true, 0, 100, 0));
  }

  @Test
  void boundsFrequencyAndPerTaskBudget() {
    assertFalse(VisionPolicy.allowed("AUTO", "execution_failed", false, 2, 100000, 0));
    assertFalse(VisionPolicy.allowed("AUTO", "unknown_menu", false, 1, 20000, 10000));
    assertTrue(VisionPolicy.allowed("AUTO", "unknown_menu", false, 1, 25000, 10000));
  }

  @Test
  void imageRejectionOnlyNotQuotaOrAuth() {
    assertTrue(VisionPolicy.unsupported(400, "This model does not support image input"));
    assertTrue(VisionPolicy.unsupported(422, "Invalid image_url: unsupported"));
    assertFalse(VisionPolicy.unsupported(401, "invalid image authentication"));
    assertFalse(VisionPolicy.unsupported(429, "image quota exceeded"));
    assertFalse(VisionPolicy.unsupported(400, "invalid tool schema"));
  }

  @Test
  void fallbackPreservesTextAndToolsWithoutMutatingOriginal() {
    var request =
        JsonParser.parseString(
                "{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"real"
                    + " inventory"
                    + " 32\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,FAKE\"}}]}],\"tools\":[{\"type\":\"function\"}]}")
            .getAsJsonObject();
    var fallback = VisionPolicy.textOnly(request);
    assertTrue(fallback.toString().contains("real inventory 32"));
    assertFalse(fallback.toString().contains("base64"));
    assertEquals(request.get("tools"), fallback.get("tools"));
    assertTrue(request.toString().contains("base64"));
  }
}
