package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;

/**
 * Schema v2 unified portable mod memory bundle: Playbook + UiProfiles + AdapterDescriptors +
 * SkillPackages (schema=1) + nested Track A -> Track B (AE2 delegation) progression metadata.
 */
public final class SkillBundle {
  public record Compatibility(
      String minecraft, Map<String, String> mods, List<String> optionalMods) {}

  public record Playbook(
      String overview,
      List<String> prerequisites,
      String manualWorkflow,
      String ae2Delegation,
      List<String> pitfalls,
      List<String> citations) {}

  public record TrackBPromotion(
      boolean enabled,
      String kind,
      List<String> requiredMods,
      List<String> preconditions,
      List<String> preSetupControls,
      List<String> reuseSkills,
      List<JsonObject> patterns,
      List<String> fallbackToTrackAOn) {}

  public record TrackA(
      String summary,
      List<String> setupSkills,
      List<String> manualSkills,
      TrackBPromotion trackBPromotion) {}

  public final String bundleId;
  public final String name;
  public final String version;
  public final String author;
  public final String license;
  public final String description;
  public final List<String> tags;
  public final Compatibility compatibility;
  public final Playbook playbook;
  public final TrackA trackA;
  public final List<UiProfile> uiProfiles;
  public final List<AdapterDescriptor> adapters;
  public final List<SkillPackage> skills;
  public final JsonObject derivedFrom;
  public final String expectedSha256;
  public final String contentSha256;
  public final String exportedByBuild;
  public final JsonObject raw;

  public SkillBundle(JsonObject input) {
    if (input == null) throw new IllegalArgumentException("记忆包不能为空");
    if (input.toString().length() > 262_144) {
      throw new IllegalArgumentException("记忆包过大（上限 256 KiB）");
    }
    if (!input.has("schema") || input.get("schema").getAsInt() != 2) {
      throw new IllegalArgumentException("ModSkillBundle schema 须为 2");
    }
    BundleSanitizer.assertClean(input);
    this.raw = input.deepCopy();

    this.bundleId = str(input, "bundle_id", "").trim();
    if (!bundleId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || bundleId.length() > 100) {
      throw new IllegalArgumentException("bundle_id 须为命名空间格式且最长 100：" + bundleId);
    }
    this.name = Rules.clean(str(input, "name", bundleId), 100);
    this.version = Rules.clean(str(input, "version", "1.0.0"), 40);
    this.author = Rules.clean(str(input, "author", "community"), 64);
    this.license = Rules.clean(str(input, "license", "MIT"), 64);
    this.description = Rules.clean(str(input, "description", ""), 600);
    this.tags = stringList(input, "tags", 16, 40);
    this.compatibility = parseCompatibility(input.has("compatibility") && input.get("compatibility").isJsonObject()
        ? input.getAsJsonObject("compatibility")
        : new JsonObject());
    this.playbook = parsePlaybook(input.has("playbook") && input.get("playbook").isJsonObject()
        ? input.getAsJsonObject("playbook")
        : new JsonObject());

    List<UiProfile> parsedProfiles = new ArrayList<>();
    if (input.has("ui_profiles") && input.get("ui_profiles").isJsonArray()) {
      JsonArray arr = input.getAsJsonArray("ui_profiles");
      if (arr.size() > 16) throw new IllegalArgumentException("ui_profiles 最多 16 项");
      for (JsonElement el : arr) parsedProfiles.add(new UiProfile(el.getAsJsonObject()));
    }
    this.uiProfiles = List.copyOf(parsedProfiles);

    List<AdapterDescriptor> parsedAdapters = new ArrayList<>();
    if (input.has("adapters") && input.get("adapters").isJsonArray()) {
      JsonArray arr = input.getAsJsonArray("adapters");
      if (arr.size() > 32) throw new IllegalArgumentException("adapters 最多 32 项");
      for (JsonElement el : arr) parsedAdapters.add(AdapterDescriptor.parse(el.getAsJsonObject()));
    }
    this.adapters = List.copyOf(parsedAdapters);

    List<SkillPackage> parsedSkills = new ArrayList<>();
    if (input.has("skills") && input.get("skills").isJsonArray()) {
      JsonArray arr = input.getAsJsonArray("skills");
      if (arr.size() > 16) throw new IllegalArgumentException("skills 最多 16 项");
      for (JsonElement el : arr) parsedSkills.add(new SkillPackage(el.getAsJsonObject()));
    }
    this.skills = List.copyOf(parsedSkills);

    this.trackA = parseTrackA(input);
    this.derivedFrom =
        input.has("derived_from") && input.get("derived_from").isJsonObject()
            ? input.getAsJsonObject("derived_from").deepCopy()
            : null;

    this.contentSha256 = CanonicalJson.bundleContentSha256(input);
    if (input.has("integrity") && input.get("integrity").isJsonObject()) {
      JsonObject integ = input.getAsJsonObject("integrity");
      this.expectedSha256 = str(integ, "content_sha256", "").trim().toLowerCase(Locale.ROOT);
      this.exportedByBuild = Rules.clean(str(integ, "exported_by_build", ""), 80);
      if (!expectedSha256.isEmpty() && !expectedSha256.equals(contentSha256)) {
        throw new IllegalArgumentException(
            "记忆包完整性校验失败(content_sha256不匹配): expected="
                + expectedSha256
                + " actual="
                + contentSha256);
      }
    } else {
      this.expectedSha256 = "";
      this.exportedByBuild = "";
    }
  }

  private static Compatibility parseCompatibility(JsonObject c) {
    String mc = Rules.clean(str(c, "minecraft", "1.20.1"), 32);
    Map<String, String> mods = new LinkedHashMap<>();
    if (c.has("mods") && c.get("mods").isJsonObject()) {
      for (var e : c.getAsJsonObject("mods").entrySet()) {
        if (mods.size() >= 32) break;
        if (e.getValue().isJsonPrimitive()) {
          mods.put(e.getKey(), Rules.clean(e.getValue().getAsString(), 64));
        }
      }
    }
    return new Compatibility(mc, Map.copyOf(mods), stringList(c, "optional_mods", 16, 64));
  }

  private static Playbook parsePlaybook(JsonObject p) {
    String overview = Rules.clean(str(p, "overview", ""), 4000);
    List<String> prereqs = stringList(p, "prerequisites", 24, 500);
    String manual = Rules.clean(str(p, "manual_workflow", ""), 4000);
    String ae2 = Rules.clean(str(p, "ae2_delegation", ""), 4000);
    List<String> pitfalls = stringList(p, "pitfalls", 24, 500);
    List<String> citations = stringList(p, "citations", 16, 300);
    int totalLen =
        overview.length()
            + manual.length()
            + ae2.length()
            + prereqs.stream().mapToInt(String::length).sum()
            + pitfalls.stream().mapToInt(String::length).sum();
    if (totalLen > 16000) throw new IllegalArgumentException("playbook 文本总长度超出 16000 字符上限");
    return new Playbook(overview, prereqs, manual, ae2, pitfalls, citations);
  }

  private TrackA parseTrackA(JsonObject input) {
    JsonObject ta =
        input.has("track_a") && input.get("track_a").isJsonObject()
            ? input.getAsJsonObject("track_a")
            : new JsonObject();
    String summary = Rules.clean(str(ta, "summary", playbook.manualWorkflow()), 500);
    List<String> setup = stringList(ta, "setup_skills", 16, 100);
    List<String> manual = stringList(ta, "manual_skills", 16, 100);
    if (manual.isEmpty() && !skills.isEmpty()) {
      List<String> defaults = new ArrayList<>();
      for (SkillPackage s : skills) defaults.add(s.id);
      manual = List.copyOf(defaults);
    }

    JsonArray topPatterns =
        input.has("ae2_patterns") && input.get("ae2_patterns").isJsonArray()
            ? input.getAsJsonArray("ae2_patterns")
            : new JsonArray();
    JsonObject tbObj =
        ta.has("track_b_promotion") && ta.get("track_b_promotion").isJsonObject()
            ? ta.getAsJsonObject("track_b_promotion")
            : null;
    TrackBPromotion promo;
    if (tbObj == null && topPatterns.isEmpty() && playbook.ae2Delegation().isBlank()) {
      promo =
          new TrackBPromotion(
              false, "ae2_delegation", List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    } else {
      JsonObject src = tbObj != null ? tbObj : new JsonObject();
      boolean enabled = !src.has("enabled") || src.get("enabled").getAsBoolean();
      String kind = Rules.clean(str(src, "kind", "ae2_delegation"), 48);
      List<String> reqMods = stringList(src, "required_mods", 16, 64);
      if (reqMods.isEmpty() && (!topPatterns.isEmpty() || !playbook.ae2Delegation().isBlank())) {
        reqMods = List.of("ae2");
      }
      List<String> preconditions = stringList(src, "preconditions", 16, 80);
      List<String> preCtrls = stringList(src, "pre_setup_controls", 16, 64);
      List<String> reuseSkills = stringList(src, "reuse_skills", 16, 100);
      List<JsonObject> patterns = new ArrayList<>();
      JsonArray patArr =
          src.has("patterns") && src.get("patterns").isJsonArray()
              ? src.getAsJsonArray("patterns")
              : topPatterns;
      if (patArr.size() > 32) throw new IllegalArgumentException("ae2 patterns 最多 32 项");
      for (JsonElement el : patArr) {
        if (el.isJsonObject()) patterns.add(el.getAsJsonObject().deepCopy());
      }
      List<String> fallbackOn = stringList(src, "fallback_to_track_a_on", 16, 80);
      promo =
          new TrackBPromotion(
              enabled,
              kind,
              reqMods,
              preconditions,
              preCtrls,
              reuseSkills,
              List.copyOf(patterns),
              fallbackOn);
    }
    return new TrackA(summary, setup, manual, promo);
  }

  public boolean hasTrackB() {
    return trackA.trackBPromotion().enabled()
        && (!trackA.trackBPromotion().requiredMods().isEmpty()
            || !trackA.trackBPromotion().patterns().isEmpty()
            || !playbook.ae2Delegation().isBlank());
  }

  public boolean ownsSkill(String skillId) {
    if (skillId == null) return false;
    for (SkillPackage s : skills) {
      if (s.id.equals(skillId)) return true;
    }
    return trackA.setupSkills().contains(skillId)
        || trackA.manualSkills().contains(skillId)
        || trackA.trackBPromotion().reuseSkills().contains(skillId);
  }

  public boolean isTrackBSkill(String skillId) {
    return skillId != null && trackA.trackBPromotion().reuseSkills().contains(skillId);
  }

  /** Apply a local BundleAdaptation overlay to produce the localized effective SkillBundle. */
  public SkillBundle applyAdaptation(BundleAdaptation adapt) {
    if (adapt == null || !adapt.hasAdaptation()) return this;
    JsonObject merged = buildAdaptedBody(adapt, false);
    merged.remove("integrity");
    return new SkillBundle(merged);
  }

  public JsonObject toExportJson(
      BundleAdaptation adapt, boolean includeAdaptation, String buildId) {
    JsonObject body =
        includeAdaptation && adapt != null && adapt.hasAdaptation()
            ? buildAdaptedBody(adapt, true)
            : raw.deepCopy();
    body.remove("integrity");
    BundleSanitizer.assertClean(body);
    String sha = CanonicalJson.bundleContentSha256(body);
    JsonObject integrity = new JsonObject();
    integrity.addProperty("content_sha256", sha);
    integrity.addProperty("exported_by_build", Rules.clean(buildId, 80));
    body.add("integrity", integrity);
    return body;
  }

  private JsonObject buildAdaptedBody(BundleAdaptation adapt, boolean includeDerivedFrom) {
    JsonObject merged = raw.deepCopy();
    if (includeDerivedFrom && !contentSha256.isBlank()) {
      JsonObject derived = new JsonObject();
      derived.addProperty("bundle_id", bundleId);
      derived.addProperty("base_sha256", contentSha256);
      merged.add("derived_from", derived);
    }

    if (!adapt.uiProfilePatches.isEmpty()) {
      Map<String, UiProfile> byId = new LinkedHashMap<>();
      for (UiProfile p : uiProfiles) {
        JsonObject patch = adapt.uiProfilePatches.get(p.id);
        byId.put(p.id, patch != null ? p.withPatch(patch) : p);
      }
      for (var entry : adapt.uiProfilePatches.entrySet()) {
        if (!byId.containsKey(entry.getKey())) {
          JsonObject full = entry.getValue().deepCopy();
          if (!full.has("id")) full.addProperty("id", entry.getKey());
          byId.put(entry.getKey(), new UiProfile(full));
        }
      }
      JsonArray arr = new JsonArray();
      byId.values().forEach(p -> arr.add(p.raw.deepCopy()));
      merged.add("ui_profiles", arr);
    }

    if (!adapt.localNotes.isEmpty() || !adapt.localPitfalls.isEmpty()) {
      JsonObject pb =
          merged.has("playbook") && merged.get("playbook").isJsonObject()
              ? merged.getAsJsonObject("playbook")
              : new JsonObject();
      if (!adapt.localNotes.isEmpty()) {
        String existing = str(pb, "manual_workflow", "");
        String joinedNotes = String.join("\n", adapt.localNotes);
        pb.addProperty(
            "manual_workflow",
            existing.isBlank() ? joinedNotes : existing + "\n[本地适配备注] " + joinedNotes);
      }
      if (!adapt.localPitfalls.isEmpty()) {
        JsonArray pitArr =
            pb.has("pitfalls") && pb.get("pitfalls").isJsonArray()
                ? pb.getAsJsonArray("pitfalls")
                : new JsonArray();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonElement el : pitArr) seen.add(el.getAsString());
        for (String pit : adapt.localPitfalls) {
          if (seen.add(pit)) pitArr.add(pit);
        }
        pb.add("pitfalls", pitArr);
      }
      merged.add("playbook", pb);
    }

    if (!adapt.skillParamDefaults.isEmpty() || !adapt.skillOverrides.isEmpty()) {
      Map<String, JsonObject> skillsById = new LinkedHashMap<>();
      JsonArray srcSkills =
          merged.has("skills") && merged.get("skills").isJsonArray()
              ? merged.getAsJsonArray("skills")
              : new JsonArray();
      for (JsonElement el : srcSkills) {
        JsonObject sk = el.getAsJsonObject().deepCopy();
        skillsById.put(str(sk, "id", ""), sk);
      }
      for (var entry : adapt.skillOverrides.entrySet()) {
        skillsById.put(entry.getKey(), entry.getValue().deepCopy());
      }
      for (var entry : adapt.skillParamDefaults.entrySet()) {
        JsonObject sk = skillsById.get(entry.getKey());
        if (sk == null) continue;
        JsonObject params =
            sk.has("parameters") && sk.get("parameters").isJsonObject()
                ? sk.getAsJsonObject("parameters")
                : new JsonObject();
        for (var p : entry.getValue().entrySet()) {
          if (params.has(p.getKey()) && params.get(p.getKey()).isJsonObject()) {
            params.getAsJsonObject(p.getKey()).add("default", p.getValue().deepCopy());
          }
        }
        sk.add("parameters", params);
      }
      JsonArray updatedSkills = new JsonArray();
      skillsById.values().forEach(updatedSkills::add);
      merged.add("skills", updatedSkills);
    }
    return merged;
  }

  private static String str(JsonObject o, String key, String fallback) {
    return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback;
  }

  private static List<String> stringList(JsonObject o, String key, int maxItems, int maxLen) {
    if (o == null || !o.has(key) || !o.get(key).isJsonArray()) return List.of();
    JsonArray arr = o.getAsJsonArray(key);
    List<String> out = new ArrayList<>(Math.min(arr.size(), maxItems));
    for (JsonElement el : arr) {
      if (out.size() >= maxItems) break;
      if (el.isJsonPrimitive()) {
        String s = Rules.clean(el.getAsString(), maxLen).trim();
        if (!s.isEmpty()) out.add(s);
      }
    }
    return List.copyOf(out);
  }
}
