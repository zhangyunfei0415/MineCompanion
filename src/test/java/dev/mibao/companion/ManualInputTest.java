package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

class ManualInputTest {
  @Test
  void idleCleanupNeverReleasesPhysicalMovement() {
    var owned = new InputOwnership<String>();
    var keys = new HashMap<String, Boolean>();
    keys.put("W", true);
    keys.put("A", true);
    keys.put("S", true);
    keys.put("D", true);
    for (int i = 0; i < 200; i++) owned.release(k -> keys.put(k, false));
    assertTrue(keys.values().stream().allMatch(Boolean::booleanValue));
  }

  @Test
  void handoffReleasesAiOnceThenLeavesNewHumanPressAlone() {
    var owned = new InputOwnership<String>();
    var keys = new HashMap<String, Boolean>();
    owned.add("W");
    keys.put("W", true);
    owned.release(k -> keys.put(k, false));
    assertFalse(keys.get("W"));
    keys.put("W", true);
    for (int i = 0; i < 100; i++) owned.release(k -> keys.put(k, false));
    assertTrue(keys.get("W"));
  }

  @Test
  void cleanupReleasesOnlyTheOwnedSet() {
    var owned = new InputOwnership<String>();
    owned.add("jump");
    owned.add("use");
    var released = new HashSet<String>();
    owned.release(released::add);
    assertEquals(Set.of("jump", "use"), released);
    assertTrue(owned.isEmpty());
  }

  @Test
  void actualPackagedNavigatorCleanupHasNoDirectVanillaKeyWrites() throws Exception {
    var node = new ClassNode();
    try (var in = getClass().getResourceAsStream("/dev/mibao/companion/Navigator.class")) {
      assertNotNull(in);
      new ClassReader(in).accept(node, 0);
    }
    var method =
        node.methods.stream()
            .filter(m -> m.name.equals("release") && m.desc.equals("()V"))
            .findFirst()
            .orElseThrow();
    var calls = new ArrayList<String>();
    for (var insn : method.instructions) {
      assertFalse(
          insn instanceof FieldInsnNode, "Cleanup must not access vanilla physical key state");
      if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
    }
    assertEquals(List.of("dev/mibao/companion/PlayerControls.release()V"), calls);
  }
}
