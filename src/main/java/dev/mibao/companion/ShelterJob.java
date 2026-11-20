package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Builds a 5x5 shelter on an existing floor through ordinary verified block placement. */
final class ShelterJob extends Actions.Job {
  private record Cell(BlockPos pos, Block block, String item, Direction doorFacing) {
    boolean door() {
      return doorFacing != null;
    }
  }

  private final List<Cell> plan = new ArrayList<>();
  private int next, placed, reused;

  ShelterJob(JsonObject args, Consumer<String> done) {
    super(done);
    TaskReadiness.requireWorld(true);
    var origin = Actions.target(args);
    var facing = Direction.byName(Agent.str(args, "door_facing", "south"));
    if (facing == null || !facing.getAxis().isHorizontal())
      throw new IllegalArgumentException("[INVALID_ARGUMENT] door_facing 必须是水平方向");
    String wall = Agent.str(args, "item", "minecraft:oak_planks");
    String door = Agent.str(args, "door", "minecraft:oak_door");
    Block wallBlock = blockItem(wall);
    Block doorBlock = blockItem(door);
    if (!(doorBlock instanceof DoorBlock) || wallBlock instanceof DoorBlock
        || wallBlock instanceof net.minecraft.world.level.block.FallingBlock
        || !wallBlock.defaultBlockState().isCollisionShapeFullBlock(world, origin))
      throw new IllegalArgumentException("[INVALID_ARGUMENT] 需要普通墙体方块与门物品");
    var doorway = origin.offset(2 + facing.getStepX() * 2, 0, 2 + facing.getStepZ() * 2);
    for (int x = 0; x < 5; x++) {
      for (int z = 0; z < 5; z++) {
        var floor = origin.offset(x, -1, z);
        if (!world.hasChunkAt(floor) || !world.getWorldBorder().isWithinBounds(floor)
            || !world.getBlockState(floor).isFaceSturdy(world, floor, Direction.UP))
          throw new IllegalArgumentException("[NO_SUPPORT] 营地需要完整的 5×5 平坦支撑：" + floor);
      }
    }
    for (int y = 0; y < 2; y++) {
      for (int x = 0; x < 5; x++) {
        for (int z = 0; z < 5; z++) {
          if (x > 0 && x < 4 && z > 0 && z < 4) continue;
          var pos = origin.offset(x, y, z);
          if (!pos.atY(doorway.getY()).equals(doorway))
            plan.add(new Cell(pos, wallBlock, wall, null));
        }
      }
    }
    // Sweep from a supported edge. Each interior roof cell can attach to the preceding row.
    for (int x = 0; x < 5; x++)
      for (int z = 0; z < 5; z++)
        plan.add(new Cell(origin.offset(x, 2, z), wallBlock, wall, null));
    plan.add(new Cell(doorway, doorBlock, door, facing));
    for (var cell : plan) {
      if (!world.hasChunkAt(cell.pos()) || world.isOutsideBuildHeight(cell.pos()))
        throw new IllegalArgumentException("[UNLOADED_TARGET] 施工区未加载或超出高度");
      if (!matches(cell) && (!world.isEmptyBlock(cell.pos())
          || cell.door() && !world.isEmptyBlock(cell.pos().above())))
        throw new IllegalArgumentException("[SITE_OCCUPIED] 营地有其他方块：" + cell.pos());
    }
  }

  private static Block blockItem(String id) {
    var key = ResourceLocation.tryParse(id);
    if (key != null && BuiltInRegistries.ITEM.get(key) instanceof BlockItem item)
      return item.getBlock();
    throw new IllegalArgumentException("[INVALID_ARGUMENT] 需要有效的方块物品 ID：" + id);
  }

  @Override void tick() {
    Actions.status = "营地施工 · " + next + "/" + plan.size();
    if (next >= plan.size()) {
      boolean complete = plan.stream().allMatch(cell -> world.hasChunkAt(cell.pos()) && matches(cell));
      result(complete ? "NONE" : "CONSTRUCTION_CHANGED",
          complete ? "已核对墙体、屋顶和门" : "已放置的结构发生变化，请重新观察施工区");
      return;
    }
    var cell = plan.get(next);
    if (!world.hasChunkAt(cell.pos())) {
      result("UNLOADED_TARGET", "施工点离开已加载范围");
      return;
    }
    if (matches(cell)) {
      reused++;
      next++;
      return;
    }
    if (!world.isEmptyBlock(cell.pos()) || cell.door() && !world.isEmptyBlock(cell.pos().above())) {
      result("SITE_OCCUPIED", "施工点已被占用：" + cell.pos());
      return;
    }
    var args = new JsonObject();
    args.addProperty("item", cell.item());
    args.addProperty("x", cell.pos().getX());
    args.addProperty("y", cell.pos().getY());
    args.addProperty("z", cell.pos().getZ());
    if (cell.door()) {
      var state = new JsonObject();
      state.addProperty("facing", cell.doorFacing().getName());
      args.add("state", state);
    }
    child("place_block", args, reply -> {
      if (!Rules.actionFailed(reply) && matches(cell)) {
        placed++;
        next++;
      } else result("CONSTRUCTION_INCOMPLETE", reply);
    });
  }

  private boolean matches(Cell cell) {
    var state = world.getBlockState(cell.pos());
    if (!state.is(cell.block())) return false;
    if (!cell.door()) return true;
    var upper = world.getBlockState(cell.pos().above());
    return state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
        && state.getValue(DoorBlock.FACING) == cell.doorFacing()
        && upper.is(cell.block()) && upper.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER
        && upper.getValue(DoorBlock.FACING) == cell.doorFacing();
  }

  private void result(String code, String detail) {
    var out = new JsonObject();
    out.addProperty("status", code.equals("NONE") ? "verified" : "partial");
    out.addProperty("business_success", code.equals("NONE"));
    out.addProperty("reason_code", code);
    out.addProperty("detail", Rules.clean(detail, 400));
    out.addProperty("verified_steps", next);
    out.addProperty("placed_steps", placed);
    out.addProperty("reused_steps", reused);
    out.addProperty("total_steps", plan.size());
    finish(out.toString());
  }
}
