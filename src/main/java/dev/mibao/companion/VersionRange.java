package dev.mibao.companion;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight SemVer and range matcher (*, exact, 10.4.*, >=a <b, ^10.4.0, ~10.4.0). Falls back
 * to advisory true for non-numeric mod versions so non-standard strings never block needlessly.
 */
public final class VersionRange {
  public record MatchResult(boolean matched, boolean advisory, String detail) {}

  private static final Pattern VERSION_CORE = Pattern.compile("^v?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(.*)$");
  private static final Pattern CLAUSE = Pattern.compile("^(>=|<=|>|<|=|\\^|~)?\\s*(.+)$");

  private VersionRange() {}

  public static MatchResult matches(String constraint, String installedVersion) {
    String req = constraint == null ? "*" : constraint.trim();
    if (req.isEmpty() || req.equals("*")) {
      return new MatchResult(true, false, "wildcard");
    }
    String installed = installedVersion == null ? "" : installedVersion.trim();
    if (installed.isEmpty()) {
      return new MatchResult(false, true, "missing installed version");
    }
    if (req.equals(installed)) {
      return new MatchResult(true, false, "exact");
    }
    // Support comma or whitespace separated clauses such as ">=10.4.0 <11.0.0" or ">=10.4.0, <11.0.0"
    String[] rawTokens = req.replace(',', ' ').trim().split("\\s+");
    List<String> clauses = new ArrayList<>();
    for (int i = 0; i < rawTokens.length; i++) {
      String tok = rawTokens[i];
      if (tok.isEmpty()) continue;
      if ((tok.equals(">=") || tok.equals("<=") || tok.equals(">") || tok.equals("<") || tok.equals("="))
          && i + 1 < rawTokens.length) {
        clauses.add(tok + rawTokens[++i]);
      } else {
        clauses.add(tok);
      }
    }
    int[] actual = parseTuple(installed);
    if (actual == null) {
      return new MatchResult(true, true, "advisory: non-numeric installed version '" + installed + "'");
    }
    boolean advisory = false;
    for (String clause : clauses) {
      MatchResult step = matchSingleClause(clause, installed, actual);
      if (!step.matched()) return step;
      advisory |= step.advisory();
    }
    return new MatchResult(true, advisory, advisory ? "advisory match" : "matched");
  }

  private static MatchResult matchSingleClause(String clause, String installedRaw, int[] actual) {
    if (clause.equals("*")) return new MatchResult(true, false, "wildcard");
    if (clause.endsWith(".*") || clause.endsWith(".x") || clause.endsWith(".X")) {
      String prefix = clause.substring(0, clause.length() - 2);
      int[] req = parseTuple(prefix);
      if (req == null) return new MatchResult(true, true, "advisory wildcard: " + clause);
      String[] parts = prefix.split("\\.");
      for (int i = 0; i < Math.min(parts.length, 3); i++) {
        if (actual[i] != req[i]) {
          return new MatchResult(false, false, installedRaw + " does not match " + clause);
        }
      }
      return new MatchResult(true, false, "wildcard-prefix");
    }
    Matcher m = CLAUSE.matcher(clause);
    if (!m.matches()) {
      return new MatchResult(true, true, "advisory unparsed clause: " + clause);
    }
    String op = m.group(1) == null ? "=" : m.group(1);
    String targetStr = m.group(2).trim();
    if (targetStr.equals("*")) return new MatchResult(true, false, "wildcard");
    int[] target = parseTuple(targetStr);
    if (target == null) {
      if (op.equals("=")) {
        boolean eq = installedRaw.equals(targetStr);
        return new MatchResult(eq, !eq, eq ? "exact" : installedRaw + " != " + targetStr);
      }
      return new MatchResult(true, true, "advisory unparsed target: " + targetStr);
    }
    int cmp = compareTuples(actual, target);
    return switch (op) {
      case "=" -> {
        boolean ok = cmp == 0;
        if (ok && hasPrereleaseSuffix(installedRaw) != hasPrereleaseSuffix(targetStr)) {
          yield new MatchResult(true, true, "advisory suffix diff: " + installedRaw);
        }
        yield new MatchResult(ok, false, ok ? "equal" : installedRaw + " != " + targetStr);
      }
      case ">" -> new MatchResult(cmp > 0, false, cmp > 0 ? ">" : installedRaw + " <= " + targetStr);
      case ">=" -> new MatchResult(cmp >= 0, false, cmp >= 0 ? ">=" : installedRaw + " < " + targetStr);
      case "<" -> new MatchResult(cmp < 0, false, cmp < 0 ? "<" : installedRaw + " >= " + targetStr);
      case "<=" -> new MatchResult(cmp <= 0, false, cmp <= 0 ? "<=" : installedRaw + " > " + targetStr);
      case "^" -> {
        int[] upper;
        if (target[0] > 0) upper = new int[] {target[0] + 1, 0, 0};
        else if (target[1] > 0) upper = new int[] {0, target[1] + 1, 0};
        else upper = new int[] {0, 0, target[2] + 1};
        boolean ok = cmp >= 0 && compareTuples(actual, upper) < 0;
        yield new MatchResult(ok, false, ok ? "caret" : installedRaw + " outside " + clause);
      }
      case "~" -> {
        int[] upper = new int[] {target[0], target[1] + 1, 0};
        boolean ok = cmp >= 0 && compareTuples(actual, upper) < 0;
        yield new MatchResult(ok, false, ok ? "tilde" : installedRaw + " outside " + clause);
      }
      default -> new MatchResult(true, true, "advisory operator: " + op);
    };
  }

  private static boolean hasPrereleaseSuffix(String version) {
    Matcher m = VERSION_CORE.matcher(version);
    if (!m.matches()) return false;
    String rest = m.group(4);
    return rest != null && !rest.isBlank() && !rest.startsWith("+");
  }

  private static int[] parseTuple(String version) {
    if (version == null || version.isBlank()) return null;
    Matcher m = VERSION_CORE.matcher(version.trim());
    if (!m.matches()) return null;
    try {
      int major = Integer.parseInt(m.group(1));
      int minor = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
      int patch = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;
      String suffix = m.group(4);
      if (suffix != null && !suffix.isEmpty() && suffix.charAt(0) != '-' && suffix.charAt(0) != '+') {
        return null;
      }
      return new int[] {major, minor, patch};
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static int compareTuples(int[] a, int[] b) {
    for (int i = 0; i < 3; i++) {
      if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
    }
    return 0;
  }
}
