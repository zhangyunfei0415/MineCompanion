package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import mezz.jei.api.*;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.*;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

/** Optional public JEI 15 recipe query and explicitly requested native transfer bridge. */
@JeiPlugin
public final class JeiCompanionPlugin implements IModPlugin, RecipeBridge.Provider {
  private IJeiRuntime runtime;
  private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
  private long generation;
  private Object level, recipes;

  private record Entry(
      IRecipeCategory category, Object recipe, long at, Object level, Object recipes) {}

  @Override
  public ResourceLocation getPluginUid() {
    return new ResourceLocation(Companion.ID, "recipes");
  }

  @Override
  public void onRuntimeAvailable(IJeiRuntime r) {
    runtime = r;
    invalidate();
    RecipeBridge.available(this);
  }

  @Override
  public void onRuntimeUnavailable() {
    runtime = null;
    invalidate();
    RecipeBridge.unavailable();
  }

  @Override
  public void invalidate() {
    entries.clear();
    generation++;
    level = Minecraft.getInstance().level;
    recipes = level == null ? null : Minecraft.getInstance().level.getRecipeManager();
  }

  private void check() {
    var mc = Minecraft.getInstance();
    if (runtime == null || mc.level == null) throw new IllegalArgumentException("请进入世界并等待JEI就绪");
    if (level != mc.level || recipes != mc.level.getRecipeManager()) invalidate();
  }

  @Override
  public JsonObject status() {
    var o = new JsonObject();
    o.addProperty("ready", runtime != null);
    o.addProperty("source", "JEI public API 15");
    o.addProperty("generation", generation);
    o.addProperty("recipe_tokens_expire_seconds", 120);
    o.addProperty("recipe_transfer", true);
    o.addProperty("transfer_note", "按当前菜单/配方检查处理器；支持查询不等于每个类别都能转移");
    o.addProperty("quantity_evidence", "recipe definitions only; not inventory or execution proof");
    return o;
  }

  static JsonObject stack(ItemStack s) {
    var o = new JsonObject();
    o.addProperty("item", BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
    o.addProperty("name", s.getHoverName().getString());
    o.addProperty("count", s.getCount());
    o.addProperty("fingerprint", WorldSense.fingerprint(s));
    if (s.hasTag()) o.addProperty("nbt", s.getTag().toString());
    return o;
  }

  @Override
  public JsonObject items(String query, int offset, int limit) {
    check();
    offset = RecipeBridge.offset(offset);
    limit = RecipeBridge.limit(limit);
    String q = Rules.clean(query, 100).toLowerCase(Locale.ROOT);
    var result = new JsonArray();
    int matches = 0;
    boolean more = false;
    for (var s : runtime.getIngredientManager().getAllItemStacks()) {
      String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
      if (!q.isBlank()
          && !id.toLowerCase(Locale.ROOT).contains(q)
          && !s.getHoverName().getString().toLowerCase(Locale.ROOT).contains(q)) continue;
      if (matches++ < offset) continue;
      if (result.size() >= limit) {
        more = true;
        break;
      }
      result.add(stack(s));
    }
    var o = new JsonObject();
    o.addProperty("source", "JEI registered item variants");
    o.add("items", result);
    o.addProperty("offset", offset);
    o.addProperty("has_more", more);
    return o;
  }

  @Override
  public JsonObject lookup(String item, String role, String category, int offset, int limit) {
    check();
    offset = RecipeBridge.offset(offset);
    limit = RecipeBridge.limit(limit);
    if (!Set.of("input", "output").contains(role))
      throw new IllegalArgumentException("role为input或output");
    var id = new ResourceLocation(item);
    if (!BuiltInRegistries.ITEM.containsKey(id))
      throw new IllegalArgumentException("未知物品ID，先search_items查找");
    var focus =
        runtime
            .getJeiHelpers()
            .getFocusFactory()
            .createFocus(
                role.equals("input") ? RecipeIngredientRole.INPUT : RecipeIngredientRole.OUTPUT,
                VanillaTypes.ITEM_STACK,
                new ItemStack(BuiltInRegistries.ITEM.get(id)));
    var focuses = new ArrayList<IFocus<?>>();
    focuses.add(focus);
    for (var variant : runtime.getIngredientManager().getAllItemStacks())
      if (focuses.size() < 9 && BuiltInRegistries.ITEM.getKey(variant.getItem()).equals(id))
        focuses.add(
            runtime
                .getJeiHelpers()
                .getFocusFactory()
                .createFocus(
                    role.equals("input") ? RecipeIngredientRole.INPUT : RecipeIngredientRole.OUTPUT,
                    VanillaTypes.ITEM_STACK,
                    variant.copy()));
    var manager = runtime.getRecipeManager();
    var out = new JsonArray();
    var errors = new JsonArray();
    int matched = 0;
    boolean more = false;
    int categories = 0;
    for (var c :
        manager
            .createRecipeCategoryLookup()
            .get()
            .sorted(
                Comparator.comparingInt(
                    c ->
                        c.getRecipeType().getUid().getNamespace().equals(id.getNamespace())
                            ? 0
                            : 1))
            .toList()) {
      if (++categories > 256) {
        errors.add("分类预算256已到达，结果截断");
        break;
      }
      if (!category.isBlank()
          && !c.getRecipeType().getUid().toString().equals(category)
          && !c.getRecipeType().getUid().toString().contains(category)) continue;
      try (var stream = manager.createRecipeLookup(c.getRecipeType()).limitFocus(focuses).get()) {
        var it = stream.iterator();
        while (it.hasNext()) {
          Object r = it.next();
          if (matched++ < offset) continue;
          if (out.size() >= limit) {
            more = true;
            break;
          }
          String token = "jei:" + generation + ":" + UUID.randomUUID();
          entries.put(token, new Entry(c, r, System.currentTimeMillis(), level, recipes));
          while (entries.size() > 128) entries.remove(entries.keySet().iterator().next());
          var row = new JsonObject();
          row.addProperty("token", token);
          row.addProperty("category", c.getRecipeType().getUid().toString());
          row.addProperty("title", c.getTitle().getString());
          row.addProperty("recipe_id", registry(c, r));
          row.addProperty("source", "JEI definition; use recipe_details for slots and conditions");
          out.add(row);
        }
      } catch (RuntimeException | LinkageError e) {
        errors.add(c.getRecipeType().getUid() + ": " + e.getClass().getSimpleName());
      }
      if (more) break;
    }
    var o = new JsonObject();
    o.add("recipes", out);
    o.add("errors", errors);
    o.addProperty("has_more", more);
    o.addProperty("offset", offset);
    var names = new JsonArray();
    manager
        .createRecipeCategoryLookup()
        .get()
        .limit(256)
        .forEach(c -> names.add(c.getRecipeType().getUid().toString()));
    o.add("available_categories", names);
    if (out.isEmpty() && role.equals("output")) {
      o.add("client_recipe_fallback", RecipeKnowledge.lookup(item, role, offset, limit));
    }
    o.addProperty(
        "variant_matching",
        "JEI item focus; inspect every alternative fingerprint before using NBT-sensitive items");
    return o;
  }

  @SuppressWarnings("unchecked")
  private static String registry(IRecipeCategory c, Object r) {
    var id = c.getRegistryName(r);
    return id == null ? "" : id.toString();
  }

  private Entry entry(String token) {
    check();
    var e = entries.get(token);
    if (e == null
        || e.level != level
        || e.recipes != recipes
        || System.currentTimeMillis() - e.at > 120000)
      throw new IllegalArgumentException("配方token已失效，请重新lookup_recipes，不重复旧操作");
    return e;
  }

  @Override
  @SuppressWarnings("unchecked")
  public JsonObject details(String token) {
    var e = entry(token);
    var o = new JsonObject();
    o.addProperty("token", token);
    o.addProperty("recipe_id", registry(e.category, e.recipe));
    o.addProperty("category", e.category.getRecipeType().getUid().toString());
    o.addProperty("title", e.category.getTitle().getString());
    var catalysts = new JsonArray();
    runtime
        .getRecipeManager()
        .createRecipeCatalystLookup(e.category.getRecipeType())
        .getItemStack()
        .limit(24)
        .forEach(s -> catalysts.add(stack(s)));
    o.add("workstations", catalysts);
    var slots = new JsonArray();
    var layout =
        runtime
            .getRecipeManager()
            .createRecipeLayoutDrawable(
                e.category,
                e.recipe,
                runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup());
    if (layout.isEmpty()) throw new IllegalArgumentException("JEI无法构建该配方布局，请查看游戏配方页");
    var views =
        ((mezz.jei.api.gui.IRecipeLayoutDrawable<?>) layout.get())
            .getRecipeSlotsView()
            .getSlotViews();
    boolean truncated = views.size() > 64;
    int n = 0;
    for (var s : views) {
      if (n++ >= 64) break;
      var slot = new JsonObject();
      slot.addProperty("role", s.getRole().name());
      slot.addProperty("name", s.getSlotName().orElse(""));
      var alternatives = new JsonArray();
      var ingredients = s.getAllIngredients().limit(129).toList();
      if (ingredients.size() > 128) truncated = true;
      ingredients.stream().limit(128).forEach(i -> alternatives.add(ingredient(i)));
      slot.add("alternatives", alternatives);
      slots.add(slot);
    }
    o.add("slots", slots);
    o.addProperty("truncated", truncated);
    o.addProperty("slot_indices", "JEI recipe display slots; NOT machine menu slot IDs");
    o.addProperty(
        "conditions",
        "category-specific time, heat, energy, chance and non-consumed tools may not be exposed by"
            + " JEI slots; unknown unless explicit adapter data below");
    o.add("client_recipe", RecipeKnowledge.byId(registry(e.category, e.recipe)));
    o.addProperty(
        "execution",
        "Only supported ordinary crafting/smelting adapters may execute. Other machines require"
            + " current GUI observation and a verified skill. No automatic generic transfer.");
    return o;
  }

  private <T> JsonObject ingredient(ITypedIngredient<T> typed) {
    Object value = typed.getIngredient();
    if (value instanceof ItemStack s) return stack(s);
    var o = new JsonObject();
    o.addProperty("type", typed.getType().getUid());
    if (value instanceof FluidStack f) {
      o.addProperty("fluid", BuiltInRegistries.FLUID.getKey(f.getFluid()).toString());
      o.addProperty("amount_mb", f.getAmount());
      if (f.hasTag()) o.addProperty("nbt", f.getTag().toString());
    } else {
      var helper = runtime.getIngredientManager().getIngredientHelper(typed.getType());
      o.addProperty("name", Rules.clean(helper.getDisplayName(typed.getIngredient()), 200));
      o.addProperty("amount_known", false);
    }
    return o;
  }

  @Override
  @SuppressWarnings("unchecked")
  public String show(String token) {
    var e = entry(token);
    runtime.getRecipesGui().showRecipes(e.category, List.of(e.recipe), List.of());
    return "已打开JEI配方页；仅展示，尚未投料或生产。close_screen可退出。";
  }

  @Override
  @SuppressWarnings({"unchecked", "rawtypes"})
  public JsonObject transfer(String token, boolean execute, boolean max) {
    var e = entry(token);
    var mc = Minecraft.getInstance();
    if (!(mc.screen
        instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>))
      throw new IllegalArgumentException(
          "[TRANSFER_CONTEXT] 先打开工作台或AE样板终端；show_recipe只是配方页，不是转移目标");
    if (!mc.player.containerMenu.getCarried().isEmpty())
      throw new IllegalArgumentException("[CURSOR_OCCUPIED] 配方转移前收好鼠标物品");
    var handler =
        runtime
            .getRecipeTransferManager()
            .getRecipeTransferHandler(mc.player.containerMenu, e.category);
    var o = new JsonObject();
    o.addProperty("token", token);
    o.addProperty("category", e.category.getRecipeType().getUid().toString());
    o.addProperty("target_menu", mc.player.containerMenu.getClass().getName());
    o.addProperty("supported", handler.isPresent());
    o.addProperty("dispatched", false);
    if (handler.isEmpty()) {
      o.addProperty("reason", "当前菜单与此配方没有JEI转移处理器；用已验证技能或gui_slot_target");
      return o;
    }
    var layout =
        runtime
            .getRecipeManager()
            .createRecipeLayoutDrawable(
                e.category,
                e.recipe,
                runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup());
    if (layout.isEmpty()) throw new IllegalArgumentException("[TRANSFER_LAYOUT] 无法构建配方布局");
    var slots = ((mezz.jei.api.gui.IRecipeLayoutDrawable<?>) layout.get()).getRecipeSlotsView();
    mezz.jei.api.recipe.transfer.IRecipeTransferHandler h =
        (mezz.jei.api.recipe.transfer.IRecipeTransferHandler) handler.get();
    var error = h.transferRecipe(mc.player.containerMenu, e.recipe, slots, mc.player, max, false);
    if (error != null) {
      o.addProperty("ready", false);
      o.addProperty("error_type", error.getType().name());
      var tips = new JsonArray();
      error.getTooltip().forEach(t -> tips.add(t.getString()));
      o.add("reasons", tips);
      return o;
    }
    o.addProperty("ready", true);
    if (execute) {
      o.addProperty("dispatched", true);
      try {
        error = h.transferRecipe(mc.player.containerMenu, e.recipe, slots, mc.player, max, true);
        if (error != null) o.addProperty("transfer_error", error.getType().name());
      } catch (RuntimeException failure) {
        o.addProperty("status", "unverified");
        o.addProperty("dispatch_result_unknown", true);
        o.addProperty("reason", "JEI处理器异常，可能已经派发；先查菜单，不重放：" + failure.getClass().getSimpleName());
      }
      GuiContext.invalidate();
      MenuContext.consumeObservation();
    }
    o.addProperty("business_success", false);
    o.addProperty("next", "转移可能填草稿或直接编码；先inspect_gui检查槽与样板NBT，再决定编码，不重复触发。");
    return o;
  }
}
