package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import javax.script.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

class PointerTransformerTest {
  public static class Stub {
    public static boolean active;

    public static double mouseX(double real) {
      return active ? real + 10 : real;
    }

    public static double mouseY(double real) {
      return active ? real + 20 : real;
    }

    public static boolean mouseButton(boolean real, int button) {
      return active ? button == 1 : real;
    }
  }

  private static class Loader extends ClassLoader {
    Class<?> define(byte[] bytes) {
      return defineClass(null, bytes, 0, bytes.length);
    }
  }

  @Test
  void shippedHooksVerifyAndPreserveRealMouseWhenInactive() throws Exception {
    String script;
    try (var in = getClass().getResourceAsStream("/coremods/companion_input.js")) {
      script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    script =
        script.replace(
            "var ASMAPI = Java.type('net.minecraftforge.coremod.api.ASMAPI');",
            "var ASMAPI = {mapMethod:function(n){return n;}};");
    var engine = new ScriptEngineManager().getEngineByName("nashorn");
    engine.eval(script);
    engine.eval("var defs=initializeCoreMod();");
    assertEquals("m_91589_", engine.eval("defs.companion_pointer_x.target.methodName"));
    assertEquals("m_91594_", engine.eval("defs.companion_pointer_y.target.methodName"));
    for (Object kind : new Object[] {"x", "y", 0, 1, 2}) {
      boolean coord = kind instanceof String;
      var method =
          new MethodNode(
              Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "get", coord ? "()D" : "()Z", null, null);
      method.instructions.add(new InsnNode(coord ? Opcodes.DCONST_1 : Opcodes.ICONST_0));
      method.instructions.add(new InsnNode(coord ? Opcodes.DRETURN : Opcodes.IRETURN));
      method.maxStack = 2;
      method.maxLocals = 0;
      ((Invocable) engine).invokeFunction("injectPointer", method, kind);
      new Analyzer<>(new BasicInterpreter()).analyze("test/Pointer", method);
      for (var insn : method.instructions)
        if (insn instanceof MethodInsnNode call
            && call.owner.equals("dev/mibao/companion/AgentPointer"))
          call.owner = Type.getInternalName(Stub.class);
      var owner = new ClassNode();
      owner.version = Opcodes.V17;
      owner.access = Opcodes.ACC_PUBLIC;
      owner.name = "test/Pointer" + kind;
      owner.superName = "java/lang/Object";
      owner.methods.add(method);
      var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
      owner.accept(writer);
      var run = new Loader().define(writer.toByteArray()).getMethod("get");
      Stub.active = false;
      assertEquals(coord ? Double.valueOf(1) : Boolean.FALSE, run.invoke(null));
      Stub.active = true;
      Object expected =
          coord ? Double.valueOf(kind.equals("x") ? 11 : 21) : Boolean.valueOf(kind.equals(1));
      assertEquals(expected, run.invoke(null));
    }
  }
}
