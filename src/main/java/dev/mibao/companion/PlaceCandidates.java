package dev.mibao.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Candidate-site report for place_block failures: what was checked and why each site was refused.
 */
public final class PlaceCandidates {
  public static final int REPORT_LIMIT = 8;

  public record Site(int x, int y, int z, double distance, String reason) {
    public boolean usable() {
      return reason == null || reason.isBlank();
    }
  }

  public record Report(int checked, int usable, List<Site> rejected, Site nearest) {}

  private PlaceCandidates() {}

  public static Report build(List<Site> sites) {
    List<Site> rejected = new ArrayList<>();
    Site nearest = null;
    int usable = 0;
    for (Site s : sites) {
      if (s.usable()) {
        usable++;
        if (nearest == null || s.distance() < nearest.distance()) nearest = s;
        continue;
      }
      rejected.add(s);
    }
    rejected.sort(
        Comparator.comparingDouble(Site::distance)
            .thenComparingInt(Site::y)
            .thenComparingInt(Site::x)
            .thenComparingInt(Site::z));
    return new Report(sites.size(), usable, List.copyOf(rejected), nearest);
  }

  public static String text(Report report) {
    StringBuilder b = new StringBuilder("附近候选 checked=").append(report.checked());
    b.append(" 可用=").append(report.usable());
    b.append(" 最近可用=");
    if (report.nearest() == null) b.append("无");
    else
      b.append("(")
          .append(report.nearest().x())
          .append(",")
          .append(report.nearest().y())
          .append(",")
          .append(report.nearest().z())
          .append(") 距离")
          .append(String.format(Locale.ROOT, "%.1f", report.nearest().distance()));
    b.append(" 拒绝=[");
    int n = 0;
    for (Site s : report.rejected()) {
      if (n == REPORT_LIMIT) break;
      if (n++ > 0) b.append("；");
      b.append("(").append(s.x()).append(",").append(s.y()).append(",").append(s.z()).append(")");
      b.append(s.reason());
    }
    if (report.rejected().size() > REPORT_LIMIT)
      b.append("；另有 ").append(report.rejected().size() - REPORT_LIMIT).append(" 个未列出");
    b.append("]");
    return b.toString();
  }

  public static JsonObject json(Report report) {
    JsonObject out = new JsonObject();
    out.addProperty("checked", report.checked());
    out.addProperty("usable", report.usable());
    if (report.nearest() != null) {
      JsonObject nearest = new JsonObject();
      nearest.addProperty("x", report.nearest().x());
      nearest.addProperty("y", report.nearest().y());
      nearest.addProperty("z", report.nearest().z());
      nearest.addProperty("distance", Math.round(report.nearest().distance() * 10) / 10.0);
      out.add("nearest", nearest);
    }
    JsonArray rejected = new JsonArray();
    int n = 0;
    for (Site s : report.rejected()) {
      if (n++ == REPORT_LIMIT) break;
      JsonObject o = new JsonObject();
      o.addProperty("x", s.x());
      o.addProperty("y", s.y());
      o.addProperty("z", s.z());
      o.addProperty("reason", s.reason());
      rejected.add(o);
    }
    out.add("rejected", rejected);
    return out;
  }
}
