package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTextTooltip;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.RenderTooltipEvent;

/**
 * Passive Forge tooltip event collector used during bounded inspect_tooltip probes.
 * Handles both standard GatherComponents and direct FormattedCharSequence RenderTooltipEvent.Pre.
 */
public final class GuiTooltipProbe {
  private GuiTooltipProbe() {}

  public static void onGather(RenderTooltipEvent.GatherComponents e) {
    if (!TooltipCapture.isArmed() || !AgentPointer.probeActive()) return;
    List<String> rawLines = new ArrayList<>();
    List<String> components = new ArrayList<>();
    for (var either : e.getTooltipElements()) {
      either
          .ifLeft(ft -> rawLines.add(ft.getString()))
          .ifRight(tc -> components.add(tc.getClass().getSimpleName()));
    }
    ItemStack stack = e.getItemStack();
    JsonObject item = stack == null || stack.isEmpty() ? null : Actions.item(stack);
    TooltipCapture.recordGather(rawLines, components, item);
  }

  public static void onRenderPre(RenderTooltipEvent.Pre e) {
    if (!TooltipCapture.isArmed() || !AgentPointer.probeActive() || TooltipCapture.hasCaptured()) {
      return;
    }
    List<String> rawLines = new ArrayList<>();
    List<String> components = new ArrayList<>();
    for (ClientTooltipComponent comp : e.getComponents()) {
      if (comp instanceof ClientTextTooltip textComp) {
        String extracted = extractClientTextTooltip(textComp);
        if (!extracted.isEmpty()) rawLines.add(extracted);
      } else if (comp != null) {
        components.add(comp.getClass().getSimpleName());
      }
    }
    ItemStack stack = e.getItemStack();
    JsonObject item = stack == null || stack.isEmpty() ? null : Actions.item(stack);
    if (!rawLines.isEmpty() || !components.isEmpty()) {
      TooltipCapture.recordFallbackPre(rawLines, components, item);
    }
  }

  static String extractClientTextTooltip(ClientTextTooltip comp) {
    try {
      Field seqField =
          TooltipCapture.findDeclaredFieldByType(
              ClientTextTooltip.class, FormattedCharSequence.class);
      if (seqField == null) return "";
      Object val = seqField.get(comp);
      if (val instanceof FormattedCharSequence seq) {
        return extractSequenceText(seq);
      }
    } catch (ReflectiveOperationException | RuntimeException ignored) {
    }
    return "";
  }

  static String extractSequenceText(FormattedCharSequence seq) {
    if (seq == null) return "";
    StringBuilder sb = new StringBuilder();
    seq.accept(
        (idx, style, codePoint) -> {
          sb.appendCodePoint(codePoint);
          return true;
        });
    return sb.toString();
  }
}
