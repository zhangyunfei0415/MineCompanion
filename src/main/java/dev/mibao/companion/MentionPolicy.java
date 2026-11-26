package dev.mibao.companion;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure multiplayer @mention extraction, UUID-bound role resolution, safe public-chat gating, and
 * hard GUEST read-only sandboxing. Zero {@code net.minecraft.*} dependencies for offline JUnit
 * testing.
 */
public final class MentionPolicy {
  public enum Role {
    OWNER,
    ADMIN,
    MEMBER,
    GUEST
  }

  public enum ReplyPhase {
    CHAT,
    ACK,
    PROGRESS,
    FINAL
  }

  public enum MentionActionDecision {
    NO_MENTION,
    ALLOW_MEMBER,
    SANDBOX_GUEST,
    REJECT_STALE_OR_CLOSED
  }

  public record ExtractedMention(String alias, String body, boolean atStart) {}

  /** Explicit tiny read-only allowlist for unprivileged GUEST chat (never mutates world/state). */
  public static final Set<String> GUEST_READONLY_TOOLS =
      Set.of(
          "observe_compact",
          "inspect_client",
          "inspect_scene",
          "search_items",
          "lookup_recipes",
          "lookup_client_recipes",
          "recipe_details",
          "recipe_status",
          "capability_catalog",
          "integration_status",
          "light_survey",
          "column_probe",
          "terraform_preview");

  private MentionPolicy() {}

  /**
   * Extracts a mention targeting either {@code configName} (e.g. 小勤) or {@code playerName}
   * (actual MC account name), preferring longer alias on overlapping prefixes and preferring a
   * leading mention over mid-sentence mentions.
   */
  public static ExtractedMention extractMention(
      String message, String configName, String playerName) {
    if (message == null || message.isBlank()) return null;
    List<String> aliases = new ArrayList<>(2);
    addDistinctAlias(aliases, configName);
    addDistinctAlias(aliases, playerName);
    if (aliases.isEmpty()) return null;
    // Match longest alias first so e.g. "Bot2" matches before "Bot".
    aliases.sort((a, b) -> Integer.compare(b.length(), a.length()));

    String stripped = message.stripLeading();
    ExtractedMention leadingBest = null;
    int leadingEnd = -1;
    for (String alias : aliases) {
      Matcher m = mentionPattern(alias).matcher(stripped);
      if (m.lookingAt()) {
        if (leadingBest == null || alias.length() > leadingBest.alias().length()) {
          String body = ChatCommand.normalize(stripped.substring(m.end()));
          if (!body.isBlank()) {
            leadingBest = new ExtractedMention(alias, body, true);
            leadingEnd = m.end();
          }
        }
      }
    }
    if (leadingBest != null && leadingEnd > 0) return leadingBest;

    int earliestIdx = Integer.MAX_VALUE;
    String earliestAlias = "";
    int earliestEnd = -1;
    for (String alias : aliases) {
      Matcher m = mentionPattern(alias).matcher(message);
      if (m.find()) {
        int start = m.start();
        if (start < earliestIdx
            || (start == earliestIdx && alias.length() > earliestAlias.length())) {
          earliestIdx = start;
          earliestAlias = alias;
          earliestEnd = m.end();
        }
      }
    }
    if (earliestEnd < 0) return null;
    String body = ChatCommand.normalize(message.substring(earliestEnd));
    return body.isBlank() ? null : new ExtractedMention(earliestAlias, body, false);
  }

  private static void addDistinctAlias(List<String> aliases, String raw) {
    if (raw == null) return;
    String clean = raw.strip();
    if (clean.isEmpty()) return;
    for (String existing : aliases) {
      if (existing.equalsIgnoreCase(clean)) return;
    }
    aliases.add(clean);
  }

  private static Pattern mentionPattern(String alias) {
    return Pattern.compile(
        "[@＠]" + Pattern.quote(alias) + "(?![A-Za-z0-9_\\p{IsHan}])",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  }

  /**
   * Resolves verified sender identity without mutating config/members. Same-name impersonators
   * whose UUID differs from a UUID-bound member always resolve to {@link Role#GUEST}.
   */
  public static Role resolveRole(
      List<CommunityBoard.Member> members,
      Set<String> revoked,
      String ownerName,
      String trustedCsv,
      String uuid,
      String name) {
    if (uuid == null || uuid.isBlank() || name == null || name.isBlank()) return Role.GUEST;
    String safeUuid = uuid.strip();
    String safeName = name.strip();
    String cleanOwner = ownerName == null ? "" : ownerName.strip();
    if (revoked != null && MemberDirectory.revoked(revoked, safeUuid, safeName)) {
      return Role.GUEST;
    }
    if (members != null) {
      boolean hasExplicitDisabledMatch = false;
      for (CommunityBoard.Member m : members) {
        if (m == null) continue;
        if (m.uuid != null && !m.uuid.isBlank()) {
          if (m.uuid.equals(safeUuid)) {
            if (!m.enabled) return Role.GUEST;
            return roleFromMember(m.role, safeName, cleanOwner);
          }
          if (m.name != null && m.name.equalsIgnoreCase(safeName)) {
            // UUID mismatch against an already UUID-bound name: hard-reject impersonator.
            return Role.GUEST;
          }
        }
      }
      for (CommunityBoard.Member m : members) {
        if (m == null) continue;
        if ((m.uuid == null || m.uuid.isBlank())
            && m.name != null
            && m.name.equalsIgnoreCase(safeName)) {
          if (!m.enabled) {
            hasExplicitDisabledMatch = true;
            break;
          }
          return roleFromMember(m.role, safeName, cleanOwner);
        }
      }
      if (hasExplicitDisabledMatch) return Role.GUEST;
    }
    if (!cleanOwner.isEmpty() && safeName.equalsIgnoreCase(cleanOwner)) return Role.OWNER;
    if (trustedCsv != null && !trustedCsv.isBlank()) {
      for (String token : trustedCsv.split("[,，\\s]+")) {
        if (!token.isBlank() && token.equalsIgnoreCase(safeName)) return Role.MEMBER;
      }
    }
    return Role.GUEST;
  }

  private static Role roleFromMember(String memberRole, String safeName, String cleanOwner) {
    if (!cleanOwner.isEmpty() && safeName.equalsIgnoreCase(cleanOwner)) return Role.OWNER;
    if ("OWNER".equalsIgnoreCase(memberRole)) return Role.OWNER;
    if ("ADMIN".equalsIgnoreCase(memberRole)) return Role.ADMIN;
    return Role.MEMBER;
  }

  public static boolean canWork(Role role) {
    return role == Role.OWNER || role == Role.ADMIN || role == Role.MEMBER;
  }

  public static MentionActionDecision evaluateActionMention(
      String mentionId, boolean mentionFound, Role role, boolean closed) {
    if (mentionId == null || mentionId.isBlank()) return MentionActionDecision.NO_MENTION;
    if (!mentionFound || closed) return MentionActionDecision.REJECT_STALE_OR_CLOSED;
    return role == Role.GUEST
        ? MentionActionDecision.SANDBOX_GUEST
        : MentionActionDecision.ALLOW_MEMBER;
  }

  public static boolean isHardCommandAllowed(Role role) {
    return canWork(role);
  }

  public static List<String> capabilities(Role role) {
    return canWork(role) ? List.of("reply", "observe_safe", "request_work") : List.of("reply");
  }

  public static boolean isGuestReadOnlyTool(String tool) {
    return tool != null
        && GUEST_READONLY_TOOLS.contains(tool)
        && !Rules.WRITE.contains(tool);
  }

  /**
   * Checks whether raw or sanitized text begins with '/' after stripping formatting codes and
   * control chars.
   */
  public static boolean isSlashCommand(String raw) {
    if (raw == null) return false;
    String stripped = raw.replaceAll("(?i)\u00a7.", "");
    StringBuilder out = new StringBuilder(stripped.length());
    for (int i = 0; i < stripped.length(); i++) {
      char c = stripped.charAt(i);
      if (c >= ' ' && c != 127 && c != '\u00a7') out.append(c);
    }
    return out.toString().stripLeading().startsWith("/");
  }

  public static boolean shouldSendPublicReply(
      boolean publicReplyEnabled,
      ReplyPhase phase,
      String reportMode,
      boolean ackAlreadySent,
      String text) {
    if (!publicReplyEnabled || phase == null || text == null || text.isBlank()) return false;
    if (isSlashCommand(text)) return false;
    return switch (phase) {
      case CHAT, FINAL -> true;
      case ACK -> !ackAlreadySent;
      case PROGRESS -> CommunityReport.intermediate(reportMode, false, false, false, text);
    };
  }
}
