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
    return o.has(key) ? o.get(key).getAsString() : fallback;
  }
}
