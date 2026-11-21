package dev.mibao.companion;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * Registry and persistent local draft store for declarative UiProfiles. Zero Minecraft imports on
 * Path-based APIs so unit tests run cleanly offline without booting client runtime.
 */
public final class UiProfileStore {
  public record Entry(
      UiProfile profile, String source, String trust, String bundleId, boolean confirmed) {}

  public record MatchResult(Entry entry, int score) {}

  private static final Gson PRETTY =
      new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

  public static final Map<String, Entry> localEntries = new LinkedHashMap<>();
  public static final Map<String, Entry> bundleEntries = new LinkedHashMap<>();
  public static final List<String> errors = new ArrayList<>();

  private UiProfileStore() {}

  public static Path profilesDir(Path libraryRoot) {
    return libraryRoot.resolve("ui-profiles");
  }

  public static void refresh() {
    refresh(SkillLibrary.root());
  }

  public static synchronized void refresh(Path libraryRoot) {
    localEntries.clear();
    errors.clear();
    Path dir = profilesDir(libraryRoot);
    try {
      Files.createDirectories(dir);
      try (var files = Files.list(dir)) {
        for (Path p :
            files
                .filter(f -> f.getFileName().toString().endsWith(".uiprofile.json"))
                .sorted()
                .limit(128)
                .toList()) {
          try {
            if (Files.size(p) > 100_000) throw new IllegalArgumentException("超过 100KB");
            JsonObject wrapper =
                JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            boolean confirmed =
                wrapper.has("confirmed") && wrapper.get("confirmed").getAsBoolean();
            JsonObject profileObj =
                wrapper.has("profile") && wrapper.get("profile").isJsonObject()
                    ? wrapper.getAsJsonObject("profile")
                    : wrapper;
            UiProfile profile = new UiProfile(profileObj);
            localEntries.put(
                profile.id,
                new Entry(
                    profile,
                    confirmed ? "local_confirmed" : "local_draft",
                    confirmed ? "local_confirmed" : "local_unverified_draft",
                    "",
                    confirmed));
          } catch (Exception e) {
            errors.add(p.getFileName() + "：" + Rules.clean(e.getMessage(), 180));
          }
        }
      }
    } catch (Exception e) {
      errors.add("ui-profiles 目录不可用：" + Rules.clean(e.getMessage(), 180));
    }
  }

  public static synchronized void registerBundleProfiles(
      Collection<BundleLibrary.EffectiveBundle> bundles) {
    bundleEntries.clear();
    if (bundles == null) return;
    for (var eb : bundles) {
      for (UiProfile p : eb.effective().uiProfiles) {
        String source = eb.adapted() ? "bundle_adapted" : "bundle";
        String trust = eb.trustStatus();
        bundleEntries.putIfAbsent(p.id, new Entry(p, source, trust, eb.effective().bundleId, eb.trackAVerified()));
      }
    }
  }

  public static synchronized Entry get(String profileId) {
    if (profileId == null || profileId.isBlank()) return null;
    Entry local = localEntries.get(profileId);
    if (local != null && local.confirmed()) return local;
    Entry bundle = bundleEntries.get(profileId);
    if (bundle != null) return bundle;
    return local;
  }

  public static synchronized List<UiProfile> allProfiles() {
    Map<String, UiProfile> all = new LinkedHashMap<>();
    bundleEntries.forEach((k, v) -> all.put(k, v.profile()));
    localEntries.forEach((k, v) -> all.putIfAbsent(k, v.profile()));
    return List.copyOf(all.values());
  }

  public static synchronized MatchResult match(
      String portableSig, String layoutSig, String blockId, String menuClass) {
    MatchResult best = null;
    List<Entry> candidates = new ArrayList<>();
    candidates.addAll(localEntries.values());
    candidates.addAll(bundleEntries.values());
    for (Entry entry : candidates) {
      int score = entry.profile().matchScore(portableSig, layoutSig, blockId, menuClass);
      if (score <= 0) continue;
      if (best == null
          || score > best.score()
          || (score == best.score() && rank(entry) > rank(best.entry()))) {
        best = new MatchResult(entry, score);
      }
    }
    return best;
  }

  private static int rank(Entry e) {
    return switch (e.source()) {
      case "local_confirmed" -> 4;
      case "bundle_adapted" -> 3;
      case "bundle" -> 2;
      default -> 1;
    };
  }

  public static Entry saveDraft(JsonObject profileJson, boolean confirmed) throws Exception {
    return saveDraft(SkillLibrary.root(), profileJson, confirmed);
  }

  public static synchronized Entry saveDraft(
      Path libraryRoot, JsonObject profileJson, boolean confirmed) throws Exception {
    UiProfile profile = new UiProfile(profileJson);
    Path dir = profilesDir(libraryRoot);
    Files.createDirectories(dir);
    Path file = dir.resolve(BundleAdaptation.safeFileStem(profile.id) + ".uiprofile.json");
    JsonObject wrapper = new JsonObject();
    wrapper.addProperty("schema", 1);
    wrapper.addProperty("confirmed", confirmed);
    wrapper.addProperty("source", confirmed ? "user_confirmed" : "agent_draft");
    wrapper.addProperty("updated_at", Instant.now().toString());
    wrapper.add("profile", profile.raw.deepCopy());
    Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
    Files.writeString(tmp, PRETTY.toJson(wrapper), StandardCharsets.UTF_8);
    try {
      Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
    Entry entry =
        new Entry(
            profile,
            confirmed ? "local_confirmed" : "local_draft",
            confirmed ? "local_confirmed" : "local_unverified_draft",
            "",
            confirmed);
    localEntries.put(profile.id, entry);
    return entry;
  }

  public static Entry confirmDraft(String profileId) throws Exception {
    return confirmDraft(SkillLibrary.root(), profileId);
  }

  public static synchronized Entry confirmDraft(Path libraryRoot, String profileId)
      throws Exception {
    Entry existing = localEntries.get(profileId);
    if (existing == null) throw new IllegalArgumentException("未找到本地界面草稿：" + profileId);
    return saveDraft(libraryRoot, existing.profile().raw, true);
  }

  public static boolean deleteLocal(String profileId) throws Exception {
    return deleteLocal(SkillLibrary.root(), profileId);
  }

  public static synchronized boolean deleteLocal(Path libraryRoot, String profileId)
      throws Exception {
    if (profileId == null || profileId.isBlank()) return false;
    localEntries.remove(profileId);
    Path file =
        profilesDir(libraryRoot)
            .resolve(BundleAdaptation.safeFileStem(profileId) + ".uiprofile.json");
    return Files.deleteIfExists(file);
  }

  public static synchronized List<Entry> listLocal() {
    return List.copyOf(localEntries.values());
  }
}
