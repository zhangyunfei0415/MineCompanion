package dev.mibao.companion;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Explicit shared directory, read-only definitions, world-local verified experience. */
public final class SkillLibrary {
  private static final Set<String> PRODUCTION_PROOF_TOOLS = Set.of(
      "gui_take_role", "take_vanilla_output", "craft_item", "smelt_item", "smelt_batch",
      "brew_batch", "enchant_item", "anvil_item");
  public static final Map<String, SkillPackage> entries = new LinkedHashMap<>();
  public static final Map<String, String> bundleOwners = new LinkedHashMap<>();
  public static final List<String> errors = new ArrayList<>();

  public static Path root() {
    return Store.config.libraryFolder.isBlank()
        ? Store.ROOT.resolve("library")
        : Path.of(Store.config.libraryFolder).toAbsolutePath().normalize();
  }

  public static void refresh() {
    entries.clear();
    bundleOwners.clear();
    errors.clear();
    try {
      Path folder = root().resolve("skills");
      Files.createDirectories(folder);
      Files.createDirectories(root().resolve("knowledge"));
      for (String name :
          List.of(
              "transport", "craft", "smelt", "farm", "gather", "illuminate", "fish", "mining",
              "survival-start", "survival-mine-return", "survival-camp")) {
        Path target = folder.resolve("builtin-" + name + ".json");
        if (!Files.exists(target))
          try (var in =
              SkillLibrary.class.getResourceAsStream("/skill-library/" + name + ".json")) {
            if (in != null)
              try {
                Files.copy(in, target);
              } catch (FileAlreadyExistsException ignored) {
              }
          }
      }
      try (var stream = Files.list(folder)) {
        for (Path p :
            stream
                .filter(f -> f.getFileName().toString().endsWith(".json"))
                .sorted()
                .limit(128)
                .toList()) {
          try {
            if (Files.size(p) > 100_000) throw new IllegalArgumentException("超过100KB");
            var pack =
                new SkillPackage(
                    JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8))
                        .getAsJsonObject());
            if (entries.containsKey(pack.id))
              throw new IllegalArgumentException("重复技能ID " + pack.id);
            entries.put(pack.id, pack);
          } catch (Exception e) {
            errors.add(p.getFileName() + "：" + Rules.clean(e.getMessage(), 180));
          }
        }
      }
      BundleLibrary.refresh();
      for (var eb : BundleLibrary.effectiveEntries.values()) {
        String bundleId = eb.effective().bundleId;
        if (Store.config.disabledPackages.contains(bundleId)) continue;
        for (SkillPackage sp : eb.effective().skills) {
          if (entries.containsKey(sp.id)) {
            errors.add(bundleId + " 技能ID冲突（已忽略）：" + sp.id);
            continue;
          }
          entries.put(sp.id, sp);
          bundleOwners.put(sp.id, bundleId);
        }
      }
    } catch (Exception e) {
      errors.add("技能目录不可用：" + Rules.clean(e.getMessage(), 180));
    }
  }

  public static JsonObject list(String query) {
    var o = new JsonObject();
    var rows = new JsonArray();
    for (var p : entries.values()) {
      if (!p.name.contains(query) && !p.id.contains(query)) continue;
      var r = p.definition.deepCopy();
      r.remove("steps");
      r.addProperty("enabled", !Store.config.disabledPackages.contains(p.id));
      JsonArray found;
      try {
        found = issues(p);
      } catch (RuntimeException e) {
        found = new JsonArray();
        found.add("技能元数据格式错误：" + Rules.clean(e.getMessage(), 120));
      }
      r.add("issues", found);
      var check = Store.data.skillChecks.get(key(p));
      r.addProperty("verification", check == null ? "当前环境待验证" : check.status);
      r.addProperty("successful_runs", check == null ? 0 : check.successes);
      rows.add(r);
    }
    o.add("skills", rows);
    o.add("errors", Store.GSON.toJsonTree(errors));
    o.addProperty("root", root().toString());
    o.addProperty("schema", 1);
    o.addProperty("rule", "启用不代表执行或已验证；知识不是指令；地图与库存不进入共享技能");
    return o;
  }

  public static JsonArray issues(SkillPackage p) {
    var a = new JsonArray();
    var mods = new HashSet<String>();
    net.minecraftforge.fml.ModList.get().getMods().forEach(m -> mods.add(m.getModId()));
    for (String tool : p.requires) {
      String dep = CapabilityCatalog.dependency(tool);
      if (!dep.isBlank() && !mods.contains(dep)) a.add("缺少组件 " + dep);
    }
    if (p.definition.has("minecraft")
        && !SkillPackage.string(p.definition, "minecraft", "")
            .equals(net.minecraft.SharedConstants.getCurrentVersion().getName()))
      a.add("Minecraft版本不匹配");
    if (p.definition.has("mods") && !p.definition.get("mods").isJsonObject())
      a.add("技能元数据格式错误：mods 须为对象");
    else if (p.definition.has("mods"))
      for (var e : p.definition.getAsJsonObject("mods").entrySet()) {
        if (!e.getValue().isJsonPrimitive()) {
          a.add("技能元数据格式错误：mods." + e.getKey());
          continue;
        }
        var mod =
            net.minecraftforge.fml.ModList.get().getMods().stream()
                .filter(m -> m.getModId().equals(e.getKey()))
                .findFirst();
        if (mod.isEmpty()) a.add("缺少模组 " + e.getKey());
        else {
          var match = VersionRange.matches(e.getValue().getAsString(), mod.get().getVersion().toString());
          if (!match.matched()) a.add("模组版本不匹配 " + e.getKey() + "（" + match.detail() + "）");
          else if (match.advisory()) a.add("模组版本需复核 " + e.getKey());
        }
      }
    return a;
  }

  private static String key(SkillPackage p) {
    return "library:"
        + p.id
        + ":"
        + EnvironmentKey.current()
        + ":"
        + EnvironmentKey.of(List.of(p.definition.toString()));
  }

  public static void run(String id, JsonObject params, Consumer<String> done) {
    var p = entries.get(id);
    if (p == null) throw new IllegalArgumentException("未找到技能，先refresh_library");
    String ownerBundle = bundleOwners.get(id);
    if (ownerBundle != null && Store.config.disabledPackages.contains(ownerBundle))
      throw new IllegalArgumentException("所属记忆包已停用，请在开放记忆中心启用：" + ownerBundle);
    if (Store.config.disabledPackages.contains(id))
      throw new IllegalArgumentException("技能已停用，请在技能库启用");
    if (!issues(p).isEmpty()) throw new IllegalArgumentException("技能依赖不满足：" + issues(p));
    var bundleRun = ownerBundle == null ? null : BundleLibrary.requireSkillRun(ownerBundle, id);
    JsonObject session = p.expand(params);
    var steps =
        SkillSteps.prepare(
            session.getAsJsonArray("steps"), false, Store.data.places.keySet(), new JsonObject());
    session.add("steps", steps);
    boolean hasProductionStep = bundleRun != null
        && java.util.stream.StreamSupport.stream(steps.spliterator(), false)
            .anyMatch(step -> PRODUCTION_PROOF_TOOLS.contains(
                step.getAsJsonObject().get("tool").getAsString()));
    var worldData = Store.data;
    String worldScope = Store.scope();
    Agent.execute(
        "work_session",
        session,
        result -> {
          if (Store.data != worldData || !worldScope.equals(Store.scope())) return;
          var check = Store.data.skillChecks.computeIfAbsent(key(p), k -> new Store.SkillCheck());
          boolean success = StepEvidence.complete("work_session", result);
          if (success) check.successes++;
          else check.failures++;
          check.status = success ? "当前世界与环境流程核验通过" : "失败或未完成，需复核";
          BundleLibrary.recordSkillRun(bundleRun,
              success && result.startsWith("成功：连续流程 "), hasProductionStep);
          Store.save();
          done.accept(result);
        },
        () -> {
          if (bundleRun == null) return;
          if (Store.config.disabledPackages.contains(ownerBundle) || Store.config.disabledPackages.contains(id)
              || !ownerBundle.equals(bundleOwners.get(id))
              || !bundleRun.equals(BundleLibrary.requireSkillRun(ownerBundle, id)))
            throw new IllegalArgumentException("[BUNDLE_SKILL_CHANGED] 记忆包已变化，请重新选择技能");
        });
  }

  public static Path export(String id) throws Exception {
    var p = entries.get(id);
    if (p == null) throw new IllegalArgumentException("未知技能");
    Path dir = Store.ROOT.resolve("exports");
    Files.createDirectories(dir);
    Path out =
        dir.resolve(
            id.replaceAll("[^a-zA-Z0-9_.-]", "_") + "-" + System.currentTimeMillis() + ".json");
    Files.writeString(
        out,
        Store.GSON.toJson(p.definition),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE_NEW);
    return out;
  }
}
