package dev.mibao.companion;

import com.google.gson.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Local recipe desk. Human clicks query or display definitions, never start production. */
public final class RecipeBrowserScreen extends Screen {
  private final Screen parent;
  private EditBox search;
  private String query = "minecraft:iron_ingot", mode = "items", status = "按名称或ID搜索物品，再查看做法或用途";
  private int offset;
  private JsonArray rows = new JsonArray();
  private boolean more;
  private static final ResourceLocation ART =
      new ResourceLocation(Companion.ID, "textures/gui/workshop-077.png");

  public RecipeBrowserScreen(Screen parent) {
    super(Component.literal("配方工坊"));
    this.parent = parent;
  }

  @Override
  protected void init() {
    int x = 20, w = Math.max(240, width - 40);
    search =
        new DeskEditBox(font, x, 62, w - 112, 20, Component.literal("名称或注册ID"))
            .placeholder("名称或注册ID...");
    search.setMaxLength(120);
    search.setValue(query);
    search.setResponder(v -> query = v);
    addRenderableWidget(search);
    addRenderableWidget(
        new DeskButton(
                "搜索物品",
                width - 124,
                62,
                104,
                20,
                () -> {
                  offset = 0;
                  mode = "items";
                  load();
                })
            .variant(DeskButton.Variant.PRIMARY));
    int bw = Math.max(70, Math.min(115, (width - 52) / 3));
    var outBtn =
        new DeskButton(
                "查看做法",
                x,
                88,
                bw,
                20,
                () -> {
                  offset = 0;
                  mode = "output";
                  load();
                })
            .variant(DeskButton.Variant.SEGMENT);
    outBtn.selected = "output".equals(mode);
    addRenderableWidget(outBtn);

    var inBtn =
        new DeskButton(
                "查看用途",
                x + bw + 6,
                88,
                bw,
                20,
                () -> {
                  offset = 0;
                  mode = "input";
                  load();
                })
            .variant(DeskButton.Variant.SEGMENT);
    inBtn.selected = "input".equals(mode);
    addRenderableWidget(inBtn);

    addRenderableWidget(
        new DeskButton(
            "制作计划",
            x + (bw + 6) * 2,
            88,
            bw,
            20,
            () -> {
              try {
                minecraft.setScreen(
                    new NoticeScreen(this, RecipeText.plan(RecipeKnowledge.plan(query, 1))));
              } catch (Exception e) {
                status = e.getMessage();
              }
            }));

    int size = pageSize();
    for (int i = 0; i < rows.size() && i < size; i++) {
      var row = rows.get(i).getAsJsonObject();
      int y = 136 + i * 44;
      if (mode.equals("items"))
        addRenderableWidget(
            new DeskButton(
                "选择",
                width - 84,
                y + 8,
                60,
                22,
                () -> {
                  query = row.get("item").getAsString();
                  offset = 0;
                  mode = "output";
                  load();
                }));
      else {
        addRenderableWidget(
            new DeskButton(
                "详情",
                width - 140,
                y + 8,
                54,
                22,
                () -> {
                  try {
                    minecraft.setScreen(
                        new NoticeScreen(
                            this,
                            RecipeText.details(
                                RecipeBridge.require().details(row.get("token").getAsString()))));
                  } catch (Exception e) {
                    status = e.getMessage();
                  }
                }));
        addRenderableWidget(
            new DeskButton(
                    "JEI",
                    width - 80,
                    y + 8,
                    56,
                    22,
                    () -> {
                      try {
                        RecipeBridge.require().show(row.get("token").getAsString());
                      } catch (Exception e) {
                        status = e.getMessage();
                      }
                    })
                .variant(DeskButton.Variant.PRIMARY));
      }
    }
    addRenderableWidget(new DeskButton("返回面板", 20, height - 30, 96, 22, this::onClose));
    var prev =
        new DeskButton(
            "上一页",
            width - 186,
            height - 30,
            76,
            22,
            () -> {
              offset = Math.max(0, offset - pageSize());
              load();
            });
    prev.active = offset > 0;
    addRenderableWidget(prev);
    var next =
        new DeskButton(
            "下一页",
            width - 102,
            height - 30,
            82,
            22,
            () -> {
              offset += pageSize();
              load();
            });
    next.active = more;
    addRenderableWidget(next);
  }

  private int pageSize() {
    return Math.max(1, Math.min(10, (height - 178) / 44));
  }

  private void load() {
    try {
      JsonObject data =
          mode.equals("items")
              ? RecipeBridge.require().items(query, offset, pageSize())
              : RecipeBridge.require().lookup(query, mode, "", offset, pageSize());
      rows = data.getAsJsonArray(mode.equals("items") ? "items" : "recipes");
      more = data.get("has_more").getAsBoolean();
      status =
          rows.isEmpty() ? "未找到；可换关键词，或检查JEI是否就绪" : "第 " + (offset + 1) + " 项起 · 配方是参考定义，生产需单独核验";
    } catch (Exception | LinkageError e) {
      rows = new JsonArray();
      more = false;
      status = Rules.clean(e.getMessage(), 200);
    }
    rebuildWidgets();
  }

  @Override
  public void render(GuiGraphics g, int mx, int my, float partial) {
    UiTheme t = UiTheme.current();
    g.fill(0, 0, width, height, t.backdrop());
    UiDraw.fillRounded(g, 10, 8, width - 20, height - 16, t.windowBg(), t.border());
    UiDraw.fillAuroraBar(g, 11, 9, width - 22, 2, t);

    if (width > 360) {
      g.setColor(1, 1, 1, 0.85f);
      g.blit(ART, width - 82, 13, 60, 38, 0, 0, 1536, 1024, 1536, 1024);
      g.setColor(1, 1, 1, 1);
    }
    g.drawString(font, "配方工坊 · Recipe Studio", 20, 18, t.accent(), false);
    g.drawString(font, "材料 · 工序 · 实际核验", 20, 34, t.textSecondary(), false);
    g.fill(18, 52, width - 18, 53, t.border());
    g.drawString(
        font,
        font.plainSubstrByWidth(status == null ? "操作失败" : status, width - 40),
        20,
        116,
        t.textSecondary(),
        false);
    for (int i = 0; i < rows.size() && i < pageSize(); i++) {
      int y = 136 + i * 44;
      var r = rows.get(i).getAsJsonObject();
      UiDraw.fillCard(g, 18, y, width - 36, 39, t);
      g.fill(18, y + 3, 21, y + 36, t.accent());
      String label = Agent.str(r, mode.equals("items") ? "name" : "title", "");
      String detail = Agent.str(r, mode.equals("items") ? "item" : "recipe_id", "");
      if (mode.equals("items") && r.has("nbt")) detail += " [NBT变体]";
      int space = width - (mode.equals("items") ? 124 : 180);
      g.drawString(
          font, font.plainSubstrByWidth(label, Math.max(40, space)), 28, y + 7, t.text(), false);
      g.drawString(
          font,
          font.plainSubstrByWidth(detail, Math.max(40, space)),
          28,
          y + 23,
          t.textSecondary(),
          false);
    }
    super.render(g, mx, my, partial);
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }

  @Override
  public void onClose() {
    minecraft.setScreen(parent);
  }
}
