package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import java.util.function.Consumer;

/** Resolve against a fresh view, never keep screen pixels or old slot IDs in a skill. */
public final class SemanticGui {
  public static void select(JsonObject args, Consumer<String> done) {
    var v = GuiContext.observe();
    var call = new JsonObject();
    call.add("gui_view", v.get("gui_view"));
    if (args.has("item")) {
      String filter = args.get("item").getAsString(), area = Agent.str(args, "area", "container");
      if (!Set.of("player", "container").contains(area))
        throw new IllegalArgumentException("area为player/container");
      var matches = new ArrayList<JsonObject>();
      for (var e : v.getAsJsonArray("slots")) {
        var s = e.getAsJsonObject();
        if (s.get("count").getAsInt() > 0
            && s.get("player_inventory").getAsBoolean() == area.equals("player")
            && (s.get("id").getAsString().equals(filter)
                || s.has("fingerprint") && s.get("fingerprint").getAsString().equals(filter)))
          matches.add(s);
      }
      if (matches.size() != 1) {
        int index = args.has("index") ? Agent.number(args, "index", -1) : -1;
        String nth = Agent.str(args, "nth", "");
        int pick = GuiChoice.choose(matches.size(), index, nth);
        if (pick < 0) {
          var candidates = new ArrayList<GuiChoice.Slot>();
          for (var s : matches)
            candidates.add(
                new GuiChoice.Slot(
                    s.get("menu_slot").getAsInt(),
                    s.get("id").getAsString(),
                    s.get("count").getAsInt()));
          throw new IllegalArgumentException(
              GuiChoice.candidates(matches.size(), "物品槽", candidates));
        }
        call.add("slot", matches.get(pick).get("menu_slot"));
      } else call.add("slot", matches.get(0).get("menu_slot"));
      call.addProperty("button", Agent.number(args, "button", 0));
      call.addProperty("type", Agent.str(args, "type", "QUICK_MOVE"));
      Agent.execute("gui_slot", call, done);
    } else {
      String label = SkillCatalog.required(args, "label");
      var matches = new ArrayList<JsonObject>();
      for (var e : v.getAsJsonArray("widgets")) {
        var w = e.getAsJsonObject();
        if (w.get("active").getAsBoolean()
            && w.get("visible").getAsBoolean()
            && w.get("label").getAsString().equals(label)) matches.add(w);
      }
      int index = args.has("index") ? Agent.number(args, "index", -1) : -1;
      String nth = Agent.str(args, "nth", "");
      int pick = GuiChoice.choose(matches.size(), index, nth);
      if (pick < 0) {
        var candidates = new ArrayList<GuiChoice.Slot>();
        for (var w : matches)
          candidates.add(new GuiChoice.Slot(w.get("widget").getAsInt(), label, 1));
        throw new IllegalArgumentException(GuiChoice.candidates(matches.size(), "控件", candidates));
      }
      call.add("widget", matches.get(pick).get("widget"));
      if (args.has("text")) {
        call.add("text", args.get("text"));
        Agent.execute("gui_text", call, done);
      } else Agent.execute("gui_button", call, done);
    }
  }
}
