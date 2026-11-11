package dev.mibao.companion;

import com.google.gson.*;
import net.minecraft.client.Minecraft;

public final class SignEditor {
  public static boolean active() {
    var s = Minecraft.getInstance().screen;
    for (Class<?> c = s == null ? null : s.getClass(); c != null; c = c.getSuperclass())
      if (c.getName().equals("net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen"))
        return true;
    return false;
  }

  private static String[] lines() {
    var s = Minecraft.getInstance().screen;
    if (!active()) throw new IllegalArgumentException("[SIGN_EDITOR_REQUIRED] 先打开告示牌编辑界面");
    try {
      for (Class<?> c = s.getClass(); c != null; c = c.getSuperclass())
        for (var f : c.getDeclaredFields())
          if (f.getType() == String[].class) {
            f.setAccessible(true);
            var value = (String[]) f.get(s);
            if (value != null && value.length == 4) return value;
          }
    } catch (ReflectiveOperationException e) {
      throw new IllegalArgumentException("[SIGN_VERSION_UNSUPPORTED] 无法读取当前版本告示牌编辑器");
    }
    throw new IllegalArgumentException("[SIGN_VERSION_UNSUPPORTED] 未找到四行编辑文本");
  }

  public static JsonObject inspect() {
    var o = new JsonObject();
    o.addProperty("kind", "sign_editor");
    var rows = new JsonArray();
    for (String line : lines()) rows.add(line);
    o.add("lines", rows);
    o.addProperty("actions", "write_sign / close_screen");
    return o;
  }

  public static String write(JsonArray rows) {
    String[] next = SignLines.normalize(rows);
    var current = lines();
    System.arraycopy(next, 0, current, 0, 4);
    Minecraft.getInstance().screen.onClose();
    return "告示牌四行编辑已通过正常界面提交；文字持久化须重新观察确认，未声称服务器保存成功。";
  }

  private SignEditor() {}
}
