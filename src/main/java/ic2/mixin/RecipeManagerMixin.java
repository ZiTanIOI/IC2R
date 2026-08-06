package ic2.mixin;

import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import ic2.core.IC2;
import ic2.core.util.LogCategory;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.common.crafting.conditions.ICondition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// 机械动力(及其他模组)会给 IC2 生成兼容配方，但使用的是 IC2 经典版的物品 ID
// (ic2:ingot_silver、ic2:nugget_tin、ic2:ingot_aluminium 等)。IC2 Refactored 把银/锡/铅/青铜/钢/铀
// 改名为 silver_ingot / tin_ingot / ...，且没有铝和任何粒(nugget)，导致解析这些配方时
// 直接报 "Item: ic2:ingot_silver does not exist" 而崩溃。
//
// 这里在配方被解析之前把经典 ID 重映射到 Refactored 的实际物品；若配方仍引用了 IC2 里不存在的
// 物品(如铝)，则直接跳过该配方，避免游戏崩溃。
@Mixin(RecipeManager.class)
public class RecipeManagerMixin
{
	private static final Map<String, String> CLASSIC_TO_REFACTORED = Map.ofEntries(
		Map.entry("ic2:ingot_silver", "ic2:silver_ingot"),
		Map.entry("ic2:ingot_tin", "ic2:tin_ingot"),
		Map.entry("ic2:ingot_lead", "ic2:lead_ingot"),
		Map.entry("ic2:ingot_bronze", "ic2:bronze_ingot"),
		Map.entry("ic2:ingot_steel", "ic2:steel_ingot"),
		Map.entry("ic2:ingot_uranium", "ic2:uranium_ingot"),
		// IC2 Refactored 没有粒，把 9 粒(1 锭)换算成 1 锭
		Map.entry("ic2:nugget_silver", "ic2:silver_ingot"),
		Map.entry("ic2:nugget_tin", "ic2:tin_ingot"),
		Map.entry("ic2:nugget_uranium", "ic2:uranium_ingot")
	);

	@Inject(method = "fromJson(Lnet/minecraft/resources/ResourceLocation;Lcom/google/gson/JsonObject;)Lnet/minecraft/world/item/crafting/Recipe;", at = @At("HEAD"), cancellable = true)
	private static void handleRecipe(ResourceLocation id, JsonObject json, CallbackInfoReturnable<Recipe<?>> cir)
	{
		if (json != null)
		{
			remapClassicItems(json);
			if (hasUnregisteredIc2Item(json))
			{
				IC2.log.warn(LogCategory.Recipe, "[IC2 Recipe] Skipping %s: it references an ic2 item that does not exist in IC2 Refactored", id);
				cir.setReturnValue(null);
				cir.cancel();
			}
		}
	}

	@Inject(method = "fromJson(Lnet/minecraft/resources/ResourceLocation;Lcom/google/gson/JsonObject;Lnet/minecraftforge/common/crafting/conditions/ICondition$IContext;)Lnet/minecraft/world/item/crafting/Recipe;", at = @At("HEAD"), cancellable = true)
	private static void handleRecipe(ResourceLocation id, JsonObject json, ICondition.IContext context, CallbackInfoReturnable<Recipe<?>> cir)
	{
		if (json != null)
		{
			remapClassicItems(json);
			if (hasUnregisteredIc2Item(json))
			{
				IC2.log.warn(LogCategory.Recipe, "[IC2 Recipe] Skipping %s: it references an ic2 item that does not exist in IC2 Refactored", id);
				cir.setReturnValue(null);
				cir.cancel();
			}
		}
	}

	private static void remapClassicItems(JsonElement element)
	{
		if (element == null || element.isJsonNull())
		{
			return;
		}
		if (element.isJsonArray())
		{
			JsonArray array = element.getAsJsonArray();
			for (int i = 0; i < array.size(); i++)
			{
				JsonElement child = array.get(i);
				if (child.isJsonPrimitive() && child.getAsJsonPrimitive().isString())
				{
					String mapped = CLASSIC_TO_REFACTORED.get(child.getAsString());
					if (mapped != null)
					{
						array.set(i, new JsonPrimitive(mapped));
					}
				}
				else
				{
					remapClassicItems(child);
				}
			}
			return;
		}
		if (!element.isJsonObject())
		{
			return;
		}
		JsonObject obj = element.getAsJsonObject();
		JsonElement itemField = obj.get("item");
		if (itemField != null && itemField.isJsonPrimitive() && itemField.getAsJsonPrimitive().isString())
		{
			String id = itemField.getAsString();
			String mapped = CLASSIC_TO_REFACTORED.get(id);
			if (mapped != null)
			{
				obj.addProperty("item", mapped);
				// 9 粒 = 1 锭，洗矿产出统一换算为 1 锭
				if (id.startsWith("ic2:nugget_") && obj.has("count"))
				{
					obj.addProperty("count", 1);
				}
			}
		}
		for (String key : obj.keySet())
		{
			JsonElement value = obj.get(key);
			if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString())
			{
				String mapped = CLASSIC_TO_REFACTORED.get(value.getAsString());
				if (mapped != null)
				{
					obj.addProperty(key, mapped);
				}
			}
			else if (value.isJsonArray() || value.isJsonObject())
			{
				remapClassicItems(value);
			}
		}
	}

	private static boolean hasUnregisteredIc2Item(JsonElement element)
	{
		if (element == null || element.isJsonNull())
		{
			return false;
		}
		if (element.isJsonArray())
		{
			for (JsonElement child : element.getAsJsonArray())
			{
				if (hasUnregisteredIc2Item(child))
				{
					return true;
				}
			}
			return false;
		}
		if (element.isJsonObject())
		{
			for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet())
			{
				// "type" 是序列化器 ID（如 ic2:shaped、ic2:compressor），"tag" 是物品标签
				// （如 ic2:forge_hammers），都不是物品 ID，不能当作 ic2 物品存在性检查，
				// 否则所有 IC2 配方都会被误判跳过。
				if ("type".equals(entry.getKey()) || "tag".equals(entry.getKey()))
				{
					continue;
				}
				if (hasUnregisteredIc2Item(entry.getValue()))
				{
					return true;
				}
			}
			return false;
		}
		if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString())
		{
			String s = element.getAsString();
			if (s.startsWith("ic2:"))
			{
				ResourceLocation id = ResourceLocation.tryParse(s);
				return id == null || !ForgeRegistries.ITEMS.containsKey(id);
			}
		}
		return false;
	}
}
