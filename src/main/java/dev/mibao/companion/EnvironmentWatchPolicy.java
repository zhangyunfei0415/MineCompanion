package dev.mibao.companion;

import com.google.gson.JsonObject;

/**
 * Pure-Java time-of-day, weather, and phantom-risk classifier with hasSkyLight gate. Zero {@code
 * net.minecraft.*} imports.
 */
public final class EnvironmentWatchPolicy {
  public record Snapshot(
      boolean skyLight,
      String phase,
      String weather,
      boolean nightDanger,
      boolean phantomRisk,
      int daysSinceSleep,
      String alert) {
    public JsonObject toJson() {
      var o = new JsonObject();
      if (!skyLight) return o;
      o.addProperty("sky_light", true);
      o.addProperty("phase", phase);
      o.addProperty("weather", weather);
      o.addProperty("night_danger", nightDanger);
      o.addProperty("phantom_risk", phantomRisk);
      o.addProperty("days_since_sleep", daysSinceSleep);
      if (!alert.isBlank()) o.addProperty("alert", alert);
      return o;
    }
  }

  private EnvironmentWatchPolicy() {}

  public static Snapshot evaluate(
      boolean hasSkyLight,
      long dayTimeMod24000,
      boolean raining,
      boolean thundering,
      int daysSinceSleep,
      long lastPhantomCueMs,
      long nowMs) {
    if (!hasSkyLight) {
      return new Snapshot(false, "no_sky", "none", false, false, 0, "");
    }
    long t = ((dayTimeMod24000 % 24000L) + 24000L) % 24000L;
    String phase;
    boolean nightDanger = false;
    if (t < 1000L || t >= 23000L) {
      phase = "dawn";
    } else if (t < 12000L) {
      phase = "day";
    } else if (t < 13000L) {
      phase = "dusk";
    } else if (t < 18500L) {
      phase = "night";
      nightDanger = true;
    } else {
      phase = "late_night";
      nightDanger = true;
    }
    String weather = thundering ? "thunder" : raining ? "rain" : "clear";
    boolean recentPhantom = lastPhantomCueMs > 0 && nowMs - lastPhantomCueMs <= 20_000L;
    boolean phantomRisk = (daysSinceSleep >= 3 && nightDanger) || recentPhantom;

    String alert = "";
    if (thundering) {
      alert = "雷暴天气，敌对生物可在白天刷新且有雷击风险";
    } else if (phantomRisk) {
      alert = "已连续" + Math.max(3, daysSinceSleep) + "日未睡眠且夜间存在幻翼风险，建议尽快找床休息";
    } else if (phase.equals("dusk")) {
      alert = "即将入夜，户外风险上升";
    } else if (nightDanger && daysSinceSleep >= 3) {
      alert = "当前为夜间，户外有敌对生物刷新";
    }
    return new Snapshot(true, phase, weather, nightDanger, phantomRisk, daysSinceSleep, alert);
  }
}
