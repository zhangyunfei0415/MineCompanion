package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import javax.script.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

class MiningTransformerTest {
  public static class Stub {
    public static boolean allowed;

    public static boolean permitBreak(Object position) {
      return allowed;
    }
  }

  private static class Loader extends ClassLoader {
    Class<?> define(byte[] bytes) {
      return defineClass(null, bytes, 0, bytes.length);
    }
  }

  @Test
  void bothShippedHooksRejectBeforeTheOriginalBodyAndPreserveAllowedCalls() throws Exception {
    String script;
    try (var in = getClass().getResourceAsStream("/coremods/companion_input.js")) {
      assertNotNull(in);
      script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    script =
        script.replace(
            "var ASMAPI = Java.type('net.minecraftforge.coremod.api.ASMAPI');",
            "var ASMAPI = {mapMethod:function(n){return n;}};");
    var engine = new ScriptEngineManager().getEngineByName("nashorn");
    engine.eval(script);
    engine.eval("var defs=initializeCoreMod();");
    for (String kind : new String[] {"start", "continue"}) {
      assertEquals(
          kind.equals("start") ? "m_105269_" : "m_105283_",
          engine.eval("defs.companion_mining_" + kind + ".target.methodName"));
      assertEquals(
          "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z",
          engine.eval("defs.companion_mining_" + kind + ".target.methodDesc"));
      var owner = new ClassNode();
      owner.version = Opcodes.V17;
      owner.access = Opcodes.ACC_PUBLIC;
      owner.name = "test/MiningBoundary" + kind;
      owner.superName = "java/lang/Object";
      owner.fields.add(
          new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "writes", "I", null, null));
      var method =
          new MethodNode(
              Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
              "run",
              "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z",
              null,
              null);
      method.instructions.add(new InsnNode(Opcodes.ICONST_1));
      method.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, owner.name, "writes", "I"));
      method.instructions.add(new InsnNode(Opcodes.ICONST_1));
      method.instructions.add(new InsnNode(Opcodes.IRETURN));
      method.maxStack = 1;
      method.maxLocals = 3;
      ((Invocable) engine).invokeFunction("injectMiningBoundary", method);
      new Analyzer<>(new BasicInterpreter()).analyze(owner.name, method);
      for (var insn : method.instructions) {
        if (insn instanceof MethodInsnNode call
            && call.owner.equals("dev/mibao/companion/MiningExpedition")) {
          call.owner = Type.getInternalName(Stub.class);
          call.desc = "(Ljava/lang/Object;)Z";
        }
      }
      owner.methods.add(method);
      var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
      owner.accept(writer);
      var generated = new Loader().define(writer.toByteArray());
      var run = generated.getMethod("run", Object.class, Object.class, Object.class);
      Stub.allowed = false;
      assertEquals(false, run.invoke(null, null, null, null));
      assertEquals(0, generated.getField("writes").getInt(null));
      Stub.allowed = true;
      assertEquals(true, run.invoke(null, null, null, null));
      assertEquals(1, generated.getField("writes").getInt(null));
    }
  }
}
