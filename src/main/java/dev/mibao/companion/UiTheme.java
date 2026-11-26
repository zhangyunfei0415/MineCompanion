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

  public static final List<String> ALL = List.of("霓虹", "霓虹亮", "深海", "纸白", "终端", "原版");

  private static final UiTheme NEON =
      new UiTheme(
          "霓虹",
          "Gemini · 暗夜极光",
          false,
          0xd0050812,
          0xf4090e1c,
          0xf8060a14,
          0xf60d1528,
          0xff121c33,
          0xff192746,
          0xff080d1a,
          0xff243456,
          0xff3c5990,
          0xff42d9ff,
          0x2c42d9ff,
          0xffa46bff,
          0xff34d399,
          0xfffbbf24,
          0xfffb7185,
          0xfff4f7ff,
          0xffa8bad8,
          0xff788cb3,
          0xff04101c,
          0x090e1c);

  private static final UiTheme NEON_LIGHT =
      new UiTheme(
          "霓虹亮",
          "Gemini · 晨曦亮色",
          true,
          0x990b1324,
          0xf6f3f6fd,
          0xf9e8eefc,
          0xf8eef3fc,
          0xffffffff,
          0xffeef4ff,
          0xfff7f9ff,
          0xffcfd9ee,
          0xff8ea6dc,
          0xff2563eb,
          0x242563eb,
          0xff7c3aed,
          0xff047857,
          0xffb45309,
          0xffbe123c,
          0xff10182b,
          0xff425274,
          0xff637599,
          0xffffffff,
          0xeff4fc);

  private static final UiTheme OCEAN =
      new UiTheme(
          "深海",
          "深海 · DeepSeek Blue",
          false,
          0xc8050914,
          0xf40a101d,
          0xf8070b15,
          0xf60e1526,
          0xff111a2e,
          0xff182540,
          0xff080d18,
          0xff21304d,
          0xff3b5485,
          0xff5c88ff,
          0x2c5c88ff,
          0xff38bdf8,
          0xff34d399,
          0xfffbbf24,
          0xfff87171,
          0xfff0f5ff,
          0xffa2b4d1,
          0xff7388ad,
          0xff040b1a,
          0x0a1120);

  private static final UiTheme PAPER =
      new UiTheme(
          "纸白",
          "纸白 · Ivory Ink",
          true,
          0x9912100d,
          0xf6f6f2ea,
          0xf9ebe4d7,
          0xf8f1ece1,
          0xfffffcf7,
          0xfff4eee2,
          0xfff9f6ef,
          0xffd6ccbc,
          0xffa6967f,
          0xff185c70,
          0x24185c70,
          0xffb45309,
          0xff047857,
          0xffb45309,
          0xffbe123c,
          0xff1e1b18,
          0xff524a40,
          0xff6f6558,
          0xffffffff,
          0xf1ece2);

  private static final UiTheme TERMINAL =
      new UiTheme(
          "终端",
          "终端 · Phosphor Matrix",
          false,
          0xc8030a06,
          0xf5050d08,
          0xf8030905,
          0xf608140d,
          0xff0c1c13,
          0xff12281c,
          0xff040a06,
          0xff194029,
          0xff2b6d47,
          0xff34f288,
          0x2a34f288,
          0xff06d6e0,
          0xff4ade80,
          0xfffacc15,
          0xfffb7185,
          0xffe2ffed,
          0xff8cd1a8,
          0xff5f9d78,
          0xff031409,
          0x06140c);

  private static final UiTheme VANILLA =
      new UiTheme(
          "原版",
          "原版 · Craftsman Oak",
          false,
          0xc8120f0c,
          0xf41b1712,
          0xf813100d,
          0xf6221c16,
          0xff272019,
          0xff332a21,
          0xff15120e,
          0xff493d2f,
          0xff735e46,
          0xffe8b948,
          0x2ce8b948,
          0xff34d399,
          0xff4ade80,
          0xfffbbf24,
          0xfff87171,
          0xfff8f1e4,
          0xffcabca5,
          0xff978873,
          0xff181207,
          0x1b1712);

  private static final Map<String, UiTheme> BY_ID =
      Map.of(
          NEON.id, NEON,
          NEON_LIGHT.id, NEON_LIGHT,
          OCEAN.id, OCEAN,
          PAPER.id, PAPER,
          TERMINAL.id, TERMINAL,
          VANILLA.id, VANILLA);

  public boolean isAurora() {
    return id.equals("霓虹") || id.equals("霓虹亮");
  }

  public int auroraMid() {
    return switch (id) {
      case "霓虹" -> 0xff4f8cff;
      case "霓虹亮" -> 0xff4f46e5;
      case "深海" -> 0xff3b82f6;
      default -> lerpColor(accent, accentAlt, 0.5f);
    };
  }

  public String shortBadge() {
    return switch (id) {
      case "霓虹" -> "Gemini 暗";
      case "霓虹亮" -> "Gemini 亮";
      case "深海" -> "DeepSeek";
      case "纸白" -> "纸白 Ivory";
      case "终端" -> "CRT 矩阵";
      case "原版" -> "原版橡木";
      default -> "G4Neon";
    };
  }

  public String toggledLightDarkId() {
    return switch (id) {
      case "霓虹" -> "霓虹亮";
      case "霓虹亮" -> "霓虹";
      case "纸白" -> "深海";
      case "深海" -> "纸白";
      case "终端" -> "霓虹亮";
      case "原版" -> "纸白";
      default -> light ? "霓虹" : "霓虹亮";
    };
  }

  public static double contrastRatio(int fg, int bg) {
    double l1 = relativeLuminance(fg);
    double l2 = relativeLuminance(bg);
    return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
  }

  private static double relativeLuminance(int argb) {
    double r = ((argb >>> 16) & 0xff) / 255.0;
    double g = ((argb >>> 8) & 0xff) / 255.0;
    double b = (argb & 0xff) / 255.0;
    return 0.2126 * linearChannel(r) + 0.7152 * linearChannel(g) + 0.0722 * linearChannel(b);
  }

  private static double linearChannel(double c) {
    return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  }

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
