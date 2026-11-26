package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class FaceHitMathTest {
  @Test
  void subSlotsResolveClearOfMiddleDividerAndBorders() {
    var top = FaceHitMath.resolveUV(null, null, "top");
    var bottom = FaceHitMath.resolveUV(null, null, "bottom");
    assertEquals(0.5, top.u(), 1e-6);
    assertTrue(top.v() > 0.7 && top.v() < 0.98);
    assertEquals(0.5, bottom.u(), 1e-6);
    assertTrue(bottom.v() < 0.3 && bottom.v() > 0.02);

    var tl = FaceHitMath.resolveUV(null, null, "top_left");
    assertTrue(tl.u() < 0.4 && tl.v() > 0.6);
    var br = FaceHitMath.resolveUV(null, null, "bottom_right");
    assertTrue(br.u() > 0.6 && br.v() < 0.4);
  }

  @Test
  void mapsVisualLeftRightAndBottomTopConsistentlyOnAllFourHorizontalFaces() {
    // North face: viewer faces +Z (South); viewer's left is +X (dx=1), right is -X (dx=0).
    var northLeftTop = FaceHitMath.toLocal("north", 0.1, 0.9, 0, 0, 0, 1, 1, 1);
    assertTrue(northLeftTop.dx() > 0.85);
    assertTrue(northLeftTop.dy() > 0.85);
    assertTrue(northLeftTop.dz() < 0.01);

    // East face: viewer faces -X (West); viewer's left is +Z (dz=1), right is -Z (dz=0).
    var eastRightBottom = FaceHitMath.toLocal("east", 0.9, 0.1, 0, 0, 0, 1, 1, 1);
    assertTrue(eastRightBottom.dx() > 0.99);
    assertTrue(eastRightBottom.dz() < 0.15);
    assertTrue(eastRightBottom.dy() < 0.15);

    // Up face: centered vertical & near maxY
    var upCenter = FaceHitMath.toLocal("up", 0.5, 0.5, 0, 0, 0, 1, 1, 1);
    assertEquals(0.5, upCenter.dx(), 1e-6);
    assertEquals(0.5, upCenter.dz(), 1e-6);
    assertTrue(upCenter.dy() > 0.99);
  }

  @Test
  void rejectsInvalidUnitsOrUnknownSubSlots() {
    assertThrows(IllegalArgumentException.class, () -> FaceHitMath.resolveUV(1.5, 0.5, ""));
    assertThrows(IllegalArgumentException.class, () -> FaceHitMath.resolveUV(0.5, -0.1, ""));
    assertThrows(IllegalArgumentException.class, () -> FaceHitMath.resolveUV(null, null, "diagonal"));
  }

  @Test
  void resolvesRecessedStorageDrawersPaneAcrossAllFourFacingDirections() {
    // StorageDrawers 12.15.1 exact 5-box VoxelShape for facing=north (1px recessed pane at z=0.0625)
    List<FaceHitMath.Box> northDrawerBoxes =
        List.of(
            new FaceHitMath.Box(0.0, 0.0, 0.0, 1.0, 0.0625, 1.0),
            new FaceHitMath.Box(0.0, 0.0625, 0.0, 0.0625, 1.0, 1.0),
            new FaceHitMath.Box(0.0625, 0.0625, 0.0625, 1.0, 1.0, 1.0),
            new FaceHitMath.Box(0.9375, 0.0625, 0.0, 1.0, 1.0, 0.0625),
            new FaceHitMath.Box(0.0625, 0.9375, 0.0, 0.9375, 1.0, 0.0625));
    FaceHitMath.Box bounds = FaceHitMath.union(northDrawerBoxes);

    var top = FaceHitMath.resolveFaceTarget("north", 0.5, 0.78, northDrawerBoxes);
    var bottom = FaceHitMath.resolveFaceTarget("north", 0.5, 0.22, northDrawerBoxes);
    assertEquals(0.5, top.dx(), 1e-4);
    assertEquals(0.78, top.dy(), 1e-4);
    assertTrue(top.dz() > 0.0625 && top.dz() < 0.063, "top must target recessed pane, not trim");
    assertEquals(0.22, bottom.dy(), 1e-4);
    assertTrue(bottom.dz() > 0.0625 && bottom.dz() < 0.063, "bottom must target recessed pane");

    // 4-slot quadrants on north face
    var tl = FaceHitMath.resolveUV(null, null, "top_left");
    var lpTl = FaceHitMath.resolveFaceTarget("north", tl.u(), tl.v(), northDrawerBoxes);
    assertTrue(lpTl.dx() > 0.5 && lpTl.dy() > 0.5 && lpTl.dz() > 0.0625);

    // Roundtrip projection and border occlusion rejection
    var backUv = FaceHitMath.projectToUV("north", top.dx(), top.dy(), top.dz(), bounds);
    assertTrue(FaceHitMath.matchesUV(new FaceHitMath.UV(0.5, 0.78), backUv, 0.02));
    var trimUv = FaceHitMath.projectToUV("north", 0.5, 0.03, 0.0, bounds);
    assertFalse(FaceHitMath.matchesUV(new FaceHitMath.UV(0.5, 0.22), trimUv, 0.12));

    // Half-drawer slab/recessed south, east, west cases
    List<FaceHitMath.Box> southHalfDrawer =
        List.of(
            new FaceHitMath.Box(0.0, 0.0, 0.0, 1.0, 0.0625, 0.5),
            new FaceHitMath.Box(0.0625, 0.0625, 0.0, 0.9375, 0.9375, 0.4375));
    var southCenter = FaceHitMath.resolveFaceTarget("south", 0.5, 0.5, southHalfDrawer);
    assertTrue(southCenter.dz() < 0.4375 && southCenter.dz() > 0.437);

    List<FaceHitMath.Box> eastHalfDrawer =
        List.of(
            new FaceHitMath.Box(0.0, 0.0, 0.0, 0.5, 0.0625, 1.0),
            new FaceHitMath.Box(0.0, 0.0625, 0.0625, 0.4375, 0.9375, 0.9375));
    var eastCenter = FaceHitMath.resolveFaceTarget("east", 0.5, 0.5, eastHalfDrawer);
    assertTrue(eastCenter.dx() < 0.4375 && eastCenter.dx() > 0.437);
  }
}
