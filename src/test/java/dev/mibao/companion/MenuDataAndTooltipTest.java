package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MenuDataAndTooltipTest {
  private static final class DummySeq {}

  private static final class DummyTooltip {
    @SuppressWarnings("unused")
    private final DummySeq obfuscatedFieldName = new DummySeq();
  }

  @Test
  void menuDataStateTracksUpdatesBoundsDeltaAndResetsOnSwitch() {
    MenuDataState state = new MenuDataState();
    assertFalse(state.update(1, 0, 100), "Unbound menu must ignore updates");

    state.bind(5);
    assertTrue(state.update(5, 0, 42));
    assertTrue(state.update(5, 2, 200));
    assertFalse(state.update(6, 0, 10), "Wrong containerId rejected");
    assertFalse(state.update(5, -1, 10));
    assertFalse(state.update(5, MenuDataState.MAX_SLOTS, 10));

    Map<Integer, Integer> before = state.snapshot();
    assertEquals(42, before.get(0));
    assertEquals(200, before.get(2));

    assertTrue(state.update(5, 2, 210));
    assertTrue(state.update(5, 3, 9));
    var delta = MenuDataState.delta(before, state.snapshot());
    assertEquals(2, delta.size());
    assertArrayEquals(new int[] {200, 210}, delta.get(2));
    assertArrayEquals(new int[] {Integer.MIN_VALUE, 9}, delta.get(3));

    long revBeforeSwitch = state.revision();
    state.bind(7);
    assertTrue(state.snapshot().isEmpty());
    assertTrue(state.revision() > revBeforeSwitch);
  }

  @Test
  void tooltipCaptureCleansFormattingAndScansFieldsByType() throws Exception {
    TooltipCapture.reset();
    TooltipCapture.recordGather(List.of("ignored"), List.of(), null);
    assertFalse(TooltipCapture.hasCaptured());

    TooltipCapture.start();
    assertTrue(TooltipCapture.isArmed());
    TooltipCapture.recordGather(
        List.of("§bSide Config", "  点击切换§a输入/输出\u0007  ", ""),
        List.of("FluidTooltipComponent"),
        null);
    assertTrue(TooltipCapture.hasCaptured());

    var res = TooltipCapture.consume();
    assertTrue(res.get("captured").getAsBoolean());
    var lines = res.getAsJsonArray("lines");
    assertEquals(2, lines.size());
    assertEquals("Side Config", lines.get(0).getAsString());
    assertEquals("点击切换输入/输出", lines.get(1).getAsString());
    assertFalse(TooltipCapture.isArmed());

    Field f = TooltipCapture.findDeclaredFieldByType(DummyTooltip.class, DummySeq.class);
    assertNotNull(f);
    assertInstanceOf(DummySeq.class, f.get(new DummyTooltip()));
  }

  @Test
  void hoverProbeExpiresQuicklyAndYieldsOnPhysicalMouseMovement() {
    PointerState.HoverProbe probe = new PointerState.HoverProbe();
    Object screen = new Object();
    probe.begin(screen, 2L, 800, 450, 120, 80, 500.0, 300.0, 1_000_000L);

    assertTrue(probe.active(screen, 2L, 800, 450, 100_000_000L));
    assertFalse(probe.active(screen, 2L, 800, 450, 1_000_000L + PointerState.HoverProbe.TTL_NANOS + 1));
    assertFalse(probe.active(new Object(), 2L, 800, 450, 10_000_000L));
    assertFalse(probe.active(screen, 3L, 800, 450, 10_000_000L));

    assertFalse(probe.checkPhysicalMoved(500.2, 300.3));
    assertTrue(probe.active(screen, 2L, 800, 450, 10_000_000L));
    assertTrue(probe.checkPhysicalMoved(503.0, 300.0));
    assertFalse(probe.active(screen, 2L, 800, 450, 10_000_000L));

    assertThrows(
        IllegalArgumentException.class,
        () -> probe.begin(screen, 2L, 800, 450, 800, 10, 0, 0, 10));
  }
}
