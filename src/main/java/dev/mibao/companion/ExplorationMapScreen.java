package dev.mibao.companion;

import static dev.mibao.companion.ExplorationGraph.*;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Height slices and multi-floor overview built from the same evidence used by route planning. */
final class ExplorationMapScreen extends Screen {
  enum ViewMode {
    ALL_Y("全景"),
    BAND("±2层"),
    SINGLE("单层");

    final String label;

    ViewMode(String label) {
      this.label = label;
    }
  }

  private record MapChip(Cell anchor, int layer, int x, int y, int w, int h) {
    boolean contains(double mx, double my) {
      return mx >= x && mx < x + w && my >= y && my < y + h;
    }
  }

  private final Screen previous;
  private final List<MapChip> floorChips = new ArrayList<>();
  private final List<MapChip> anchorChips = new ArrayList<>();
  private ViewMode viewMode = ViewMode.BAND;
  private boolean initialized;
  private boolean autoHeight = true;
  private int layer, zoom = 6;
  private int mapLeft, mapRight, mapTop, mapBottom;
  private int panelX, panelW, ctrlTop, floorsTop, floorsBottom, anchorsTop;
  private double centerX, centerZ;
  private Cell selected, hovered;
  private DeskEditBox name;
  private DeskButton allBtn, bandBtn, singleBtn, followBtn, rename, branch, back;
  private String message = "";

  private long cachedRouteRev = -1;
  private Cell cachedRouteStart, cachedRouteGoal;
  private List<Step> cachedRoute = List.of();

  ExplorationMapScreen(Screen previous) {
    this(previous, null);
  }

  ExplorationMapScreen(Screen previous, Cell focus) {
    super(Component.literal("探索地图"));
    this.previous = previous;
    if (focus != null) {
      this.initialized = true;
      this.autoHeight = false;
      this.centerX = focus.x();
      this.centerZ = focus.z();
      this.layer = focus.y();
      this.selected = focus;
    }
  }

  private void computeLayout() {
    panelW = width >= 500 ? 168 : Math.max(126, width / 3);
    mapLeft = 14;
    mapRight = Math.max(mapLeft + 120, width - panelW - 22);
    mapTop = 30;
    mapBottom = Math.max(mapTop + 60, height - 30);
    panelX = mapRight + 8;
    ctrlTop = Math.max(88, height - 106);
    floorsTop = 76;
    anchorsTop = Math.min(ctrlTop - 22, 118);
    floorsBottom = Math.max(floorsTop + 14, anchorsTop - 6);
  }

  @Override
  protected void init() {
    computeLayout();
    if (!initialized) {
      recenter();
      initialized = true;
    }

    // View mode selector row in right panel
    int w3 = Math.max(30, (panelW - 6) / 3);
    allBtn = segBtn("全景", panelX, 30, w3, () -> { viewMode = ViewMode.ALL_Y; syncModeButtons(); });
    bandBtn =
        segBtn("±2层", panelX + w3 + 3, 30, w3, () -> { viewMode = ViewMode.BAND; syncModeButtons(); });
    singleBtn =
        segBtn(
            "单层",
            panelX + 2 * (w3 + 3),
            30,
            panelW - 2 * (w3 + 3),
            () -> {
              viewMode = ViewMode.SINGLE;
              syncModeButtons();
            });
    syncModeButtons();

    // Slice controls row
    int wSide = Math.max(26, (panelW - 6) / 4);
    int wMid = panelW - 2 * wSide - 6;
    addRenderableWidget(
        new DeskButton("Y−", panelX, 52, wSide, 18, () -> { autoHeight = false; layer--; }));
    followBtn =
        addRenderableWidget(
                new DeskButton(
                    "跟随Y",
                    panelX + wSide + 3,
                    52,
                    wMid,
                    18,
                    () -> {
                      autoHeight = true;
                      recenter();
                    }))
            .variant(DeskButton.Variant.SEGMENT);
    followBtn.selected = autoHeight;
    addRenderableWidget(
        new DeskButton(
            "Y+",
            panelX + wSide + wMid + 6,
            52,
            wSide,
            18,
            () -> {
              autoHeight = false;
              layer++;
            }));

    // Floating zoom/recenter cluster in bottom-right corner of map canvas
    int zY = mapBottom - 23;
    addRenderableWidget(new DeskButton("+", mapRight - 72, zY, 20, 18, () -> zoom = Math.min(20, zoom + 1)));
    addRenderableWidget(new DeskButton("−", mapRight - 49, zY, 20, 18, () -> zoom = Math.max(2, zoom - 1)));
    addRenderableWidget(new DeskButton("⌖", mapRight - 26, zY, 20, 18, this::recenter));

    // Pinned node inspector controls at bottom of right panel
    name =
        new DeskEditBox(font, panelX, ctrlTop + 24, panelW, 18, Component.literal("节点名称"))
            .placeholder("节点名称...");
    name.setMaxLength(64);
    Node cur = selectedNode();
    if (cur != null) name.setValue(cur.name());
    addRenderableWidget(name);

    int halfW = (panelW - 4) / 2;
    rename =
        addRenderableWidget(
            new DeskButton("重命名", panelX, ctrlTop + 46, halfW, 18, this::rename));
    branch =
        addRenderableWidget(
                new DeskButton(
                    "探此岔口", panelX + halfW + 4, ctrlTop + 46, panelW - halfW - 4, 18, () -> action(true)))
            .variant(DeskButton.Variant.PRIMARY);
    back =
        addRenderableWidget(
            new DeskButton("走到此处", panelX, ctrlTop + 68, halfW, 18, () -> action(false)));
    addRenderableWidget(
        new DeskButton("返回", panelX + halfW + 4, ctrlTop + 68, panelW - halfW - 4, 18, this::onClose));
  }

  private DeskButton segBtn(String label, int x, int y, int w, Runnable run) {
    return addRenderableWidget(new DeskButton(label, x, y, w, 18, run).variant(DeskButton.Variant.SEGMENT));
  }

  private void syncModeButtons() {
    if (allBtn != null) allBtn.selected = viewMode == ViewMode.ALL_Y;
    if (bandBtn != null) bandBtn.selected = viewMode == ViewMode.BAND;
    if (singleBtn != null) singleBtn.selected = viewMode == ViewMode.SINGLE;
  }

  private void recenter() {
    if (minecraft.player == null) return;
    var p = minecraft.player.blockPosition();
    centerX = p.getX();
    centerZ = p.getZ();
    layer = p.getY();
    Cell feet = ExplorationMemory.current();
    if (feet != null) layer = feet.y();
  }

  @Override
  public void tick() {
    if (autoHeight && minecraft.player != null) {
      Cell here = ExplorationMemory.current();
      layer = here == null ? minecraft.player.blockPosition().getY() : here.y();
    }
    if (followBtn != null) followBtn.selected = autoHeight;
    syncModeButtons();
    var n = selectedNode();
    name.setEditable(n != null);
    rename.active = n != null;
    branch.active = n != null && n.kind() == Kind.FRONTIER;
    back.active = n != null && n.kind() != Kind.FRONTIER && n.kind() != Kind.HAZARD;
  }

  private Node selectedNode() {
    var graph = ExplorationMemory.graph();
    return graph == null || selected == null ? null : graph.node(selected);
  }

  private void rename() {
    if (selectedNode() == null) return;
    var args = new JsonObject();
    args.addProperty("node_id", selected.id());
    args.addProperty("name", name.getValue());
    message = ExplorationMemory.mark(args);
  }

  private void action(boolean exploring) {
    if (selectedNode() == null) return;
    if (!Agent.enabled || Agent.paused || Cooperation.yielding() || HarnessController.busy()) {
      message = "请先启用接管，暂停或作业结束后再执行。";
      return;
    }
    var args = new JsonObject();
    args.addProperty(exploring ? "frontier_id" : "target", selected.id());
    minecraft.setScreen(null);
    Agent.execute(exploring ? "explore_branch" : "explore_return", args, Companion::notifyUser);
  }

  private int sx(double wx) {
    return (mapLeft + mapRight) / 2 + (int) Math.round((wx - centerX) * zoom);
  }

  private int sz(double wz) {
    return (mapTop + mapBottom) / 2 + (int) Math.round((wz - centerZ) * zoom);
  }

  private boolean inActiveBand(int y) {
    return switch (viewMode) {
      case ALL_Y -> true;
      case BAND -> Math.abs(y - layer) <= 2;
      case SINGLE -> y == layer;
    };
  }

  private boolean inGhostBand(int y) {
    return switch (viewMode) {
      case ALL_Y -> false;
      case BAND -> Math.abs(y - layer) == 3 || Math.abs(y - layer) == 4;
      case SINGLE -> Math.abs(y - layer) == 1;
    };
  }

  private int edgeAlpha(int ay, int by, int baseAlpha) {
    if (viewMode == ViewMode.ALL_Y) {
      int dist = Math.min(Math.abs(ay - layer), Math.abs(by - layer));
      return Math.max(85, baseAlpha - dist * 16);
    }
    if (ay == layer || by == layer) return baseAlpha;
    return Math.max(120, baseAlpha - 65);
  }

  @Override
  public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
    UiTheme theme = UiTheme.current();
    g.fill(0, 0, width, height, theme.backdrop());
    UiDraw.fillRounded(g, 8, 6, width - 16, height - 12, theme.windowBg(), theme.border());
    UiDraw.fillAuroraBar(g, 9, 7, width - 18, 2, theme);

    var map = ExplorationMemory.graph();
    String sliceDesc = viewMode == ViewMode.ALL_Y ? "全景" : viewMode == ViewMode.BAND ? "Y " + (layer - 2) + ".." + (layer + 2) : "Y " + layer;
    String title = "探索地图 · " + sliceDesc + (autoHeight ? " · 跟随" : "");
    g.drawString(font, title, 14, 14, theme.text(), false);
    if (map != null) {
      long fronts = map.nodes().stream().filter(n -> n.kind() == Kind.FRONTIER).count();
      String summary = "节点 " + map.nodes().size() + " · 岔口 " + fronts + " · " + zoom + "x";
      g.drawString(
          font,
          summary,
          Math.max(160, mapRight - font.width(summary) - 2),
          14,
          theme.textSecondary(),
          false);
    }

    UiDraw.fillCard(g, mapLeft - 1, mapTop - 1, mapRight - mapLeft + 2, mapBottom - mapTop + 2, theme);
    hovered = null;
    Cell playerCell =
        minecraft.player != null ? ExplorationMemory.cell(minecraft.player.blockPosition()) : null;
    if (map != null) {
      int halfW = (mapRight - mapLeft) / 2;
      int halfH = (mapBottom - mapTop) / 2;
      int minX = (int) Math.floor(centerX - (double) halfW / zoom) - 2;
      int maxX = (int) Math.ceil(centerX + (double) halfW / zoom) + 2;
      int minZ = (int) Math.floor(centerZ - (double) halfH / zoom) - 2;
      int maxZ = (int) Math.ceil(centerZ + (double) halfH / zoom) + 2;

      g.enableScissor(mapLeft, mapTop, mapRight, mapBottom);

      // 1. 8-block aligned world grid
      int gridColor = UiTheme.withAlpha(theme.border(), theme.light() ? 60 : 46);
      for (int gx = Math.floorDiv(minX, 8) * 8; gx <= maxX; gx += 8) {
        int px = sx(gx);
        if (px >= mapLeft && px < mapRight) g.fill(px, mapTop, px + 1, mapBottom, gridColor);
      }
      for (int gz = Math.floorDiv(minZ, 8) * 8; gz <= maxZ; gz += 8) {
        int pz = sz(gz);
        if (pz >= mapTop && pz < mapBottom) g.fill(mapLeft, pz, mapRight, pz + 1, gridColor);
      }

      // 2. Ghost neighboring layers
      int ghostColor = UiTheme.withAlpha(theme.textMuted(), theme.light() ? 45 : 36);
      for (Edge e : map.edges()) {
        if (e.kind() != EdgeKind.BIDIRECTIONAL) continue;
        for (int i = 1; i < e.cells().size(); i++) {
          Cell a = e.cells().get(i - 1), b = e.cells().get(i);
          if (inActiveBand(a.y()) || inActiveBand(b.y())) continue;
          if (!inGhostBand(a.y()) && !inGhostBand(b.y())) continue;
          if (!inBounds(a, b, minX, maxX, minZ, maxZ)) continue;
          drawStroke(g, sx(a.x()), sz(a.z()), sx(b.x()), sz(b.z()), 0, ghostColor);
        }
      }

      // 3. De-cluttered background pass: OBSERVED edges & small hollow FRONTIER markers
      int frontierColor = UiTheme.withAlpha(theme.warning(), theme.light() ? 185 : 165);
      for (Edge e : map.edges()) {
        if (e.kind() != EdgeKind.BIDIRECTIONAL || e.state() != State.OBSERVED) continue;
        for (int i = 1; i < e.cells().size(); i++) {
          Cell a = e.cells().get(i - 1), b = e.cells().get(i);
          if (!inActiveBand(a.y()) && !inActiveBand(b.y())) continue;
          if (!inBounds(a, b, minX, maxX, minZ, maxZ)) continue;
          drawStroke(g, sx(a.x()), sz(a.z()), sx(b.x()), sz(b.z()), 0, UiTheme.withAlpha(theme.textMuted(), 145));
        }
      }
      for (Node n : map.nodes()) {
        if (n.kind() != Kind.FRONTIER) continue;
        Cell c = n.cell();
        if (!inActiveBand(c.y()) || c.x() < minX || c.x() > maxX || c.z() < minZ || c.z() > maxZ) continue;
        int nx = sx(c.x()), nz = sz(c.z());
        detectHover(c, nx, nz, mouseX, mouseY);
        int alpha = viewMode == ViewMode.ALL_Y ? Math.max(80, 175 - Math.abs(c.y() - layer) * 18) : (c.y() == layer ? 190 : 120);
        int fc = UiTheme.withAlpha(frontierColor, alpha);
        if (zoom >= 5) {
          UiDraw.fillRounded(g, nx - 1, nz - 1, 3, 3, 0, fc);
        } else {
          g.fill(nx, nz, nx + 1, nz + 1, fc);
        }
      }

      // 4. Foreground corridor pass: VERIFIED, mined, STALE/BLOCKED, stairs & directional links ON TOP
      int pad = zoom >= 9 ? 1 : 0;
      for (Edge e : map.edges()) {
        if (e.kind() != EdgeKind.BIDIRECTIONAL) {
          Cell f = e.from();
          if (inActiveBand(f.y()) && f.x() >= minX && f.x() <= maxX && f.z() >= minZ && f.z() <= maxZ) {
            int fx = sx(f.x()), fz = sz(f.z());
            int dc = e.kind() == EdgeKind.WARP_TRANSITION ? theme.accentAlt() : theme.warning();
            // Distinct directional diamond / drop glyph
            g.fill(fx - 2, fz - 2, fx + 3, fz + 3, UiTheme.withAlpha(dc, 70));
            g.fill(fx + 2, fz - 3, fx + 4, fz + 3, dc);
            g.fill(fx + 1, fz + 1, fx + 5, fz + 2, dc);
          }
          continue;
        }
        if (e.state() == State.OBSERVED) continue;
        int baseColor = switch (e.state()) {
          case VERIFIED -> e.mined_segment() ? theme.accentAlt() : theme.success();
          case STALE, BLOCKED -> theme.danger();
          default -> theme.textMuted();
        };
        for (int i = 1; i < e.cells().size(); i++) {
          Cell a = e.cells().get(i - 1), b = e.cells().get(i);
          if (!inActiveBand(a.y()) && !inActiveBand(b.y())) continue;
          if (!inBounds(a, b, minX, maxX, minZ, maxZ)) continue;
          int c = UiTheme.withAlpha(baseColor, edgeAlpha(a.y(), b.y(), 245));
          if (a.y() != b.y()) {
            Cell stair = inActiveBand(a.y()) ? a : b;
            int rx = sx(stair.x()), rz = sz(stair.z());
            g.fill(rx - 2, rz - 2, rx + 3, rz + 3, theme.accentAlt());
            g.fill(rx - 1, rz - 1, rx + 2, rz + 2, theme.windowBg());
            g.fill(rx, rz, rx + 2, rz + 1, theme.accentAlt());
          } else {
            drawStroke(g, sx(a.x()), sz(a.z()), sx(b.x()), sz(b.z()), pad, c);
            if (e.state() == State.BLOCKED && i == 1) {
              int mxp = (sx(a.x()) + sx(b.x())) / 2, mzp = (sz(a.z()) + sz(b.z())) / 2;
              g.fill(mxp - 2, mzp - 2, mxp + 3, mzp + 3, theme.danger());
            }
          }
        }
      }

      // 5. Verified route preview from player stance to selected node
      List<Step> preview = resolvePreviewRoute(map, playerCell);
      for (int i = 0; i < preview.size(); i++) {
        Step st = preview.get(i);
        if (!inActiveBand(st.from().y()) && !inActiveBand(st.to().y())) continue;
        if (!inBounds(st.from(), st.to(), minX, maxX, minZ, maxZ)) continue;
        drawStroke(g, sx(st.from().x()), sz(st.from().z()), sx(st.to().x()), sz(st.to().z()), pad + 1, UiTheme.withAlpha(theme.accent(), 220));
      }

      // 6. Meaningful landmarks & named nodes ONLY (omit generic plain PATH clutter)
      for (Node n : map.nodes()) {
        if (n.kind() == Kind.FRONTIER) continue;
        if (n.kind() == Kind.PATH && n.name().isBlank() && !n.cell().equals(selected)) continue;
        Cell c = n.cell();
        if (!inActiveBand(c.y()) || c.x() < minX || c.x() > maxX || c.z() < minZ || c.z() > maxZ) continue;
        int nx = sx(c.x()), nz = sz(c.z());
        detectHover(c, nx, nz, mouseX, mouseY);
        int color = switch (n.kind()) {
          case SAFE_POINT, ENTRY -> theme.success();
          case HAZARD -> theme.danger();
          case WORK_FACE, STAIRS -> theme.accentAlt();
          default -> theme.text();
        };
        boolean isSel = c.equals(selected);
        if (n.kind() == Kind.HAZARD) {
          g.fill(nx - 2, nz - 2, nx + 3, nz + 3, theme.danger());
        } else if (n.kind() == Kind.ENTRY || n.kind() == Kind.SAFE_POINT) {
          int r = isSel ? 4 : 3;
          UiDraw.fillRounded(g, nx - r, nz - r, r * 2 + 1, r * 2 + 1, color, theme.windowBg());
        } else {
          int r = isSel ? 3 : 2;
          g.fill(nx - r, nz - r, nx + r + 1, nz + r + 1, color);
        }
        if (isSel) {
          UiDraw.fillRounded(g, nx - 5, nz - 5, 11, 11, 0, theme.accent());
        }
        if (zoom >= 5 && !n.name().isBlank()) {
          String lbl = font.plainSubstrByWidth(n.name(), 88);
          int lw = font.width(lbl);
          g.fill(nx + 5, nz - 5, nx + 9 + lw, nz + 6, UiTheme.withAlpha(theme.windowBg(), 210));
          g.drawString(font, lbl, nx + 7, nz - 3, theme.text(), false);
        }
      }

      if (hovered != null && !hovered.equals(selected)) {
        int hx = sx(hovered.x()), hz = sz(hovered.z());
        UiDraw.fillRounded(g, hx - 5, hz - 5, 11, 11, 0, theme.borderStrong());
      }

      // 7. Player marker + heading pointer
      if (playerCell != null && inActiveBand(playerCell.y())) {
        int px = sx(playerCell.x()), pz = sz(playerCell.z());
        double yawRad = Math.toRadians(minecraft.player.getYRot());
        int dirX = (int) Math.round(-Math.sin(yawRad) * 6.0);
        int dirZ = (int) Math.round(Math.cos(yawRad) * 6.0);
        g.fill(px + dirX - 1, pz + dirZ - 1, px + dirX + 2, pz + dirZ + 2, theme.accentAlt());
        g.fill(px + dirX / 2 - 1, pz + dirZ / 2 - 1, px + dirX / 2 + 2, pz + dirZ / 2 + 2, theme.accent());
        UiDraw.fillRounded(g, px - 4, pz - 4, 9, 9, theme.accent(), theme.textOnAccent());
        g.fill(px - 1, pz - 1, px + 2, pz + 2, theme.textOnAccent());
      }

      // 8. Canvas overlays: top-right compass & bottom-left ruler + hover world coordinates
      UiDraw.drawBadge(
          g,
          font,
          mapRight - 24,
          mapTop + 5,
          "N↑",
          theme.textSecondary(),
          UiTheme.withAlpha(theme.cardBg(), 215),
          theme.border());

      int scaleBlocks = zoom >= 10 ? 4 : zoom >= 5 ? 8 : 16;
      int scalePx = scaleBlocks * zoom;
      int barX = mapLeft + 8, barY = mapBottom - 11;
      g.fill(barX - 2, barY - 10, barX + scalePx + 110, barY + 5, UiTheme.withAlpha(theme.windowBg(), 180));
      g.fill(barX, barY, barX + scalePx, barY + 2, theme.textSecondary());
      g.fill(barX, barY - 3, barX + 1, barY + 3, theme.textSecondary());
      g.fill(barX + scalePx - 1, barY - 3, barX + scalePx, barY + 3, theme.textSecondary());
      String coordInfo = scaleBlocks + " 格";
      if (onMap(mouseX, mouseY)) {
        long wx = Math.round(centerX + (mouseX - (mapLeft + mapRight) / 2.0) / zoom);
        long wz = Math.round(centerZ + (mouseY - (mapTop + mapBottom) / 2.0) / zoom);
        coordInfo += " · (" + wx + ", " + wz + ")";
      }
      g.drawString(font, coordInfo, barX + scalePx + 6, barY - 6, theme.textSecondary(), false);

      g.disableScissor();
    } else {
      g.drawCenteredString(font, "进入世界后自动采集并展示探索记忆", (mapLeft + mapRight) / 2, (mapTop + mapBottom) / 2 - 4, theme.textMuted());
    }

    renderLegend(g, theme);
    renderRightInspector(g, theme, map);

    super.render(g, mouseX, mouseY, delta);
  }

  private void detectHover(Cell c, int nx, int nz, int mouseX, int mouseY) {
    if (!onMap(mouseX, mouseY)) return;
    double dx = nx - mouseX, dz = nz - mouseY;
    double distSq = dx * dx + dz * dz;
    if (distSq < 81) {
      if (hovered == null) {
        hovered = c;
      } else {
        double prevDx = sx(hovered.x()) - mouseX, prevDz = sz(hovered.z()) - mouseY;
        if (distSq < prevDx * prevDx + prevDz * prevDz) hovered = c;
      }
    }
  }

  private List<Step> resolvePreviewRoute(ExplorationGraph map, Cell playerCell) {
    if (selected == null || playerCell == null) return List.of();
    if (cachedRouteRev == map.revision()
        && selected.equals(cachedRouteGoal)
        && playerCell.equals(cachedRouteStart)) {
      return cachedRoute;
    }
    cachedRouteRev = map.revision();
    cachedRouteStart = playerCell;
    cachedRouteGoal = selected;
    cachedRoute = map.route(playerCell, Set.of(selected));
    return cachedRoute;
  }

  private void drawStroke(GuiGraphics g, int x0, int z0, int x1, int z1, int pad, int color) {
    g.fill(
        Math.min(x0, x1) - pad,
        Math.min(z0, z1) - pad,
        Math.max(x0, x1) + pad + 1,
        Math.max(z0, z1) + pad + 1,
        color);
  }

  private void renderRightInspector(GuiGraphics g, UiTheme theme, ExplorationGraph map) {
    floorChips.clear();
    anchorChips.clear();

    // Floor chips section
    g.drawString(font, "楼层切片", panelX, floorsTop - 2, theme.textMuted(), false);
    if (map != null && !map.nodes().isEmpty()) {
      List<Integer> layers = map.layers();
      int curIdx = layers.indexOf(layer);
      int start = 0;
      if (curIdx >= 0 && layers.size() > 8) {
        start = Math.max(0, Math.min(layers.size() - 8, curIdx - 3));
      }
      int cx = panelX;
      int cy = floorsTop + 10;
      int maxRowY = Math.max(cy, floorsBottom - 13);
      for (int i = start; i < layers.size(); i++) {
        int yVal = layers.get(i);
        String txt = Integer.toString(yVal);
        int cw = Math.max(24, UiDraw.badgeWidth(font, txt));
        if (cx + cw > panelX + panelW) {
          cx = panelX;
          cy += 15;
          if (cy > maxRowY) break;
        }
        boolean active = inActiveBand(yVal);
        boolean exact = yVal == layer;
        UiDraw.drawBadge(
            g,
            font,
            cx,
            cy,
            txt,
            exact ? theme.textOnAccent() : (active ? theme.text() : theme.textSecondary()),
            exact
                ? theme.accent()
                : active ? UiTheme.withAlpha(theme.accent(), 48) : theme.inputBg(),
            exact ? theme.accent() : theme.border());
        floorChips.add(new MapChip(null, yVal, cx, cy, cw, 13));
        cx += cw + 3;
      }
    }

    // Landmark quick-jump chips (when height allows)
    if (map != null && anchorsTop + 26 <= ctrlTop) {
      g.drawString(font, "快捷定位", panelX, anchorsTop, theme.textMuted(), false);
      Cell here = ExplorationMemory.current();
      List<Node> anchors =
          map.nodes().stream()
              .filter(n -> n.kind() == Kind.ENTRY || n.kind() == Kind.SAFE_POINT || n.kind() == Kind.WORK_FACE || !n.name().isBlank())
              .sorted(Comparator.comparingLong(n -> here == null ? 0L : n.cell().distanceSquared(here)))
              .limit(6)
              .toList();
      int ax = panelX, ay = anchorsTop + 12;
      int maxAy = ctrlTop - 16;
      for (Node an : anchors) {
        String label = an.name().isBlank() ? kind(an.kind()) : an.name();
        label = font.plainSubstrByWidth(label, Math.max(36, panelW - 16));
        int aw = UiDraw.badgeWidth(font, label);
        if (ax + aw > panelX + panelW && ax > panelX) {
          ax = panelX;
          ay += 15;
        }
        if (ay > maxAy) break;
        boolean sel = an.cell().equals(selected);
        UiDraw.drawBadge(
            g,
            font,
            ax,
            ay,
            label,
            sel ? theme.textOnAccent() : theme.text(),
            sel ? theme.accent() : theme.cardBg(),
            sel ? theme.accent() : theme.borderStrong());
        anchorChips.add(new MapChip(an.cell(), an.cell().y(), ax, ay, aw, 13));
        ax += aw + 4;
      }
    }

    // Node detail card summary above DeskEditBox
    Node infoNode = selectedNode();
    if (infoNode == null && map != null && hovered != null) infoNode = map.node(hovered);
    UiDraw.fillCard(g, panelX - 2, ctrlTop - 2, panelW + 4, height - ctrlTop - 6, theme);
    String line1 =
        infoNode == null
            ? "点击地图节点查看详情"
            : kind(infoNode.kind()) + (infoNode.name().isBlank() ? "" : " · " + infoNode.name());
    String line2 =
        infoNode == null
            ? "拖动平移 · 滚轮缩放"
            : infoNode.cell().id()
                + (infoNode.landmark_hints().isEmpty()
                    ? ""
                    : " · " + String.join("、", infoNode.landmark_hints()));
    g.drawString(font, font.plainSubstrByWidth(line1, panelW), panelX, ctrlTop + 2, theme.text(), false);
    g.drawString(font, font.plainSubstrByWidth(line2, panelW), panelX, ctrlTop + 13, theme.textSecondary(), false);
    if (!message.isEmpty()) {
      g.drawString(font, font.plainSubstrByWidth(message, panelW), panelX, ctrlTop + 90, theme.warning(), false);
    }
  }

  private void renderLegend(GuiGraphics g, UiTheme theme) {
    int y = mapBottom + 6;
    int x = mapLeft;
    x = legendSwatch(g, x, y, theme.success(), "已走通/入口", theme);
    x = legendSwatch(g, x, y, theme.accentAlt(), "矿道/楼梯/跃迁", theme);
    x = legendSwatch(g, x, y, theme.accent(), "路线/玩家", theme);
    x = legendSwatch(g, x, y, theme.warning(), "未探开口", theme);
    legendSwatch(g, x, y, theme.danger(), "受阻/危险", theme);
  }

  private int legendSwatch(GuiGraphics g, int x, int y, int color, String label, UiTheme t) {
    int lw = font.width(label);
    if (x + 12 + lw > mapRight) return x;
    g.fill(x, y + 1, x + 6, y + 7, color);
    g.drawString(font, label, x + 8, y, t.textSecondary(), false);
    return x + 16 + lw;
  }

  private static boolean inBounds(Cell a, Cell b, int minX, int maxX, int minZ, int maxZ) {
    return !(Math.max(a.x(), b.x()) < minX
        || Math.min(a.x(), b.x()) > maxX
        || Math.max(a.z(), b.z()) < minZ
        || Math.min(a.z(), b.z()) > maxZ);
  }

  private static String kind(Kind kind) {
    return switch (kind) {
      case PATH -> "路标";
      case ENTRY -> "入口";
      case SAFE_POINT -> "安全点";
      case JUNCTION -> "岔路口";
      case STAIRS -> "上下层";
      case WORK_FACE -> "工作面";
      case HAZARD -> "危险点";
      case FRONTIER -> "未探开口";
    };
  }

  private boolean onMap(double x, double y) {
    return x >= mapLeft && x < mapRight && y >= mapTop && y < mapBottom;
  }

  @Override
  public boolean mouseClicked(double x, double y, int button) {
    if (super.mouseClicked(x, y, button)) return true;
    if (button == 0) {
      for (MapChip chip : floorChips) {
        if (chip.contains(x, y)) {
          autoHeight = false;
          layer = chip.layer();
          return true;
        }
      }
      for (MapChip chip : anchorChips) {
        if (chip.contains(x, y) && chip.anchor() != null) {
          autoHeight = false;
          selected = chip.anchor();
          centerX = selected.x();
          centerZ = selected.z();
          layer = selected.y();
          Node n = selectedNode();
          name.setValue(n == null ? "" : n.name());
          message = "";
          return true;
        }
      }
    }
    var map = ExplorationMemory.graph();
    if (map != null && button == 0 && onMap(x, y)) {
      double bestScore = 100;
      selected = null;
      for (Node n : map.nodes()) {
        Cell c = n.cell();
        if (!inActiveBand(c.y())) continue;
        double dx = sx(c.x()) - x, dz = sz(c.z()) - y;
        double distSq = dx * dx + dz * dz;
        if (distSq >= 81) continue;
        double penalty = (n.kind() == Kind.PATH && n.name().isBlank()) ? 18 : (n.kind() == Kind.FRONTIER ? 9 : 0);
        double score = distSq + penalty;
        if (score < bestScore) {
          selected = c;
          bestScore = score;
        }
      }
      setFocused(null);
      Node n = selectedNode();
      name.setValue(n == null ? "" : n.name());
      message = "";
      return true;
    }
    return false;
  }

  @Override
  public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
    if (button == 0 && onMap(x, y)) {
      centerX -= dx / zoom;
      centerZ -= dy / zoom;
      return true;
    }
    return super.mouseDragged(x, y, button, dx, dy);
  }

  @Override
  public boolean mouseScrolled(double x, double y, double delta) {
    if (onMap(x, y)) {
      zoom = Math.max(2, Math.min(20, zoom + (delta > 0 ? 1 : -1)));
      return true;
    }
    return super.mouseScrolled(x, y, delta);
  }

  @Override
  public void onClose() {
    minecraft.setScreen(previous);
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }
}
