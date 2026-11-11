package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import javax.script.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/** Execute the shipped coremod JS with only Forge's mapping lookup substituted. */
class InputTransformerTest {
  @Test
  void optionalMenuReceiptHookVerifiesEveryReturn() throws Exception {
    String script;
    try (var in = getClass().getResourceAsStream("/coremods/companion_input.js")) {
      script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    script =
        script.replace(
            "var ASMAPI = Java.type('net.minecraftforge.coremod.api.ASMAPI');",
            "var ASMAPI = {mapMethod:function(name){return name;}};");
    var engine = new ScriptEngineManager().getEngineByName("nashorn");
    engine.eval(script);
    var m =
        new MethodNode(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "test", "(Ljava/lang/Object;)V", null, null);
    var end = new LabelNode();
    m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
    m.instructions.add(new JumpInsnNode(Opcodes.IFNULL, end));
    m.instructions.add(new InsnNode(Opcodes.RETURN));
    m.instructions.add(end);
    m.instructions.add(new InsnNode(Opcodes.RETURN));
    m.maxStack = 1;
    m.maxLocals = 1;
    ((Invocable) engine).invokeFunction("injectMenuReceipt", m);
    new Analyzer<>(new BasicInterpreter()).analyze("test/Receiver", m);
    int calls = 0;
    for (var n : m.instructions)
      if (n instanceof MethodInsnNode call
          && call.owner.equals("dev/mibao/companion/ModMenuReceipts")) calls++;
    assertEquals(2, calls);
  }

  public static class Stub {
    public static int result = -1, calls;
    public static boolean active;

    public static int keyState(long window, int key) {
      return result;
    }

    public static boolean focused() {
      return active;
    }

    public static boolean key(long w, int k, int s, int a, int m) {
      return active;
    }

    public static boolean character(long w, int c, int m) {
      return active;
    }

    public static boolean blocksWorldMouse() {
      return active;
    }
  }

  private static class Loader extends ClassLoader {
    Class<?> define(byte[] bytes) {
      return defineClass(null, bytes, 0, bytes.length);
    }
  }

  @Test
  void actualCoremodInjectsValidFallbackAndFocusBranches() throws Exception {
    String script;
    try (var in = getClass().getResourceAsStream("/coremods/companion_input.js")) {
      assertNotNull(in);
      script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    script =
        script.replace(
            "var ASMAPI = Java.type('net.minecraftforge.coremod.api.ASMAPI');",
            "var ASMAPI = {mapMethod:function(name){return name;}};");
    var engine = new ScriptEngineManager().getEngineByName("nashorn");
    assertNotNull(engine);
    engine.eval(script);
    engine.eval("var definitions=initializeCoreMod();");
    assertEquals("(JI)Z", engine.eval("definitions.companion_game_keys.target.methodDesc"));
    assertEquals(
        "m_91302_", engine.eval("definitions.companion_background_lease.target.methodName"));
    for (String kind : new String[] {"keys", "focus"}) {
      var m =
          new MethodNode(
              Opcodes.ACC_PUBLIC | (kind.equals("keys") ? Opcodes.ACC_STATIC : 0),
              "test",
              kind.equals("keys") ? "(JI)Z" : "()Z",
              null,
              null);
      m.instructions.add(new InsnNode(Opcodes.ICONST_0));
      m.instructions.add(new InsnNode(Opcodes.IRETURN));
      m.maxStack = 1;
      m.maxLocals = kind.equals("keys") ? 3 : 1;
      ((Invocable) engine).invokeFunction("injectInput", m, kind);
      new Analyzer<>(new BasicInterpreter()).analyze("test/Owner", m);
      assertTrue(m.instructions.size() > 6);
      assertEquals(Opcodes.ICONST_0, m.instructions.get(m.instructions.size() - 2).getOpcode());
      // JVM verification and execution of the actual injected branches, without loading Minecraft.
      m.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
      m.maxLocals = kind.equals("keys") ? 3 : 0;
      for (var insn : m.instructions)
        if (insn instanceof MethodInsnNode call
            && call.owner.equals("dev/mibao/companion/VirtualInput"))
          call.owner = Type.getInternalName(Stub.class);
      var owner = new ClassNode();
      owner.version = Opcodes.V17;
      owner.access = Opcodes.ACC_PUBLIC;
      owner.name = "test/Generated" + kind;
      owner.superName = "java/lang/Object";
      owner.methods.add(m);
      var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
      owner.accept(writer);
      Class<?> compiled = new Loader().define(writer.toByteArray());
      if (kind.equals("keys")) {
        var method = compiled.getMethod("test", long.class, int.class);
        Stub.result = -1;
        assertEquals(false, method.invoke(null, 1L, 49));
        Stub.result = 1;
        assertEquals(true, method.invoke(null, 1L, 49));
        Stub.result = 0;
        assertEquals(false, method.invoke(null, 1L, 49));
      } else {
        var method = compiled.getMethod("test");
        Stub.active = false;
        assertEquals(false, method.invoke(null));
        Stub.active = true;
        assertEquals(true, method.invoke(null));
      }
    }
  }

  @Test
  void physicalKeyTextAndMouseHooksVerifyAndExecuteTheirActualGates() throws Exception {
    String script;
    try (var in = getClass().getResourceAsStream("/coremods/companion_input.js")) {
      script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    script =
        script.replace(
            "var ASMAPI = Java.type('net.minecraftforge.coremod.api.ASMAPI');",
            "var ASMAPI = {mapMethod:function(name){return name;}};");
    var engine = new ScriptEngineManager().getEngineByName("nashorn");
    engine.eval(script);
    for (String kind : new String[] {"key", "char", "mouse"}) {
      String desc = kind.equals("key") ? "(JIIII)V" : kind.equals("char") ? "(JII)V" : "()V";
      var m = new MethodNode(Opcodes.ACC_PUBLIC, "test", desc, null, null);
      String stub = Type.getInternalName(Stub.class);
      m.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, stub, "calls", "I"));
      m.instructions.add(new InsnNode(Opcodes.ICONST_1));
      m.instructions.add(new InsnNode(Opcodes.IADD));
      m.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, stub, "calls", "I"));
      m.instructions.add(new InsnNode(Opcodes.RETURN));
      m.maxStack = 2;
      m.maxLocals = kind.equals("key") ? 7 : kind.equals("char") ? 5 : 1;
      ((Invocable) engine).invokeFunction("injectGate", m, kind);
      new Analyzer<>(new BasicInterpreter()).analyze("test/Gate", m);
      for (var n : m.instructions)
        if (n instanceof MethodInsnNode call && call.owner.equals("dev/mibao/companion/WatchMode"))
          call.owner = stub;
      var owner = new ClassNode();
      owner.version = Opcodes.V17;
      owner.access = Opcodes.ACC_PUBLIC;
      owner.name = "test/Gate" + kind;
      owner.superName = "java/lang/Object";
      owner.methods.add(m);
      var init = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
      init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
      init.instructions.add(
          new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
      init.instructions.add(new InsnNode(Opcodes.RETURN));
      owner.methods.add(init);
      var writer = new ClassWriter(3);
      owner.accept(writer);
      var cls = new Loader().define(writer.toByteArray());
      Object instance = cls.getConstructor().newInstance();
      var method =
          kind.equals("key")
              ? cls.getMethod("test", long.class, int.class, int.class, int.class, int.class)
              : kind.equals("char")
                  ? cls.getMethod("test", long.class, int.class, int.class)
                  : cls.getMethod("test");
      Object[] args =
          kind.equals("key")
              ? new Object[] {1L, 65, 0, 1, 0}
              : kind.equals("char") ? new Object[] {1L, 65, 0} : new Object[] {};
      Stub.calls = 0;
      Stub.active = false;
      method.invoke(instance, args);
      assertEquals(1, Stub.calls);
      Stub.active = true;
      method.invoke(instance, args);
      assertEquals(1, Stub.calls);
    }
  }
}
