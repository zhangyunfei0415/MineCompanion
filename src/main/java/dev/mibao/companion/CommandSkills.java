package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import java.util.function.Consumer;

/** Portable command skill definitions; server-specific arguments stay in the world ledger. */
final class CommandSkills {
  static String save(JsonObject a) throws Exception {
    String name = SkillCatalog.required(a, "name"), template = SkillCatalog.required(a, "template");
    if (name.length() > 80
        || template.length() > 1024
        || template.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("[INVALID_ARGUMENT] 名称最长80，模板为单行1024字符以内");
    var skill = new Store.Skill();
    skill.description = Agent.str(a, "description", "指令技能；运行时仍检查本服配置和实际权限");
    skill.updated = java.time.Instant.now().toString();
    var step = new JsonObject();
    step.addProperty("tool", "game_command");
    var args = new JsonObject();
    args.addProperty("command", template);
    step.add("args", args);
    skill.steps.add(step);
    Store.saveSkill(name, skill);
    return "已保存通用指令技能，未执行、待验证。参数用${home}等占位，command_skill_bind绑定本服参数；不会修改授权。";
  }

  static String bind(JsonObject a) {
    String name = SkillCatalog.required(a, "name");
    var params = Agent.obj(a, "parameters");
    if (!Store.data.skills.containsKey(name))
      throw new IllegalArgumentException("[SKILL_NOT_FOUND] 先保存通用指令技能");
    if (params.size() > 16 || params.toString().length() > 2048)
      throw new IllegalArgumentException("[INVALID_ARGUMENT] 最多16参数/2048字符");
    for (var e : params.entrySet())
      if (!e.getValue().isJsonPrimitive()
          || e.getValue().getAsString().chars().anyMatch(Character::isISOControl))
        throw new IllegalArgumentException("[INVALID_ARGUMENT] 参数须单行简单值");
    if (Store.data.commandBindings == null) Store.data.commandBindings = new LinkedHashMap<>();
    if (params.size() == 0) Store.data.commandBindings.remove(name);
    else Store.data.commandBindings.put(name, params.deepCopy());
    Store.save();
    return "已保存本服务器参数，不修改通用技能或指令权限";
  }

  static void run(JsonObject a, Consumer<String> done) {
    String name = SkillCatalog.required(a, "name");
    var skill = Store.data.skills.get(name);
    if (skill == null || skill.steps.size() != 1)
      throw new IllegalArgumentException("[SKILL_NOT_FOUND] 需要单步指令技能");
    var step = skill.steps.get(0).getAsJsonObject();
    if (!Agent.str(step, "tool", "").equals("game_command"))
      throw new IllegalArgumentException("[INVALID_SKILL] 不是指令技能");
    Map<String, String> values = new LinkedHashMap<>();
    if (Store.data.commandBindings != null && Store.data.commandBindings.containsKey(name))
      Store.data
          .commandBindings
          .get(name)
          .entrySet()
          .forEach(e -> values.put(e.getKey(), e.getValue().getAsString()));
    Agent.obj(a, "parameters")
        .entrySet()
        .forEach(e -> values.put(e.getKey(), e.getValue().getAsString()));
    var args = new JsonObject();
    args.addProperty(
        "command",
        CommandTemplate.expand(
            SkillCatalog.required(step.getAsJsonObject("args"), "command"), values));
    Agent.execute(
        "game_command",
        args,
        result -> {
          Store.recordCommandOutcome(name, result);
          Store.save();
          done.accept(result);
        });
  }
}
