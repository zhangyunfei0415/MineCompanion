package dev.mibao.companion;

import java.util.List;
import java.util.Map;

/**
 * Curated semantic color palettes for the G4Neon workbench, overlays, and secondary screens.
 * Pure data — zero Minecraft rendering dependencies so themes can be tested offline.
 */
public record UiTheme(
    String id,
    String label,
    boolean light,
    int backdrop,
    int windowBg,
    int railBg,
    int headerBg,
    int cardBg,
    int cardHover,
    int inputBg,
    int border,
    int borderStrong,
    int accent,
    int accentSoft,
    int accentAlt,
    int success,
    int warning,
    int danger,
    int text,
    int textSecondary,
    int textMuted,
    int textOnAccent,
    int hudRgb) {

  public static final List<String> ALL = List.of("霓虹", "深海", "纸白", "终端", "原版");

  private static final UiTheme NEON =
      new UiTheme(
          "霓虹",
          "G4Neon · 霓虹夜幕",
          false,
          0xcc060811,
          0xf20b101b,
          0xf7070b14,
          0xf50e1525,
          0xff121a2b,
          0xff19253d,
          0xff080d18,
          0xff22304c,
          0xff35507c,
          0xff38f2e6,
          0x2a38f2e6,
          0xffa855f7,
          0xff34d399,
          0xfffbbf24,
          0xfffb7185,
          0xfff1f5f9,
          0xff9fb0c9,
          0xff667a99,
          0xff051018,
          0x0b101d);

  private static final UiTheme OCEAN =
      new UiTheme(
          "深海",
          "深海 · Abyssal Teal",
          false,
          0xc4040d14,
          0xf2081520,
          0xf7061019,
          0xf50b1b29,
          0xff102230,
          0xff162e40,
          0xff07121b,
          0xff1e3a4c,
          0xff2d5972,
          0xff5eead4,
          0x285eead4,
          0xff38bdf8,
          0xff4ade80,
          0xfffbbf24,
          0xfff87171,
          0xffe6f2f6,
          0xff9ab9c6,
          0xff648494,
          0xff06151c,
          0x0a1826);

  private static final UiTheme PAPER =
      new UiTheme(
          "纸白",
          "纸白 · Editorial Paper",
          true,
          0x99101820,
          0xf5f2f6f7,
          0xf8e5edf0,
          0xf8eaf0f2,
          0xffffffff,
          0xffeef5f7,
          0xfff7fafb,
          0xffc7d6dc,
          0xff88a8b5,
          0xff0e7490,
          0x220e7490,
          0xff6366f1,
          0xff059669,
          0xffd97706,
          0xffe11d48,
          0xff0f2430,
          0xff435e6e,
          0xff6d8694,
          0xffffffff,
          0xeef4f6);

  private static final UiTheme TERMINAL =
      new UiTheme(
          "终端",
          "终端 · Phosphor CRT",
          false,
          0xc8030a06,
          0xf205120b,
          0xf7030c07,
          0xf507180f,
          0xff0a1e13,
          0xff102c1d,
          0xff040e08,
          0xff1a422b,
          0xff2a6845,
          0xff4ade80,
          0x284ade80,
          0xff2dd4bf,
          0xff4ade80,
          0xfffacc15,
          0xfffb7185,
          0xffd1fae5,
          0xff86c9a2,
          0xff538b6b,
          0xff041209,
          0x07180f);

  private static final UiTheme VANILLA =
      new UiTheme(
          "原版",
          "原版 · Craftsman Oak",
          false,
          0xc8120f0c,
          0xf21e1a15,
          0xf716130f,
          0xf5241f19,
          0xff29231c,
          0xff352d24,
          0xff181410,
          0xff4a3f32,
          0xff6d5b46,
          0xffe2b866,
          0x2ae2b866,
          0xfff59e0b,
          0xff4ade80,
          0xfffbbf24,
          0xfff87171,
          0xfff5efe4,
          0xffc9bea9,
          0xff8c806c,
          0xff19130b,
          0x1e1a14);

  private static final Map<String, UiTheme> BY_ID =
      Map.of(
          NEON.id, NEON,
          OCEAN.id, OCEAN,
          PAPER.id, PAPER,
          TERMINAL.id, TERMINAL,
          VANILLA.id, VANILLA);

  public static UiTheme of(String id) {
    return id == null ? NEON : BY_ID.getOrDefault(id, NEON);
  }

  public static UiTheme current() {
    return of(Store.config == null ? null : Store.config.theme);
  }

  public int hudBg(int opacityPercent) {
    int alpha = Rules.bounded(opacityPercent, 40, 100) * 255 / 100;
    return (alpha << 24) | (hudRgb & 0x00ffffff);
  }

  public static int withAlpha(int argb, int alpha) {
    int clamped = Rules.bounded(alpha, 0, 255);
    return (clamped << 24) | (argb & 0x00ffffff);
  }

  public static int lerpColor(int a, int b, float t) {
    float k = Math.max(0f, Math.min(1f, t));
    int aa = (a >>> 24) & 0xff, ar = (a >>> 16) & 0xff, ag = (a >>> 8) & 0xff, ab = a & 0xff;
    int ba = (b >>> 24) & 0xff, br = (b >>> 16) & 0xff, bg = (b >>> 8) & 0xff, bb = b & 0xff;
    int oa = Math.round(aa + (ba - aa) * k);
    int or = Math.round(ar + (br - ar) * k);
    int og = Math.round(ag + (bg - ag) * k);
    int ob = Math.round(ab + (bb - ab) * k);
    return (oa << 24) | (or << 16) | (og << 8) | ob;
  }
}
