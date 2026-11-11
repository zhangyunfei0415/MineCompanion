package dev.mibao.companion;

import java.util.*;

public final class MemberDirectory {
  public static CommunityBoard.Member save(
      List<CommunityBoard.Member> members,
      String id,
      String name,
      String uuid,
      String role,
      String delivery,
      String report) {
    if (!name.matches("[A-Za-z0-9_]{1,16}")
        || !Set.of("ADMIN", "MEMBER").contains(role)
        || !Set.of("DEFAULT", "BRIEF", "DETAIL").contains(report))
      throw new IllegalArgumentException("玩家名1..16位英文数字下划线；角色ADMIN/MEMBER；汇报DEFAULT/BRIEF/DETAIL");
    var existing = members.stream().filter(m -> !id.isBlank() && m.id.equals(id)).findFirst();
    if (!id.isBlank() && existing.isEmpty()) throw new IllegalArgumentException("成员记录已删除，请重新选择");
    var m = existing.orElseGet(CommunityBoard.Member::new);
    for (var other : members)
      if (other != m
          && (other.name.equalsIgnoreCase(name) || !uuid.isBlank() && other.uuid.equals(uuid)))
        throw new IllegalArgumentException("姓名或UUID与已有成员冲突");
    if (!m.uuid.isBlank() && !m.name.equalsIgnoreCase(name) && !m.uuid.equals(uuid))
      throw new IllegalArgumentException("已绑定账号不可直接改成其他人；请删除旧授权再添加");
    m.name = name;
    if (!uuid.isBlank()) m.uuid = uuid;
    m.role = role;
    m.delivery = delivery;
    m.report = report;
    m.enabled = true;
    if (existing.isEmpty()) members.add(m);
    return m;
  }

  public static String key(String name) {
    return "name:" + name.toLowerCase(Locale.ROOT);
  }

  public static boolean revoked(Set<String> denied, String uuid, String name) {
    return denied.contains(key(name)) || !uuid.isBlank() && denied.contains("uuid:" + uuid);
  }

  private MemberDirectory() {}
}
