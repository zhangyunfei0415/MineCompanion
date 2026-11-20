package dev.mibao.companion;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Properties;

public final class BuildInfo {
  public static final String VERSION;
  public static final String ID;

  static {
    var values = new Properties();
    try (var input = BuildInfo.class.getResourceAsStream("/minecompanion-build.properties")) {
      if (input == null) throw new IllegalStateException("Missing MineCompanion build information");
      values.load(input);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    VERSION = values.getProperty("version");
    ID = values.getProperty("build");
  }

  private BuildInfo() {}
}
