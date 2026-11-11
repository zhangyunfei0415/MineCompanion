package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
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

  private void button(String s, int x, int y, int w, Runnable action) {
    addRenderableWidget(
        Button.builder(Component.literal(s), b -> action.run()).bounds(x, y, w, 20).build());
  }

  @Override
  protected void init() {
    int x = 16, w = Math.max(240, width - 32);
    search = new EditBox(font, x, 62, w - 112, 20, Component.literal("名称或注册ID"));
    search.setMaxLength(120);
    search.setValue(query);
    search.setResponder(v -> query = v);
    addRenderableWidget(search);
    button(
        "搜索物品",
        width - 120,
        62,
        104,
        () -> {
          offset = 0;
          mode = "items";
          load();
        });
    int bw = Math.max(70, Math.min(110, (width - 44) / 3));
    button(
        "查看做法",
        x,
        88,
        bw,
        () -> {
          offset = 0;
          mode = "output";
          load();
        });
    button(
        "查看用途",
        x + bw + 6,
        88,
        bw,
        () -> {
          offset = 0;
          mode = "input";
          load();
        });
    button(
        "制作计划",
        x + (bw + 6) * 2,
        88,
        bw,
        () -> {
          try {
            minecraft.setScreen(
                new NoticeScreen(this, RecipeText.plan(RecipeKnowledge.plan(query, 1))));
          } catch (Exception e) {
            status = e.getMessage();
          }
        });
    int size = pageSize();
    for (int i = 0; i < rows.size() && i < size; i++) {
      var row = rows.get(i).getAsJsonObject();
      int y = 136 + i * 44;
      if (mode.equals("items"))
        button(
            "选择",
            width - 76,
            y + 5,
            60,
            () -> {
              query = row.get("item").getAsString();
              offset = 0;
              mode = "output";
              load();
            });
      else {
        button(
            "详情",
            width - 132,
            y + 5,
            54,
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
            });
        button(
            "JEI",
            width - 72,
            y + 5,
            56,
            () -> {
              try {
                RecipeBridge.require().show(row.get("token").getAsString());
              } catch (Exception e) {
                status = e.getMessage();
              }
            });
      }
    }
    button("返回面板", 16, height - 28, 95, () -> onClose());
    button(
        "上一页",
        width - 176,
        height - 28,
        74,
        () -> {
          offset = Math.max(0, offset - pageSize());
          load();
        });
    var next =
        Button.builder(
                Component.literal("下一页"),
                b -> {
                  offset += pageSize();
                  load();
                })
            .bounds(width - 96, height - 28, 80, 20)
            .build();
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
    g.fill(0, 0, width, height, 0xff0b1723);
    g.fill(8, 8, width - 8, height - 36, 0xff122938);
    g.fill(16, 50, width - 16, 52, 0xff65cfb3);
    if (width > 360) {
      g.setColor(1, 1, 1, 0.85f);
      g.blit(ART, width - 76, 9, 60, 40, 0, 0, 1536, 1024, 1536, 1024);
      g.setColor(1, 1, 1, 1);
    }
    g.drawString(font, "配方工坊", 18, 16, 0xffe6c886, false);
    g.drawString(font, "材料 · 工序 · 实际核验", 18, 34, 0xffa8c4ce, false);
    g.drawString(
        font,
        font.plainSubstrByWidth(status == null ? "操作失败" : status, width - 34),
        18,
        116,
        0xffa8c4ce,
        false);
    for (int i = 0; i < rows.size() && i < pageSize(); i++) {
      int y = 136 + i * 44;
      var r = rows.get(i).getAsJsonObject();
      g.fill(16, y, width - 16, y + 39, 0xff1a3747);
      g.fill(16, y, 19, y + 39, 0xff76cbb5);
      String label = Agent.str(r, mode.equals("items") ? "name" : "title", "");
      String detail = Agent.str(r, mode.equals("items") ? "item" : "recipe_id", "");
      if (mode.equals("items") && r.has("nbt")) detail += " [NBT变体]";
      int space = width - (mode.equals("items") ? 114 : 170);
      g.drawString(
          font, font.plainSubstrByWidth(label, Math.max(40, space)), 26, y + 7, 0xffedf3e8, false);
      g.drawString(
          font,
          font.plainSubstrByWidth(detail, Math.max(40, space)),
          26,
          y + 23,
          0xff9fbdc9,
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
