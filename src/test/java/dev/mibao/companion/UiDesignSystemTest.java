package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class UiDesignSystemTest {
  @Test
  void allFiveThemesExistWithValidContrastAndFallbacks() {
    assertEquals(List.of("霓虹", "深海", "纸白", "终端", "原版"), UiTheme.ALL);
    for (String id : UiTheme.ALL) {
      UiTheme t = UiTheme.of(id);
      assertEquals(id, t.id());
      assertEquals("纸白".equals(id), t.light());
      assertNotEquals(t.windowBg(), t.text());
      assertNotEquals(t.cardBg(), t.accent());
      int bg85 = t.hudBg(85);
      assertTrue(((bg85 >>> 24) & 0xff) >= 200);
    }
    assertEquals("霓虹", UiTheme.of(null).id());
    assertEquals("霓虹", UiTheme.of("nonexistent").id());
    assertEquals(0x80ffffff, UiTheme.withAlpha(0xffffffff, 128));
    assertEquals(0xff808080, UiTheme.lerpColor(0xff000000, 0xffffffff, 0.5f));
  }

  @Test
  void settingsCatalogMatchesChineseEnglishAndMultiWordQueries() {
    assertTrue(SettingsCatalog.search("").isEmpty());
    assertTrue(SettingsCatalog.search("   ").isEmpty());
    assertTrue(SettingsCatalog.search("zzzz-unmatched-token").isEmpty());

    assertFalse(SettingsCatalog.search("判断").isEmpty());
    assertFalse(SettingsCatalog.search("公屏").isEmpty());
    assertFalse(SettingsCatalog.search("导航").isEmpty());
    assertFalse(SettingsCatalog.search("租约").isEmpty());
    assertFalse(SettingsCatalog.search("主题").isEmpty());
    assertFalse(SettingsCatalog.search("harness").isEmpty());
    assertFalse(SettingsCatalog.search("api").isEmpty());
    assertFalse(SettingsCatalog.search("hud").isEmpty());
    assertFalse(SettingsCatalog.search("full").isEmpty());

    var navBreak = SettingsCatalog.search("导航 破坏");
    assertFalse(navBreak.isEmpty());
    assertTrue(navBreak.stream().anyMatch(e -> e.id().equals("navigation_terrain")));

    for (var entry : SettingsCatalog.ALL) {
      assertTrue(entry.page() >= 0 && entry.page() <= 4, entry.id());
      if (entry.section() >= 0) {
        var route = DeskRoute.of(entry.section(), true);
        assertFalse(route.title().isBlank());
      }
    }
  }

  @Test
  void storeConfigDefaultsUseValidTheme() {
    var cfg = new Store.Config();
    assertTrue(UiTheme.ALL.contains(cfg.theme));
    assertEquals("霓虹", cfg.theme);
  }

  @Test
  void rulesCleanAndActionFailedHandleEdgeCasesSafely() {
    assertFalse(Rules.actionFailed(null));
    assertTrue(Rules.actionFailed("失败：权限不足"));
    assertTrue(Rules.actionFailed("{\"status\":\"rejected\"}"));
    assertTrue(Rules.actionFailed("{\"business_success\":false}"));
    assertFalse(Rules.actionFailed("成功：已确认"));

    assertEquals("", Rules.clean(null, 50));
    assertEquals("", Rules.clean("abc", 0));
    assertEquals("hello\n\tworld", Rules.clean("hello\n\tworld", 100));
    assertEquals("abcdef", Rules.clean("a\u0000b\u0007cdef\u007f", 10));
    assertEquals("abc", Rules.clean("a\u0000bcdef", 3));
    assertEquals("abc", Rules.clean("abcdef", 3));
  }

  @Test
  void bagPolicyCopyIsIndependentDeepCopy() {
    var orig = new BagPolicy();
    orig.name = "矿洞补给";
    orig.source = "材料箱";
    orig.destination = "交付箱";
    orig.desired.put("minecraft:bread", 16);
    orig.mandatory.add("minecraft:bread");
    orig.protectedItems.add("minecraft:diamond_pickaxe#sha256:" + "a".repeat(64));

    var copy = orig.copy();
    copy.desired.put("minecraft:torch", 32);
    copy.mandatory.remove("minecraft:bread");
    copy.protectedItems.clear();
    copy.name = "改名";

    assertEquals("矿洞补给", orig.name);
    assertEquals(1, orig.desired.size());
    assertTrue(orig.mandatory.contains("minecraft:bread"));
    assertEquals(1, orig.protectedItems.size());
  }

  @Test
  void worldMemoryDistanceSquaredIsExact() {
    var p = new WorldMemory.Point();
    p.x = 10;
    p.y = 64;
    p.z = -5;
    assertEquals(3 * 3 + 4 * 4 + 5 * 5, WorldMemory.distanceSquared(p, 7, 60, 0));
  }
}
