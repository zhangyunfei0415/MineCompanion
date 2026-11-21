package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SkillBundleTest {
  private static JsonObject readResource(String path) throws Exception {
    try (var in = SkillBundleTest.class.getResourceAsStream(path)) {
      assertNotNull(in, "Missing resource " + path);
      return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
          .getAsJsonObject();
    }
  }

  @Test
  void builtinAndSampleBundlesParseValidateAndComputeCanonicalSha() throws Exception {
    JsonObject builtinRaw = readResource("/skill-bundles/builtin-vanilla-furnace-demo.mcskill.json");
    SkillBundle builtin = new SkillBundle(builtinRaw);
    assertEquals("builtin:vanilla-furnace-demo", builtin.bundleId);
    assertEquals(1, builtin.uiProfiles.size());
    assertEquals(1, builtin.skills.size());
    assertTrue(builtin.hasTrackB());
    assertEquals("track_b_promotion", "ae2_delegation".equals(builtin.trackA.trackBPromotion().kind()) ? "track_b_promotion" : "");
    assertTrue(builtin.contentSha256.matches("[0-9a-f]{64}"));
    assertEquals(builtin.contentSha256, builtin.expectedSha256);

    Path examplePath = Path.of("examples/bundles/mekanism-enrichment-sample.mcskill.json");
    assertTrue(Files.exists(examplePath), "Missing " + examplePath.toAbsolutePath());
    JsonObject sampleRaw =
        JsonParser.parseString(Files.readString(examplePath, StandardCharsets.UTF_8))
            .getAsJsonObject();
    SkillBundle sample = new SkillBundle(sampleRaw);
    assertEquals("community:mekanism-enrichment-basics", sample.bundleId);
    assertEquals(sample.contentSha256, sample.expectedSha256);
    assertEquals(1, sample.trackA.trackBPromotion().patterns().size());
    assertFalse(sample.playbook.ae2Delegation().isBlank());
  }

  @Test
  void rejectsTamperedIntegrityHashAndLeakedHostPathsOrSecrets() throws Exception {
    JsonObject raw = readResource("/skill-bundles/builtin-vanilla-furnace-demo.mcskill.json");
    SkillBundle ok = new SkillBundle(raw);
    JsonObject exported = ok.toExportJson(null, false, "test-build");
    // Exact canonical hash passes
    assertDoesNotThrow(() -> new SkillBundle(exported));

    // Tamper playbook after hashing
    JsonObject tampered = exported.deepCopy();
    tampered.getAsJsonObject("playbook").addProperty("overview", "恶意篡改后的文本");
    assertThrows(IllegalArgumentException.class, () -> new SkillBundle(tampered));

    // Local drive path rejected
    JsonObject leakedDrive = raw.deepCopy();
    leakedDrive.getAsJsonObject("playbook").addProperty("overview", "查看 D:\\Users\\secret\\key.txt");
    assertThrows(IllegalArgumentException.class, () -> new SkillBundle(leakedDrive));

    // file:/// URI and Unix /Users/ rejected
    JsonObject leakedUri = raw.deepCopy();
    leakedUri.getAsJsonObject("playbook").addProperty("overview", "打开 file:///Users/alice/config.json");
    assertThrows(IllegalArgumentException.class, () -> new SkillBundle(leakedUri));

    // API key literal rejected
    JsonObject leakedSecret = raw.deepCopy();
    leakedSecret.addProperty("apiKey", "sk-1234567890abcdef123456");
    assertThrows(IllegalArgumentException.class, () -> new SkillBundle(leakedSecret));
  }

  @Test
  void skillPackageStillRejectsSchema2WhileSkillBundleHandlesIt() throws Exception {
    JsonObject raw = readResource("/skill-bundles/builtin-vanilla-furnace-demo.mcskill.json");
    assertThrows(IllegalArgumentException.class, () -> new SkillPackage(raw));
  }

  @Test
  void trackBNestedPromotionRequiresTrackAAndDowngradesOnEnvDrift() throws Exception {
    JsonObject raw = readResource("/skill-bundles/builtin-vanilla-furnace-demo.mcskill.json");
    SkillBundle bundle = new SkillBundle(raw);
    BundleAdaptation adapt = new BundleAdaptation();
    adapt.bundleId = bundle.bundleId;
    adapt.baseSha256 = bundle.contentSha256;
    adapt.environmentKey = "env_hash_pack_1";

    Set<String> modsWithAe = Set.of("minecraft", "forge", "ae2");
    assertEquals("track_a_unverified", adapt.effectiveTrack(bundle, modsWithAe, "env_hash_pack_1"));
    assertFalse(adapt.canPromoteTrackB(bundle, modsWithAe, "env_hash_pack_1"));

    // Verify Track A, still missing AE2 preconditions
    adapt.trackState.trackAVerified = true;
    adapt.trackState.trackARuns = 1;
    assertEquals("track_a_verified", adapt.effectiveTrack(bundle, modsWithAe, "env_hash_pack_1"));
    assertFalse(adapt.canPromoteTrackB(bundle, modsWithAe, "env_hash_pack_1"));
    assertEquals(
        2, adapt.missingTrackBPreconditions(bundle, modsWithAe).size());

    // Satisfy preconditions and promote
    adapt.trackState.verifiedPreconditions.add("hopper_or_provider_attached");
    adapt.trackState.verifiedPreconditions.add("ae2_pattern_encoded");
    assertTrue(adapt.canPromoteTrackB(bundle, modsWithAe, "env_hash_pack_1"));
    adapt.trackState.trackBPromoted = true;
    assertEquals("track_b_reuse", adapt.effectiveTrack(bundle, modsWithAe, "env_hash_pack_1"));

    // Removing ae2 or drifting EnvironmentKey degrades cleanly back to Track A reverify
    assertEquals("track_a_verified", adapt.effectiveTrack(bundle, Set.of("minecraft"), "env_hash_pack_1"));
    assertEquals("track_a_reverify", adapt.effectiveTrack(bundle, modsWithAe, "env_hash_pack_2_changed"));
  }
}
