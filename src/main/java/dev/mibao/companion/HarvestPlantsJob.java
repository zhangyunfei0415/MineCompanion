package dev.mibao.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Harvests upper sections or ripe fruit while leaving the root/stem in place. */
final class HarvestPlantsJob extends Actions.Job {
  private final String crop;
  private final String drop;
  private final Block plant, stem;
  private final boolean replant;
  private final int count;
  private final Iterator<BlockPos> scan;
  private final ArrayList<BlockPos> candidates = new ArrayList<>();
  private final JsonArray cutSites = new JsonArray();
  private int index, confirmed, unknown, pickupVerifiedSites, inventoryDelta, replanted;
  private String collectionDetail = "";

  HarvestPlantsJob(JsonObject args, Consumer<String> done) {
    super(done);
    crop = SkillCatalog.required(args, "crop");
    drop = switch (crop) {
      case "minecraft:melon" -> "minecraft:melon_slice";
      case "minecraft:cocoa" -> "minecraft:cocoa_beans";
      case "minecraft:sweet_berry_bush" -> "minecraft:sweet_berries";
      default -> crop;
    };
    plant = switch (crop) {
      case "minecraft:sugar_cane" -> Blocks.SUGAR_CANE;
      case "minecraft:bamboo" -> Blocks.BAMBOO;
      case "minecraft:cactus" -> Blocks.CACTUS;
      case "minecraft:pumpkin" -> Blocks.PUMPKIN;
      case "minecraft:melon" -> Blocks.MELON;
      case "minecraft:cocoa" -> Blocks.COCOA;
      case "minecraft:nether_wart" -> Blocks.NETHER_WART;
      case "minecraft:sweet_berry_bush" -> Blocks.SWEET_BERRY_BUSH;
      default -> throw new IllegalArgumentException("[INVALID_ARGUMENT] 当前采收工具不支持：" + crop);
    };
    replant = (plant == Blocks.COCOA || plant == Blocks.NETHER_WART)
        && (!args.has("replant") || args.get("replant").getAsBoolean());
    stem = plant == Blocks.PUMPKIN ? Blocks.ATTACHED_PUMPKIN_STEM
        : plant == Blocks.MELON ? Blocks.ATTACHED_MELON_STEM : null;
    count = SkillCatalog.bounded(args, "count", 8, 1, 16);
    int radius = SkillCatalog.bounded(args, "radius", 8, 1, 12);
    var origin = Minecraft.getInstance().player.blockPosition();
    scan = BlockPos.betweenClosed(origin.offset(-radius, -2, -radius),
        origin.offset(radius, 3, radius)).iterator();
  }

  @Override void tick() {
    Actions.status = "采收 · " + crop + " · " + confirmed + "/" + count;
    if (scan.hasNext()) {
      for (int i = 0; i < 128 && scan.hasNext(); i++) {
        var p = scan.next();
        if (!world.hasChunkAt(p)) unknown++;
        else if (eligible(p)) candidates.add(p.immutable());
      }
      if (!scan.hasNext()) candidates.sort(Comparator.comparingDouble(
          p -> p.distSqr(Minecraft.getInstance().player.blockPosition())));
      return;
    }
    if (confirmed >= count || index >= candidates.size()) {
      result(confirmed >= count ? "NONE" : "HARVEST_TARGET_INCOMPLETE",
          candidates.isEmpty() ? "已观察范围内没有符合采收条件的植株" : "已尝试采收可见成熟植株");
      return;
    }
    var target = candidates.get(index++);
    if (!eligible(target)) return;
    int before = totalDrop(PlayerEvidence.actual(drop));
    if (plant == Blocks.SWEET_BERRY_BUSH) {
      child("use_item_on_block", coordinates(target), receipt -> {
        var state = world.getBlockState(target);
        if (state.is(plant) && state.getValue(BlockStateProperties.AGE_3) == 1) {
          confirm(target);
          int added = Math.max(0, totalDrop(PlayerEvidence.actual(drop)) - before);
          inventoryDelta += added;
          if (added > 0 && !Rules.actionFailed(receipt)) pickupVerifiedSites++;
        } else result("HARVEST_PARTIAL", receipt);
      });
      return;
    }
    var original = world.getBlockState(target);
    MiningJob.lastReceipt = null;
    CollectionJob.lastReceipt = null;
    Actions.job = new MiningJob(crop, 1, 24, target, false, 3, receipt -> {
      if (epoch != Agent.epoch || Minecraft.getInstance().level != world) return;
      Actions.job = this;
      var proof = MiningJob.lastReceipt;
      if (proof != null && proof.epoch() == epoch && proof.positions().contains(target)) {
        confirm(target);
        var pickup = CollectionJob.lastReceipt;
        if (pickup != null && pickup.epoch() == epoch && pickup.inventoryVerified()) {
          int added = Math.max(0, totalDrop(pickup.inventory()) - before);
          inventoryDelta += added;
          if (!Rules.actionFailed(receipt) && added > 0) pickupVerifiedSites++;
        }
        collectionDetail = Rules.clean(receipt, 420);
        if (replant) {
          var args = coordinates(target);
          args.addProperty("item", drop);
          var face = plant == Blocks.COCOA
              ? original.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite() : Direction.UP;
          args.addProperty("face", face.getName());
          child("place_block", args, reply -> {
            if (!Rules.actionFailed(reply) && world.getBlockState(target).is(plant)) replanted++;
            else result("REPLANT_INCOMPLETE", "采收已核实，补种未完成：" + reply);
          });
          return;
        }
      }
      if (Rules.actionFailed(receipt)) {
        if (confirmed >= count)
          result("NONE", "所需切点均已确认，拾取情况见pickup_state");
        else result("HARVEST_PARTIAL", receipt);
      }
    }).withTargetGuard(this::eligible).withCollectionFilter(drop);
  }

  private void confirm(BlockPos target) {
    confirmed++;
    var site = new JsonArray();
    site.add(target.getX());
    site.add(target.getY());
    site.add(target.getZ());
    cutSites.add(site);
  }

  private static JsonObject coordinates(BlockPos pos) {
    var args = new JsonObject();
    args.addProperty("x", pos.getX());
    args.addProperty("y", pos.getY());
    args.addProperty("z", pos.getZ());
    return args;
  }

  private int totalDrop(Map<String, Integer> items) {
    return items.entrySet().stream()
        .filter(x -> x.getKey().equals(drop) || x.getKey().startsWith(drop + "#"))
        .mapToInt(Map.Entry::getValue).sum();
  }

  private boolean eligible(BlockPos p) {
    if (!world.hasChunkAt(p) || !world.getBlockState(p).is(plant)) return false;
    var state = world.getBlockState(p);
    if (plant == Blocks.COCOA) return state.getValue(BlockStateProperties.AGE_2) == 2;
    if (plant == Blocks.NETHER_WART) return state.getValue(BlockStateProperties.AGE_3) == 3;
    if (plant == Blocks.SWEET_BERRY_BUSH) return state.getValue(BlockStateProperties.AGE_3) >= 2;
    if (stem == null) {
      return world.getBlockState(p.below()).is(plant) && !world.getBlockState(p.below(2)).is(plant);
    }
    for (var direction : Direction.Plane.HORIZONTAL) {
      var near = p.relative(direction);
      if (!world.hasChunkAt(near)) continue;
      var nearby = world.getBlockState(near);
      if (nearby.is(stem) && nearby.getValue(BlockStateProperties.HORIZONTAL_FACING) == direction.getOpposite())
        return true;
    }
    return false;
  }

  private void result(String code, String detail) {
    var out = new JsonObject();
    out.addProperty("status", code.equals("NONE") ? "verified" : "partial");
    out.addProperty("business_success", code.equals("NONE"));
    out.addProperty("reason_code", code);
    out.addProperty("detail", Rules.clean(detail, 500));
    out.addProperty("confirmed_cut_sites", confirmed);
    out.addProperty("requested_sites", count);
    out.addProperty("cut_verified", confirmed >= count);
    out.add("cut_positions", cutSites.deepCopy());
    out.addProperty("pickup_verified", confirmed > 0 && pickupVerifiedSites == confirmed);
    out.addProperty("pickup_verified_sites", pickupVerifiedSites);
    out.addProperty("pickup_state", confirmed == 0 ? "not_observed"
        : pickupVerifiedSites == confirmed ? "verified" : "unconfirmed");
    out.addProperty("confirmed_inventory_increase", inventoryDelta);
    out.addProperty("inventory_item", drop);
    out.addProperty("replanted_sites", replanted);
    if (!collectionDetail.isBlank()) out.addProperty("last_collection_detail", collectionDetail);
    out.addProperty("unknown_samples", unknown);
    finish(out.toString());
  }
}
