package dev.mibao.companion;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * Local environment adaptation overlay + Track A -> Track B progression state for a downloaded or
 * built-in SkillBundle. Keeps upstream bundles immutable while letting local agents localize
 * offsets, aliases, pitfalls, parameter defaults, and promote verified Track A into reusable
 * Track B (AE2 delegation).
 */
public final class BundleAdaptation {
  public static final class TrackState {
    public boolean trackAVerified;
    public int trackARuns;
    public boolean trackBPromoted;
    public int trackBRuns;
    public Set<String> verifiedPreconditions = new LinkedHashSet<>();
    public String preferredTrack = "auto";
    public String lastVerifiedEnv = "";
    public String lastVerifiedUpstream = "";
    public String lastVerifiedContent = "";
    public String lastVerifiedSkill = "";
  }

  public int schema = 1;
  public String bundleId = "";
  public String baseSha256 = "";
  public String environmentKey = "";
  public String updatedAt = "";
  public String reason = "";
  public Map<String, JsonObject> uiProfilePatches = new LinkedHashMap<>();
  public List<String> localNotes = new ArrayList<>();
  public List<String> localPitfalls = new ArrayList<>();
  public Map<String, Map<String, JsonElement>> skillParamDefaults = new LinkedHashMap<>();
  public Map<String, JsonObject> skillOverrides = new LinkedHashMap<>();
  public TrackState trackState = new TrackState();

  public boolean envDrifted(String currentEnvKey) {
    String cur = currentEnvKey == null ? "" : currentEnvKey.trim();
    return !environmentKey.isBlank()
        && !cur.isBlank()
        && !"unknown".equals(cur)
        && !environmentKey.equals(cur);
  }

  public boolean upstreamChanged(SkillBundle bundle) {
    return bundle != null
        && !baseSha256.isBlank()
        && !bundle.contentSha256.isBlank()
        && !baseSha256.equals(bundle.contentSha256);
  }

  public boolean hasAdaptation() {
    return !uiProfilePatches.isEmpty()
        || !localNotes.isEmpty()
        || !localPitfalls.isEmpty()
        || !skillParamDefaults.isEmpty()
        || !skillOverrides.isEmpty();
  }

  public boolean trackAValid(SkillBundle upstream, SkillBundle effective, String currentEnvKey) {
    return trackState.trackAVerified && currentEnvKey != null && !currentEnvKey.isBlank()
        && !"unknown".equals(currentEnvKey)
        && currentEnvKey.equals(trackState.lastVerifiedEnv)
        && upstream.contentSha256.equals(trackState.lastVerifiedUpstream)
        && effective.contentSha256.equals(trackState.lastVerifiedContent)
        && effective.isManualSkill(trackState.lastVerifiedSkill);
  }

  void invalidateTrackEvidence() {
    trackState.trackAVerified = false;
    trackState.trackBPromoted = false;
    trackState.verifiedPreconditions.clear();
  }

  public List<String> missingTrackBPreconditions(
      SkillBundle upstream, SkillBundle bundle, Set<String> installedMods, String currentEnvKey) {
    if (bundle == null || !bundle.hasTrackB()) return List.of("bundle_has_no_track_b");
    List<String> missing = new ArrayList<>();
    if (!trackAValid(upstream, bundle, currentEnvKey)) missing.add("track_a_verified");
    var promo = bundle.trackA.trackBPromotion();
    Set<String> mods = installedMods == null ? Set.of() : installedMods;
    for (String mod : promo.requiredMods()) {
      if (!mods.contains(mod)) missing.add("mod:" + mod);
    }
    for (String pre : promo.preconditions()) {
      if ("track_a_verified".equals(pre)) continue;
      if (!trackState.verifiedPreconditions.contains(pre)) {
        missing.add("precondition:" + pre);
      }
    }
    return List.copyOf(missing);
  }

  public boolean canPromoteTrackB(
      SkillBundle upstream, SkillBundle bundle, Set<String> installedMods, String currentEnvKey) {
    return missingTrackBPreconditions(upstream, bundle, installedMods, currentEnvKey).isEmpty();
  }

  /**
   * Resolves effective runtime track: Track B is nested inside Track A and only persistently reused
   * once Track A is verified, Track B preconditions hold, and the modpack environment has not
   * drifted.
   */
  public String effectiveTrack(
      SkillBundle upstream, SkillBundle bundle, Set<String> installedMods, String currentEnvKey) {
    boolean verified = trackAValid(upstream, bundle, currentEnvKey);
    if (!verified && (trackState.trackARuns > 0 || trackState.trackAVerified || trackState.trackBPromoted
        || (envDrifted(currentEnvKey) || upstreamChanged(upstream)) && hasAdaptation())) {
      return "track_a_reverify";
    }
    if (trackState.trackBPromoted
        && !"track_a".equals(trackState.preferredTrack)
        && canPromoteTrackB(upstream, bundle, installedMods, currentEnvKey)) {
      return "track_b_reuse";
    }
    if (verified) return "track_a_verified";
    return "track_a_unverified";
  }

  public static BundleAdaptation parse(JsonObject root) {
    if (root == null) throw new IllegalArgumentException("adaptation 不能为空");
    BundleSanitizer.assertClean(root);
    if (root.has("schema") && root.get("schema").getAsInt() != 1) {
      throw new IllegalArgumentException("BundleAdaptation schema 须为 1");
    }
    BundleAdaptation a = new BundleAdaptation();
    a.bundleId = str(root, "bundle_id", "").trim();
    if (!a.bundleId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
      throw new IllegalArgumentException("adaptation bundle_id 无效：" + a.bundleId);
    }
    a.baseSha256 = str(root, "base_sha256", "");
    a.environmentKey = Rules.clean(str(root, "environment_key", ""), 80);
    a.updatedAt = Rules.clean(str(root, "updated_at", ""), 64);
    a.reason = Rules.clean(str(root, "reason", ""), 400);

    if (root.has("ui_profile_patches") && root.get("ui_profile_patches").isJsonObject()) {
      for (var e : root.getAsJsonObject("ui_profile_patches").entrySet()) {
        if (e.getValue().isJsonObject()) {
          a.uiProfilePatches.put(e.getKey(), e.getValue().getAsJsonObject().deepCopy());
        }
      }
    }
    readStringList(root, "local_notes", a.localNotes, 32, 600);
    readStringList(root, "local_pitfalls", a.localPitfalls, 32, 400);

    if (root.has("skill_param_defaults") && root.get("skill_param_defaults").isJsonObject()) {
      for (var skillEntry : root.getAsJsonObject("skill_param_defaults").entrySet()) {
        if (!skillEntry.getValue().isJsonObject()) continue;
        Map<String, JsonElement> params = new LinkedHashMap<>();
        for (var p : skillEntry.getValue().getAsJsonObject().entrySet()) {
          if (p.getValue().isJsonPrimitive()) params.put(p.getKey(), p.getValue().deepCopy());
        }
        a.skillParamDefaults.put(skillEntry.getKey(), params);
      }
    }
    if (root.has("skill_overrides") && root.get("skill_overrides").isJsonObject()) {
      for (var skillEntry : root.getAsJsonObject("skill_overrides").entrySet()) {
        if (skillEntry.getValue().isJsonObject()) {
          JsonObject def = skillEntry.getValue().getAsJsonObject().deepCopy();
          new SkillPackage(def);
          a.skillOverrides.put(skillEntry.getKey(), def);
        }
      }
    }
    if (root.has("track_state") && root.get("track_state").isJsonObject()) {
      JsonObject ts = root.getAsJsonObject("track_state");
      a.trackState.trackAVerified =
          ts.has("track_a_verified") && ts.get("track_a_verified").getAsBoolean();
      a.trackState.trackARuns = ts.has("track_a_runs") ? Math.max(0, ts.get("track_a_runs").getAsInt()) : 0;
      a.trackState.trackBPromoted =
          ts.has("track_b_promoted") && ts.get("track_b_promoted").getAsBoolean();
      a.trackState.trackBRuns = ts.has("track_b_runs") ? Math.max(0, ts.get("track_b_runs").getAsInt()) : 0;
      String pref = str(ts, "preferred_track", "auto");
      a.trackState.preferredTrack =
          Set.of("auto", "track_a", "track_b").contains(pref) ? pref : "auto";
      a.trackState.lastVerifiedEnv = Rules.clean(str(ts, "last_verified_env", ""), 80);
      a.trackState.lastVerifiedUpstream = str(ts, "last_verified_upstream", "");
      a.trackState.lastVerifiedContent = str(ts, "last_verified_content", "");
      a.trackState.lastVerifiedSkill = str(ts, "last_verified_skill", "");
      if (ts.has("verified_preconditions") && ts.get("verified_preconditions").isJsonArray()) {
        for (JsonElement el : ts.getAsJsonArray("verified_preconditions")) {
          if (el.isJsonPrimitive()) a.trackState.verifiedPreconditions.add(el.getAsString());
        }
      }
    }
    return a;
  }

  public JsonObject toJson() {
    JsonObject root = new JsonObject();
    root.addProperty("schema", 1);
    root.addProperty("bundle_id", bundleId);
    root.addProperty("base_sha256", baseSha256);
    root.addProperty("environment_key", environmentKey);
    root.addProperty("updated_at", updatedAt.isBlank() ? Instant.now().toString() : updatedAt);
    root.addProperty("reason", reason);

    JsonObject patches = new JsonObject();
    uiProfilePatches.forEach((k, v) -> patches.add(k, v.deepCopy()));
    root.add("ui_profile_patches", patches);

    JsonArray notes = new JsonArray();
    localNotes.forEach(notes::add);
    root.add("local_notes", notes);

    JsonArray pitfalls = new JsonArray();
    localPitfalls.forEach(pitfalls::add);
    root.add("local_pitfalls", pitfalls);

    JsonObject defaults = new JsonObject();
    skillParamDefaults.forEach(
        (sid, map) -> {
          JsonObject m = new JsonObject();
          map.forEach((p, val) -> m.add(p, val.deepCopy()));
          defaults.add(sid, m);
        });
    root.add("skill_param_defaults", defaults);

    JsonObject overrides = new JsonObject();
    skillOverrides.forEach((k, v) -> overrides.add(k, v.deepCopy()));
    root.add("skill_overrides", overrides);

    JsonObject ts = new JsonObject();
    ts.addProperty("track_a_verified", trackState.trackAVerified);
    ts.addProperty("track_a_runs", trackState.trackARuns);
    ts.addProperty("track_b_promoted", trackState.trackBPromoted);
    ts.addProperty("track_b_runs", trackState.trackBRuns);
    JsonArray pre = new JsonArray();
    trackState.verifiedPreconditions.forEach(pre::add);
    ts.add("verified_preconditions", pre);
    ts.addProperty("preferred_track", trackState.preferredTrack);
    ts.addProperty("last_verified_env", trackState.lastVerifiedEnv);
    ts.addProperty("last_verified_upstream", trackState.lastVerifiedUpstream);
    ts.addProperty("last_verified_content", trackState.lastVerifiedContent);
    ts.addProperty("last_verified_skill", trackState.lastVerifiedSkill);
    root.add("track_state", ts);
    return root;
  }

  public static String safeFileStem(String bundleId) {
    return bundleId.replaceAll("[^a-zA-Z0-9_.-]", "_");
  }

  /** Injective stem ('_'->"__", ':'->"_c", '/'->"_s"), so distinct bundle IDs never share a file. */
  public static String uniqueFileStem(String bundleId) {
    StringBuilder out = new StringBuilder(bundleId.length() + 8);
    for (int i = 0; i < bundleId.length(); i++) {
      char c = bundleId.charAt(i);
      if (c == '_') out.append("__");
      else if (c == ':') out.append("_c");
      else if (c == '/') out.append("_s");
      else if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '.' || c == '-') out.append(c);
      else out.append("_u").append(String.format("%04x", (int) c));
    }
    return out.toString();
  }

  /** bundle_id declared inside a bundle/adaptation file, or null when the file is unreadable. */
  public static String declaredBundleId(Path file) {
    try {
      if (Files.size(file) > 262_144) return null;
      JsonObject root =
          JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
      return str(root, "bundle_id", "").trim();
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * Keeps using a legacy (lossy {@link #safeFileStem}) file only when it belongs to this ID or
   * cannot be read; otherwise uses the injective name so another bundle's file is never reused.
   */
  public static Path fileFor(Path dir, String bundleId, String suffix) {
    Path legacy = dir.resolve(safeFileStem(bundleId) + suffix);
    if (Files.exists(legacy)) {
      String owner = declaredBundleId(legacy);
      if (owner == null || owner.equals(bundleId)) return legacy;
    }
    return dir.resolve(uniqueFileStem(bundleId) + suffix);
  }

  public static final int MAX_FILE_BYTES = 131_072;

  public void save(Path file) throws Exception {
    JsonObject out = toJson();
    BundleSanitizer.assertClean(out);
    byte[] bytes =
        new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create()
            .toJson(out)
            .getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_FILE_BYTES)
      throw new IllegalArgumentException(
          "[ADAPTATION_TOO_LARGE] 本地适配写盘后为 "
              + bytes.length
              + " 字节，超过 128 KiB 加载上限；未保存，请精简 skill_overrides 等覆盖内容");
    if (Files.exists(file)) {
      String owner = declaredBundleId(file);
      if (owner == null || !owner.equals(bundleId))
        throw new IllegalStateException(
            "[ADAPTATION_FILE_CONFLICT] 适配文件 "
                + file.getFileName()
                + (owner == null ? " 无法读取" : " 属于其他记忆包 " + owner)
                + "，拒绝覆盖；请先检查或用 reset=true 清除");
    }
    Files.createDirectories(file.getParent());
    Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
    Files.write(tmp, bytes);
    try {
      Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  public static BundleAdaptation load(Path file) throws Exception {
    return parse(
        JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject());
  }

  private static String str(JsonObject o, String key, String fallback) {
    return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback;
  }

  private static void readStringList(
      JsonObject root, String key, List<String> dest, int maxItems, int maxLen) {
    if (!root.has(key) || !root.get(key).isJsonArray()) return;
    for (JsonElement el : root.getAsJsonArray(key)) {
      if (dest.size() >= maxItems) break;
      if (el.isJsonPrimitive()) {
        String s = Rules.clean(el.getAsString(), maxLen).trim();
        if (!s.isEmpty() && !dest.contains(s)) dest.add(s);
      }
    }
  }
}
