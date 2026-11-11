package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

class UseHandSessionRegressionTest {
  @Test void worldUseSessionsCannotOpenInventoryDuringVerification() throws Exception {
    var type = new ClassNode();
    try (var stream = getClass().getResourceAsStream("/dev/mibao/companion/UseHandJob.class")) {
      assertNotNull(stream);
      new ClassReader(stream).accept(type, 0);
    }
    int silentSessions = 0;
    for (var method : type.methods) {
      for (var instruction : method.instructions) {
        if (!(instruction instanceof MethodInsnNode call)
            || !call.owner.equals("dev/mibao/companion/InventorySession")
            || !call.name.equals("<init>")) continue;
        assertEquals("(Z)V", call.desc, "使用物品的核验禁止默认打开背包");
        assertEquals(Opcodes.ICONST_0, instruction.getPrevious().getOpcode());
        silentSessions++;
      }
    }
    assertEquals(2, silentSessions);
  }
}
