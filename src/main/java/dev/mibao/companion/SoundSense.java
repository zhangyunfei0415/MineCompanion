package dev.mibao.companion;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.*;

/**
 * Client-side SoundEventListener hook (bytecode-verified to fire before master-volume mute check so
 * Subtitles OFF / F4 eco mute do not silence perception).
 */
public final class SoundSense {
  private static final SoundPolicy.Buffer BUFFER = new SoundPolicy.Buffer();
  private static final SoundEventListener LISTENER = SoundSense::onPlaySound;
  private static SoundManager attachedManager;
  private static volatile long lastPhantomCueMs;

  private SoundSense() {}

  public static void attach() {
    var mc = Minecraft.getInstance();
    var sm = mc == null ? null : mc.getSoundManager();
    if (sm != null && attachedManager != sm) {
      sm.removeListener(LISTENER);
      sm.addListener(LISTENER);
      attachedManager = sm;
    }
  }

  public static void detach() {
    if (attachedManager != null) {
      try {
        attachedManager.removeListener(LISTENER);
      } catch (RuntimeException ignored) {
      }
      attachedManager = null;
    }
    reset();
  }

  public static void reset() {
    BUFFER.clear();
    lastPhantomCueMs = 0L;
  }

  public static long lastPhantomCueMs() {
    return lastPhantomCueMs;
  }

  private static void onPlaySound(SoundInstance sound, WeighedSoundEvents accessor) {
    if (!Store.config.soundSense || sound == null) return;
    var mc = Minecraft.getInstance();
    var player = mc.player;
    var level = mc.level;
    if (player == null || level == null) return;
    String id = sound.getLocation() == null ? "" : sound.getLocation().toString();
    if (id.isBlank()) return;
    String source = sound.getSource() == null ? "MASTER" : sound.getSource().name();
    boolean directionless =
        sound.isRelative()
            || sound.getAttenuation() == SoundInstance.Attenuation.NONE
            || SoundPolicy.isDirectionlessSource(source);
    double dx = sound.getX() - player.getX();
    double dy = sound.getY() - player.getY();
    double dz = sound.getZ() - player.getZ();
    double dist = directionless ? 0.0 : Math.sqrt(dx * dx + dy * dy + dz * dz);

    SoundPolicy.Tier tier =
        SoundPolicy.classify(
            id,
            source,
            dist,
            directionless,
            Store.config.customSoundWarn,
            Store.config.customSoundInfo,
            Store.config.customSoundIgnore);
    if (tier == SoundPolicy.Tier.IGNORE) return;

    long now = System.currentTimeMillis();
    if (id.equals("minecraft:entity.phantom.flap")
        || id.equals("minecraft:entity.phantom.swoop")) {
      lastPhantomCueMs = now;
    }
    String bearing =
        SoundPolicy.relativeBearing(
            player.getX(), player.getZ(), player.getYRot(), sound.getX(), sound.getZ(), directionless);
    BUFFER.record(id, tier, bearing, dist, sound.getX(), sound.getY(), sound.getZ(), now);

    if (Store.config.soundReflex) {
      if (tier == SoundPolicy.Tier.LETHAL
          && !directionless
          && dist <= 9.0
          && (id.equals("minecraft:entity.creeper.primed")
              || id.equals("minecraft:entity.tnt.primed"))) {
        SoundReflex.triggerDanger(id, sound.getX(), sound.getY(), sound.getZ(), dist);
      } else if (id.equals("minecraft:block.lava.pop") && !directionless && dist <= 5.5) {
        SoundReflex.triggerMiningLavaStop(id, sound.getX(), sound.getY(), sound.getZ(), dist);
      }
    }
  }

  public static com.google.gson.JsonArray recentJson() {
    if (!Store.config.soundSense) return new com.google.gson.JsonArray();
    return BUFFER.toJsonArray(System.currentTimeMillis(), SoundPolicy.MAX_EMIT);
  }

  public static void append(JsonObject obs) {
    if (!Store.config.soundSense || obs == null) return;
    var arr = recentJson();
    if (arr.size() > 0) obs.add("recent_sounds", arr);
  }
}
