package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.util.Locale;

/** Landing deviation and stance-candidate diagnostics shared by navigation receipts. */
public final class ArrivalReceipt {
  public static JsonObject landing(
      double px, double py, double pz, int tx, int ty, int tz, double surfaceY) {
    double targetY = Double.isFinite(surfaceY) ? surfaceY : ty;
    double dx = px - (tx + .5), dy = py - targetY, dz = pz - (tz + .5);
    var o = new JsonObject();
    o.addProperty("x", round(px));
    o.addProperty("y", round(py));
    o.addProperty("z", round(pz));
    o.addProperty("target_x", tx + .5);
    o.addProperty("target_y", round(targetY));
    o.addProperty("target_z", tz + .5);
    o.addProperty("horizontal_deviation", round(Math.sqrt(dx * dx + dz * dz)));
    o.addProperty("vertical_deviation", round(Math.abs(dy)));
    return o;
  }

  public static String landingText(
      double px, double py, double pz, int tx, int ty, int tz, double surfaceY) {
    var l = landing(px, py, pz, tx, ty, tz, surfaceY);
    return "落点=("
        + number(l, "x")
        + ","
        + number(l, "y")
        + ","
        + number(l, "z")
        + ")，水平偏差="
        + number(l, "horizontal_deviation")
        + "格，垂直偏差="
        + number(l, "vertical_deviation")
        + "格";
  }

  public static JsonObject stance(
      int maxRadius,
      boolean exactStance,
      int scanned,
      int standCells,
      int visibleFromStand,
      int both,
      int visibleOnly,
      int blocked,
      int unloaded,
      int hazard,
      Integer nearestX,
      Integer nearestY,
      Integer nearestZ,
      double nearestDistance) {
    var o = new JsonObject();
    o.addProperty("max_radius", maxRadius);
    o.addProperty("exact_stance", exactStance);
    o.addProperty("scanned", scanned);
    o.addProperty("stand_cells", standCells);
    o.addProperty("visible_from_stand", visibleFromStand);
    o.addProperty("both", both);
    o.addProperty("visible_only", visibleOnly);
    var rejected = new JsonObject();
    rejected.addProperty("blocked", blocked);
    rejected.addProperty("unloaded", unloaded);
    rejected.addProperty("hazard", hazard);
    o.add("rejected", rejected);
    if (nearestX != null && nearestY != null && nearestZ != null) {
      var nearest = new JsonObject();
      nearest.addProperty("x", nearestX);
      nearest.addProperty("y", nearestY);
      nearest.addProperty("z", nearestZ);
      nearest.addProperty("distance", round(nearestDistance));
      o.add("nearest", nearest);
    } else o.add("nearest", com.google.gson.JsonNull.INSTANCE);
    return o;
  }

  public static String candidatesText(JsonObject candidates) {
    return "candidates=" + candidates;
  }

  private static String number(JsonObject o, String key) {
    return String.format(Locale.ROOT, "%.2f", o.get(key).getAsDouble());
  }

  private static double round(double value) {
    return Double.isFinite(value) ? Math.round(value * 100.0) / 100.0 : value;
  }

  private ArrivalReceipt() {}
}
