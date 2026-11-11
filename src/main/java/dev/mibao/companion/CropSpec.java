package dev.mibao.companion;

/** Shared names for all four ordinary crops, independent of Minecraft class loading. */
public record CropSpec(String block, String seed, String product) {
  public static CropSpec of(String id) {
    return switch (id) {
      case "minecraft:wheat", "wheat" ->
          new CropSpec("minecraft:wheat", "minecraft:wheat_seeds", "minecraft:wheat");
      case "minecraft:carrot", "minecraft:carrots", "carrots" ->
          new CropSpec("minecraft:carrots", "minecraft:carrot", "minecraft:carrot");
      case "minecraft:potato", "minecraft:potatoes", "potatoes" ->
          new CropSpec("minecraft:potatoes", "minecraft:potato", "minecraft:potato");
      case "minecraft:beetroot", "minecraft:beetroots", "beetroots" ->
          new CropSpec("minecraft:beetroots", "minecraft:beetroot_seeds", "minecraft:beetroot");
      default -> throw new IllegalArgumentException("支持小麦、胡萝卜、马铃薯、甜菜，crop使用注册ID");
    };
  }
}
