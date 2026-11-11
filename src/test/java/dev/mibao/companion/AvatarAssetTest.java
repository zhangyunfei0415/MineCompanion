package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class AvatarAssetTest {
  @Test
  void dragonAtlasHasEightNonemptyTransparentStatesAndRecordedDigest() throws Exception {
    var cls = getClass();
    try (var pixels =
            cls.getResourceAsStream(
                "/assets/logistics_companion/textures/gui/dragon_states_client04.png");
        var layout =
            cls.getResourceAsStream(
                "/assets/logistics_companion/textures/gui/dragon_states_client04.json")) {
      assertNotNull(pixels);
      assertNotNull(layout);
      byte[] bytes = pixels.readAllBytes();
      var image = ImageIO.read(new ByteArrayInputStream(bytes));
      var meta =
          JsonParser.parseReader(new InputStreamReader(layout, StandardCharsets.UTF_8))
              .getAsJsonObject();
      assertEquals(
          meta.get("sha256").getAsString(),
          java.util.HexFormat.of()
              .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
      assertEquals(meta.get("width").getAsInt(), image.getWidth());
      assertEquals(meta.get("height").getAsInt(), image.getHeight());
      assertTrue(image.getColorModel().hasAlpha());
      for (int row = 0; row < 2; row++)
        for (int col = 0; col < 4; col++) {
          int visible = 0, transparent = 0;
          for (int y = row * image.getHeight() / 2; y < (row + 1) * image.getHeight() / 2; y += 4)
            for (int x = col * image.getWidth() / 4; x < (col + 1) * image.getWidth() / 4; x += 4) {
              int a = image.getRGB(x, y) >>> 24;
              if (a > 128) visible++;
              if (a == 0) transparent++;
            }
          assertTrue(visible > 100);
          assertTrue(transparent > 100);
        }
    }
  }

  @Test
  void shippedAtlasHasTransparencyAnd32ReadableStateRegions() throws Exception {
    var cls = getClass();
    try (var pixels =
            cls.getResourceAsStream("/assets/logistics_companion/textures/gui/watch_states.png");
        var layout =
            cls.getResourceAsStream("/assets/logistics_companion/textures/gui/watch_states.json")) {
      assertNotNull(pixels);
      assertNotNull(layout);
      var image = ImageIO.read(pixels);
      var meta =
          JsonParser.parseReader(new InputStreamReader(layout, StandardCharsets.UTF_8))
              .getAsJsonObject();
      assertEquals(meta.get("width").getAsInt(), image.getWidth());
      assertEquals(meta.get("height").getAsInt(), image.getHeight());
      assertTrue(image.getColorModel().hasAlpha());
      var x = meta.getAsJsonArray("columns");
      var rows = meta.getAsJsonArray("rows");
      assertEquals(9, x.size());
      assertEquals(4, rows.size());
      assertEquals(8, meta.getAsJsonArray("states").size());
      for (var row : rows) {
        int y = row.getAsJsonArray().get(0).getAsInt(), h = row.getAsJsonArray().get(1).getAsInt();
        assertTrue(y >= 0 && y + h <= image.getHeight());
        for (int i = 0; i < 8; i++) {
          int left = x.get(i).getAsInt(), right = x.get(i + 1).getAsInt(), visible = 0;
          assertTrue(left >= 0 && right > left && right <= image.getWidth());
          for (int yy = y; yy < y + h; yy += 3)
            for (int xx = left; xx < right; xx += 3)
              if ((image.getRGB(xx, yy) >>> 24) > 128) visible++;
          assertTrue(visible > 100, "empty sprite region");
        }
      }
    }
  }
}
