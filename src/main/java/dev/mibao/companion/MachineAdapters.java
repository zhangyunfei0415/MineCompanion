package dev.mibao.companion;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

public final class MachineAdapters {
  public static JsonObject describe(String query) {
    var out = new JsonObject();
    var entries = new JsonArray();
    var errors = new JsonArray();
    for (String family : List.of("ae2", "refinedstorage")) {
      if (!query.isBlank() && !query.contains(family) && !family.contains(query)) continue;
      var row = new JsonObject();
      row.addProperty("id", family + "_terminal");
      row.addProperty("kind", "builtin_client_adapter");
      row.addProperty("availability", "current version/menu must be verified");
      row.addProperty(
          "tools",
          family.equals("ae2")
              ? "ae_inventory/ae_fetch/ae_deposit/ae_request_craft"
              : "rs_inventory/rs_fetch/rs_deposit");
      entries.add(row);
    }
    Path folder =
        Store.config.libraryFolder.isBlank()
            ? Store.ROOT.resolve("machine-adapters")
            : Path.of(Store.config.libraryFolder).resolve("machine-adapters");
    if (Files.isDirectory(folder))
      try (var files = Files.list(folder)) {
        for (var path :
            files.filter(p -> p.getFileName().toString().endsWith(".json")).limit(32).toList())
          try {
            if (Files.size(path) > 65536) throw new IllegalArgumentException("描述超过64KiB");
            var d =
                AdapterDescriptor.parse(
                    JsonParser.parseString(Files.readString(path)).getAsJsonObject());
            if (!query.isBlank() && !d.matches(query) && !d.id().contains(query)) continue;
            var row = Store.GSON.toJsonTree(d).getAsJsonObject();
            row.addProperty("kind", "declarative_skill_reference");
            row.addProperty("verified", false);
            row.addProperty(
                "execution",
                "inspect current GUI then explicitly run_library_skill; no automatic execution");
            entries.add(row);
          } catch (Exception e) {
            errors.add(path.getFileName() + ": descriptor rejected");
          }
      } catch (Exception e) {
        errors.add("adapter directory not readable");
      }
    out.add("adapters", entries);
    out.add("errors", errors);
    out.addProperty("arbitrary_code", false);
    out.addProperty("guarantees_machine_support", false);
    return out;
  }

  private MachineAdapters() {}
}
