package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;

/** Data only. Descriptors suggest existing skills, never inject Java/JS or certify a machine. */
public record AdapterDescriptor(String id, List<String> blocks, String skill, String note) {
  public static AdapterDescriptor parse(JsonObject o) {
    String id = o.get("id").getAsString(), skill = o.get("skill").getAsString();
    if (!id.matches("[a-z0-9_.-]{1,80}") || !skill.matches("[a-zA-Z0-9_.:-]{1,128}"))
      throw new IllegalArgumentException("适配器id/skill格式错误");
    var blocks = new ArrayList<String>();
    for (var b : o.getAsJsonArray("blocks")) {
      String v = b.getAsString();
      if (!v.matches("[a-z0-9_.-]+:[a-z0-9_./*-]+") || v.indexOf('*') >= 0 && !v.endsWith(":*"))
        throw new IllegalArgumentException("blocks需要注册ID或namespace:*");
      blocks.add(v);
    }
    if (blocks.isEmpty() || blocks.size() > 32) throw new IllegalArgumentException("blocks须1..32项");
    return new AdapterDescriptor(
        id,
        List.copyOf(blocks),
        skill,
        o.has("note") ? Rules.clean(o.get("note").getAsString(), 300) : "");
  }

  public boolean matches(String block) {
    return blocks.stream()
        .anyMatch(
            v ->
                v.endsWith(":*")
                    ? block.startsWith(v.substring(0, v.length() - 1))
                    : block.equals(v));
  }
}
