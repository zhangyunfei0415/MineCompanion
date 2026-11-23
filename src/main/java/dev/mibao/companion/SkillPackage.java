package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;

/** Versioned portable definitions. Parameters substitute JSON values, never tool names or code. */
public final class SkillPackage {
  public final JsonObject definition;
  public final String id, name;
  public final Set<String> requires;

  public SkillPackage(JsonObject input) {
    definition = input.deepCopy();
    if (!input.has("schema") || input.get("schema").getAsInt() != 1)
      throw new IllegalArgumentException("技能包schema须为1");
    id = string(input, "id", "");
    name = string(input, "name", id);
    if (!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || id.length() > 100 || name.length() > 80)
      throw new IllegalArgumentException("技能ID须为命名空间ID，名称最长80");
    var deps = new LinkedHashSet<String>();
    if (input.has("requires"))
      for (var el : input.getAsJsonArray("requires")) {
        String tool = el.getAsString();
        if (!Rules.TOOLS.contains(tool)) throw new IllegalArgumentException("未知能力：" + tool);
        deps.add(tool);
      }
    requires = Set.copyOf(deps);
    checkMetadata(input);
    if (!input.has("steps")
        || input.getAsJsonArray("steps").isEmpty()
        || input.getAsJsonArray("steps").size() > 16)
      throw new IllegalArgumentException("技能须含1..16步");
    for (var el : input.getAsJsonArray("steps")) {
      var step = el.getAsJsonObject();
      String tool = string(step, "tool", "");
      if (!SessionPlan.TOOLS.contains(tool))
        throw new IllegalArgumentException("技能包不支持此能力/嵌套：" + tool);
      var args = step.getAsJsonObject("args");
      if (args == null) throw new IllegalArgumentException("步骤缺args");
      if (args.has("x")
          || args.has("y")
          || args.has("z")
          || args.has("object")
          || args.has("gui_view"))
        throw new IllegalArgumentException("可共享技能不能固化世界坐标或旧菜单编号，请绑定命名地点");
      for (String key : List.of("entity_id", "screen_view", "quest_view", "ae_view"))
        if (args.has(key) && !args.get(key).getAsString().matches("\\$\\{[A-Za-z0-9_]+}"))
          throw new IllegalArgumentException("可共享技能的临时实体/界面编号须由当前观察参数绑定：" + key);
    }
    if (definition.toString().length() > 100_000) throw new IllegalArgumentException("技能包过大");
  }

  public JsonObject expand(JsonObject supplied) {
    var values = new JsonObject();
    var params =
        definition.has("parameters") ? definition.getAsJsonObject("parameters") : new JsonObject();
    for (var e : supplied.entrySet())
      if (!params.has(e.getKey())) throw new IllegalArgumentException("未知参数：" + e.getKey());
    for (var e : params.entrySet()) {
      var spec = e.getValue().getAsJsonObject();
      JsonElement value = supplied.has(e.getKey()) ? supplied.get(e.getKey()) : spec.get("default");
      if (value == null || value.isJsonNull() || !value.isJsonPrimitive())
        throw new IllegalArgumentException("缺少或无效参数：" + e.getKey());
      switch (string(spec, "type", "string")) {
        case "integer" -> {
          int n;
          try {
            if (!value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException();
            n = value.getAsBigDecimal().intValueExact();
          } catch (Exception ex) {
            throw new IllegalArgumentException("参数须为整数：" + e.getKey());
          }
          int min = spec.has("min") ? spec.get("min").getAsInt() : 1,
              max = spec.has("max") ? spec.get("max").getAsInt() : 256;
          if (n < min || n > max) throw new IllegalArgumentException("参数越界：" + e.getKey());
          values.addProperty(e.getKey(), n);
        }
        case "boolean" -> {
          if (!value.getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException("参数须为布尔值");
          values.add(e.getKey(), value.deepCopy());
        }
        case "string" -> {
          if (!value.getAsJsonPrimitive().isString()
              || value.getAsString().isBlank()
              || value.getAsString().length() > 256)
            throw new IllegalArgumentException("参数须为1..256字符：" + e.getKey());
          values.add(e.getKey(), value.deepCopy());
        }
        default -> throw new IllegalArgumentException("未知参数类型");
      }
    }
    var session = new JsonObject();
    session.addProperty("name", name);
    session.addProperty(
        "seconds", definition.has("seconds") ? definition.get("seconds").getAsInt() : 600);
    session.add("steps", substitute(definition.get("steps"), values));
    new SessionPlan(session);
    return session;
  }

  private static JsonElement substitute(JsonElement e, JsonObject p) {
    if (e.isJsonArray()) {
      var a = new JsonArray();
      for (var x : e.getAsJsonArray()) a.add(substitute(x, p));
      return a;
    }
    if (e.isJsonObject()) {
      var o = new JsonObject();
      e.getAsJsonObject().entrySet().forEach(x -> o.add(x.getKey(), substitute(x.getValue(), p)));
      return o;
    }
    if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
      String s = e.getAsString();
      if (s.startsWith("${") && s.endsWith("}")) {
        String key = s.substring(2, s.length() - 1);
        if (!p.has(key)) throw new IllegalArgumentException("未定义参数：" + key);
        return p.get(key).deepCopy();
      }
      if (s.contains("${")) throw new IllegalArgumentException("参数占位须占据完整JSON值");
    }
    return e.deepCopy();
  }

  public static String string(JsonObject o, String key, String fallback) {
    return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback;
  }

  private static boolean isString(JsonElement e) {
    return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString();
  }

  /** Display/compatibility metadata is read by the UI; reject shapes it cannot render. */
  private static void checkMetadata(JsonObject input) {
    for (String key : List.of("name", "description", "minecraft"))
      if (input.has(key) && !isString(input.get(key)))
        throw new IllegalArgumentException("技能字段 " + key + " 须为字符串");
    if (input.has("seconds")
        && !(input.get("seconds").isJsonPrimitive() && input.getAsJsonPrimitive("seconds").isNumber()))
      throw new IllegalArgumentException("技能字段 seconds 须为数字");
    if (input.has("mods")) {
      if (!input.get("mods").isJsonObject())
        throw new IllegalArgumentException("技能字段 mods 须为 {模组ID: 版本范围} 对象");
      for (var e : input.getAsJsonObject("mods").entrySet())
        if (!isString(e.getValue()))
          throw new IllegalArgumentException("mods." + e.getKey() + " 须为版本范围字符串");
    }
    if (input.has("parameters")) {
      if (!input.get("parameters").isJsonObject())
        throw new IllegalArgumentException("技能字段 parameters 须为对象");
      for (var e : input.getAsJsonObject("parameters").entrySet()) {
        if (!e.getValue().isJsonObject())
          throw new IllegalArgumentException("参数 " + e.getKey() + " 的定义须为对象");
        var spec = e.getValue().getAsJsonObject();
        for (String key : List.of("type", "description"))
          if (spec.has(key) && !isString(spec.get(key)))
            throw new IllegalArgumentException("参数 " + e.getKey() + "." + key + " 须为字符串");
        if (spec.has("default") && !spec.get("default").isJsonPrimitive())
          throw new IllegalArgumentException("参数 " + e.getKey() + " 的默认值须为字符串/数字/布尔");
        for (String key : List.of("min", "max"))
          if (spec.has(key)
              && !(spec.get(key).isJsonPrimitive() && spec.getAsJsonPrimitive(key).isNumber()))
            throw new IllegalArgumentException("参数 " + e.getKey() + "." + key + " 须为数字");
      }
    }
  }
}
