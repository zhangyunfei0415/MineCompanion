package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;

/**
 * Pure declarative UI mapping for unknown or modded container screens. Zero runtime or world
 * bindings; safe to share across modpacks and locally adapt via UiProfile patches.
 */
public final class UiProfile {
  public record MatchSpec(
      List<String> portableSignatures,
      List<String> layoutSignatures,
      List<String> menuClasses,
      List<String> blockIds,
      int imageWidth,
      int imageHeight) {}

  public record SlotDef(
      String role,
      boolean hasCoords,
      int x,
      int y,
      int menuSlot,
      boolean virtual,
      List<String> accepts,
      String note) {}

  public record ControlDef(
      String name,
      String kind,
      String label,
      int dx,
      int dy,
      int button,
      boolean shift,
      List<String> expectedTooltipContains,
      String effectNote) {}

  public record Ae2Integration(
      boolean automatableViaPatternProvider,
      String patternMode,
      List<String> recommendedProviderFaces,
      List<String> preSetupControls,
      String ioNote) {}

  public record LiveSlot(
      int index,
      int x,
      int y,
      boolean playerInventory,
      String itemId,
      int count,
      String fingerprint) {}

  public final String id;
  public final String name;
  public final MatchSpec match;
  public final List<SlotDef> slots;
  public final List<ControlDef> controls;
  public final List<String> completionSignals;
  public final Ae2Integration ae2Integration;
  public final JsonObject raw;

  public UiProfile(JsonObject input) {
    if (input == null) throw new IllegalArgumentException("UiProfile 不能为空");
    BundleSanitizer.assertClean(input);
    this.raw = input.deepCopy();
    this.id = str(input, "id", "").trim();
    if (!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || id.length() > 120) {
      throw new IllegalArgumentException("UiProfile id 须为命名空间格式且最长 120：" + id);
    }
    this.name = Rules.clean(str(input, "name", id), 80);
    this.match = parseMatch(input.has("match") && input.get("match").isJsonObject()
        ? input.getAsJsonObject("match")
        : new JsonObject());
    this.slots = parseSlots(input.has("slots") && input.get("slots").isJsonArray()
        ? input.getAsJsonArray("slots")
        : new JsonArray());
    this.controls = parseControls(input.has("controls") && input.get("controls").isJsonArray()
        ? input.getAsJsonArray("controls")
        : new JsonArray());
    this.completionSignals = parseCompletionSignals(input);
    this.ae2Integration = parseAe2(input.has("ae2_integration") && input.get("ae2_integration").isJsonObject()
        ? input.getAsJsonObject("ae2_integration")
        : null);
  }

  private static MatchSpec parseMatch(JsonObject m) {
    List<String> portable = stringList(m, "portable_signatures", 32, 64);
    for (String sig : portable) {
      if (!sig.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("portable_signatures 须为 64 位小写 hex SHA-256");
      }
    }
    List<String> layout = stringList(m, "layout_signatures", 32, 64);
    for (String sig : layout) {
      if (!sig.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("layout_signatures 须为 64 位小写 hex SHA-256");
      }
    }
    List<String> menus = stringList(m, "menu_classes", 32, 200);
    List<String> blocks = stringList(m, "block_ids", 32, 160);
    int w = m.has("image_width") ? Rules.bounded(m.get("image_width").getAsInt(), 0, 2048) : 0;
    int h = m.has("image_height") ? Rules.bounded(m.get("image_height").getAsInt(), 0, 2048) : 0;
    return new MatchSpec(portable, layout, menus, blocks, w, h);
  }

  private static List<SlotDef> parseSlots(JsonArray arr) {
    if (arr.size() > 64) throw new IllegalArgumentException("UiProfile slots 最多 64 项");
    List<SlotDef> list = new ArrayList<>(arr.size());
    for (JsonElement el : arr) {
      JsonObject s = el.getAsJsonObject();
      String role = str(s, "role", "").trim();
      if (!role.matches("[a-z0-9_.-]{1,48}")) {
        throw new IllegalArgumentException("槽位 role 格式无效：" + role);
      }
      boolean hasCoords = s.has("x") && s.has("y");
      int x = hasCoords ? Rules.bounded(s.get("x").getAsInt(), -512, 2048) : 0;
      int y = hasCoords ? Rules.bounded(s.get("y").getAsInt(), -512, 2048) : 0;
      int menuSlot = s.has("menu_slot") ? Rules.bounded(s.get("menu_slot").getAsInt(), -1, 512) : -1;
      if (!hasCoords && menuSlot < 0) {
        throw new IllegalArgumentException("槽位定义须提供 (x,y) 或 menu_slot：" + role);
      }
      boolean virtual = s.has("virtual") && s.get("virtual").getAsBoolean();
      List<String> accepts = stringList(s, "accepts", 32, 160);
      String note = Rules.clean(str(s, "note", ""), 200);
      list.add(new SlotDef(role, hasCoords, x, y, menuSlot, virtual, accepts, note));
    }
    return List.copyOf(list);
  }

  private static List<ControlDef> parseControls(JsonArray arr) {
    if (arr.size() > 32) throw new IllegalArgumentException("UiProfile controls 最多 32 项");
    List<ControlDef> list = new ArrayList<>(arr.size());
    Set<String> seen = new HashSet<>();
    for (JsonElement el : arr) {
      JsonObject c = el.getAsJsonObject();
      String name = str(c, "name", "").trim();
      if (!name.matches("[a-z0-9_.-]{1,48}") || !seen.add(name)) {
        throw new IllegalArgumentException("控件 name 无效或重复：" + name);
      }
      String kind = str(c, "kind", "").trim();
      if (!Set.of("widget_label", "relative_point").contains(kind)) {
        throw new IllegalArgumentException("控件 kind 须为 widget_label/relative_point：" + kind);
      }
      String label = Rules.clean(str(c, "label", ""), 160);
      int dx = c.has("dx") ? Rules.bounded(c.get("dx").getAsInt(), -128, 512) : 0;
      int dy = c.has("dy") ? Rules.bounded(c.get("dy").getAsInt(), -128, 512) : 0;
      int button = c.has("button") ? Rules.bounded(c.get("button").getAsInt(), 0, 1) : 0;
      boolean shift = c.has("shift") && c.get("shift").getAsBoolean();
      if (kind.equals("widget_label") && label.isBlank()) {
        throw new IllegalArgumentException("widget_label 控件须提供 label：" + name);
      }
      if (kind.equals("relative_point") && (!c.has("dx") || !c.has("dy"))) {
        throw new IllegalArgumentException("relative_point 控件须提供 dx/dy：" + name);
      }
      List<String> tooltipHints = stringList(c, "expected_tooltip_contains", 16, 120);
      String note = Rules.clean(str(c, "effect_note", ""), 240);
      list.add(new ControlDef(name, kind, label, dx, dy, button, shift, tooltipHints, note));
    }
    return List.copyOf(list);
  }

  private static List<String> parseCompletionSignals(JsonObject input) {
    if (!input.has("progress_hints") || !input.get("progress_hints").isJsonObject()) return List.of();
    return stringList(input.getAsJsonObject("progress_hints"), "completion_signals", 16, 80);
  }

  private static Ae2Integration parseAe2(JsonObject ae) {
    if (ae == null) return null;
    boolean auto = ae.has("automatable_via_pattern_provider")
        && ae.get("automatable_via_pattern_provider").getAsBoolean();
    String mode = Rules.clean(str(ae, "pattern_mode", "processing"), 40);
    List<String> faces = stringList(ae, "recommended_provider_faces", 8, 40);
    List<String> preSetup = stringList(ae, "pre_setup_controls", 16, 60);
    String note = Rules.clean(str(ae, "io_note", ""), 300);
    return new Ae2Integration(auto, mode, faces, preSetup, note);
  }

  public int matchScore(String portableSig, String layoutSig, String blockId, String menuClass) {
    String pSig = portableSig == null ? "" : portableSig.trim();
    String lSig = layoutSig == null ? "" : layoutSig.trim();
    String bId = blockId == null ? "" : blockId.trim();
    String mCls = menuClass == null ? "" : menuClass.trim();
    if (!pSig.isEmpty() && match.portableSignatures().contains(pSig)) return 1000;
    boolean blockMatches = !bId.isEmpty() && match.blockIds().contains(bId);
    boolean menuMatches = !mCls.isEmpty() && match.menuClasses().contains(mCls);
    if (!lSig.isEmpty() && match.layoutSignatures().contains(lSig)) {
      return (blockMatches || match.blockIds().isEmpty()) ? 650 : 550;
    }
    if (blockMatches && menuMatches) return 300;
    if (match.blockIds().isEmpty() && menuMatches) return 150;
    if (match.menuClasses().isEmpty() && blockMatches) return 120;
    return 0;
  }

  public List<SlotDef> slotsForRole(String role) {
    List<SlotDef> out = new ArrayList<>();
    for (SlotDef s : slots) {
      if (s.role().equals(role)) out.add(s);
    }
    return List.copyOf(out);
  }

  public record ResolvedSlot(int index, SlotDef definition) {}

  public List<ResolvedSlot> resolveRoleSlots(String role, List<LiveSlot> liveSlots) {
    List<SlotDef> defs = slotsForRole(role);
    if (defs.isEmpty() || liveSlots == null || liveSlots.isEmpty()) return List.of();
    List<ResolvedSlot> out = new ArrayList<>();
    Set<Integer> usedIndices = new HashSet<>();
    for (SlotDef def : defs) {
      int matched = -1;
      if (def.hasCoords()) {
        for (LiveSlot ls : liveSlots) {
          if (!ls.playerInventory() && !usedIndices.contains(ls.index()) && ls.x() == def.x() && ls.y() == def.y()) {
            matched = ls.index();
            break;
          }
        }
        if (matched < 0) {
          int bestDist = 3;
          for (LiveSlot ls : liveSlots) {
            if (ls.playerInventory() || usedIndices.contains(ls.index())) continue;
            int dist = Math.abs(ls.x() - def.x()) + Math.abs(ls.y() - def.y());
            if (dist < bestDist) {
              bestDist = dist;
              matched = ls.index();
            }
          }
        }
      }
      if (matched < 0 && def.menuSlot() >= 0) {
        for (LiveSlot ls : liveSlots) {
          if (!ls.playerInventory() && ls.index() == def.menuSlot() && !usedIndices.contains(ls.index())) {
            matched = ls.index();
            break;
          }
        }
      }
      if (matched >= 0) {
        usedIndices.add(matched);
        out.add(new ResolvedSlot(matched, def));
      }
    }
    return List.copyOf(out);
  }

  public int resolveSlotIndex(String role, int roleIndex, List<LiveSlot> liveSlots) {
    List<ResolvedSlot> resolved = resolveRoleSlots(role, liveSlots);
    if (roleIndex < 0 || roleIndex >= resolved.size()) return -1;
    return resolved.get(roleIndex).index();
  }

  public ControlDef findControl(String controlName) {
    if (controlName == null) return null;
    for (ControlDef c : controls) {
      if (c.name().equals(controlName)) return c;
    }
    return null;
  }

  /** Apply a local adaptation patch (signatures, slot offsets, control tweaks) cleanly. */
  public UiProfile withPatch(JsonObject patch) {
    if (patch == null || patch.size() == 0) return this;
    JsonObject merged = raw.deepCopy();
    if (patch.has("name") && !patch.get("name").getAsString().isBlank()) {
      merged.add("name", patch.get("name").deepCopy());
    }
    JsonObject m = merged.has("match") && merged.get("match").isJsonObject()
        ? merged.getAsJsonObject("match")
        : new JsonObject();
    merged.add("match", m);
    appendUniqueStrings(m, "portable_signatures", patch.getAsJsonArray("add_portable_signatures"));
    appendUniqueStrings(m, "layout_signatures", patch.getAsJsonArray("add_layout_signatures"));
    appendUniqueStrings(m, "block_ids", patch.getAsJsonArray("add_block_ids"));
    appendUniqueStrings(m, "menu_classes", patch.getAsJsonArray("add_menu_classes"));

    if (patch.has("slots") && patch.get("slots").isJsonArray()) {
      JsonArray baseSlots = merged.has("slots") && merged.get("slots").isJsonArray()
          ? merged.getAsJsonArray("slots")
          : new JsonArray();
      for (JsonElement el : patch.getAsJsonArray("slots")) {
        JsonObject ns = el.getAsJsonObject();
        int idx = findMatchingSlotJsonIndex(baseSlots, ns);
        if (idx >= 0) baseSlots.set(idx, ns.deepCopy());
        else baseSlots.add(ns.deepCopy());
      }
      merged.add("slots", baseSlots);
    }

    if (patch.has("controls") && patch.get("controls").isJsonArray()) {
      JsonArray baseCtrls = merged.has("controls") && merged.get("controls").isJsonArray()
          ? merged.getAsJsonArray("controls")
          : new JsonArray();
      for (JsonElement el : patch.getAsJsonArray("controls")) {
        JsonObject nc = el.getAsJsonObject();
        String cName = str(nc, "name", "");
        int idx = -1;
        for (int i = 0; i < baseCtrls.size(); i++) {
          if (str(baseCtrls.get(i).getAsJsonObject(), "name", "").equals(cName)) {
            idx = i;
            break;
          }
        }
        if (idx >= 0) baseCtrls.set(idx, nc.deepCopy());
        else baseCtrls.add(nc.deepCopy());
      }
      merged.add("controls", baseCtrls);
    }
    if (patch.has("ae2_integration") && patch.get("ae2_integration").isJsonObject()) {
      merged.add("ae2_integration", patch.get("ae2_integration").deepCopy());
    }
    return new UiProfile(merged);
  }

  private static int findMatchingSlotJsonIndex(JsonArray arr, JsonObject candidate) {
    String role = str(candidate, "role", "");
    int ms = candidate.has("menu_slot") ? candidate.get("menu_slot").getAsInt() : -99;
    boolean hasXy = candidate.has("x") && candidate.has("y");
    int x = hasXy ? candidate.get("x").getAsInt() : 0;
    int y = hasXy ? candidate.get("y").getAsInt() : 0;
    for (int i = 0; i < arr.size(); i++) {
      JsonObject existing = arr.get(i).getAsJsonObject();
      if (!str(existing, "role", "").equals(role)) continue;
      if (ms >= 0 && existing.has("menu_slot") && existing.get("menu_slot").getAsInt() == ms) {
        return i;
      }
      if (hasXy && existing.has("x") && existing.has("y")
          && existing.get("x").getAsInt() == x
          && existing.get("y").getAsInt() == y) {
        return i;
      }
    }
    return -1;
  }

  private static void appendUniqueStrings(JsonObject obj, String key, JsonArray additions) {
    if (additions == null || additions.isEmpty()) return;
    JsonArray target = obj.has(key) && obj.get(key).isJsonArray() ? obj.getAsJsonArray(key) : new JsonArray();
    Set<String> seen = new LinkedHashSet<>();
    for (JsonElement el : target) seen.add(el.getAsString());
    for (JsonElement el : additions) {
      String val = el.getAsString().trim();
      if (!val.isEmpty() && seen.add(val)) target.add(val);
    }
    obj.add(key, target);
  }

  public JsonObject summaryJson(String trustStatus) {
    JsonObject o = raw.deepCopy();
    o.addProperty("trust", trustStatus == null ? "external_unverified_reference" : trustStatus);
    o.addProperty(
        "safety_rule",
        "界面映射与操作提示仅供参考，执行仍须检查当前菜单同步、权限边界与服务端实际库存变化。");
    return o;
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
