package nc.tile.processor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;

import nc.ModCheck;
import nc.config.NCConfig;
import nc.init.NCItems;
import nc.network.tile.ProcessorUpdatePacket;
import nc.recipe.AbstractRecipeHandler;
import nc.recipe.NCRecipes;
import nc.recipe.ProcessorRecipe;
import nc.recipe.ProcessorRecipeHandler;
import nc.recipe.RecipeInfo;
import nc.recipe.RecipeMatchResult;
import nc.recipe.ingredient.IFluidIngredient;
import nc.recipe.ingredient.IItemIngredient;
import nc.tile.IGui;
import nc.tile.energy.ITileEnergy;
import nc.tile.energyFluid.TileEnergyFluidSidedInventory;
import nc.tile.fluid.ITileFluid;
import nc.tile.internal.energy.EnergyConnection;
import nc.tile.internal.fluid.Tank;
import nc.tile.internal.fluid.TankSorption;
import nc.tile.internal.inventory.ItemSorption;
import nc.tile.inventory.ITileInventory;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.MathHelper;

public class TileItemFluidProcessor extends TileEnergyFluidSidedInventory implements IItemFluidProcessor, IGui<ProcessorUpdatePacket>, IUpgradable {
	
	public final int defaultProcessTime, defaultProcessPower;
	public double baseProcessTime, baseProcessPower, baseProcessRadiation;
	public final int itemInputSize, fluidInputSize, itemOutputSize, fluidOutputSize;
	
	public double time;
	public boolean isProcessing, canProcessInputs;
	
	public final boolean shouldLoseProgress, hasUpgrades;
	public final int guiID;
	
	public final NCRecipes.Type recipeType;
	protected RecipeInfo<ProcessorRecipe> recipeInfo, cachedRecipeInfo;
	
	protected Set<EntityPlayer> playersToUpdate;
	
	public TileItemFluidProcessor(String name, int itemInSize, int fluidInSize, int itemOutSize, int fluidOutSize, @Nonnull List<ItemSorption> itemSorptions, @Nonnull List<Integer> fluidCapacity, @Nonnull List<TankSorption> tankSorptions, List<List<String>> allowedFluids, int time, int power, boolean shouldLoseProgress, @Nonnull NCRecipes.Type recipeType) {
		this(name, itemInSize, fluidInSize, itemOutSize, fluidOutSize, itemSorptions, fluidCapacity, tankSorptions, allowedFluids, time, power, shouldLoseProgress, false, recipeType, 1);
	}
	
	public TileItemFluidProcessor(String name, int itemInSize, int fluidInSize, int itemOutSize, int fluidOutSize, @Nonnull List<ItemSorption> itemSorptions, @Nonnull List<Integer> fluidCapacity, @Nonnull List<TankSorption> tankSorptions, List<List<String>> allowedFluids, int time, int power, boolean shouldLoseProgress, @Nonnull NCRecipes.Type recipeType, int guiID) {
		this(name, itemInSize, fluidInSize, itemOutSize, fluidOutSize, itemSorptions, fluidCapacity, tankSorptions, allowedFluids, time, power, shouldLoseProgress, true, recipeType, guiID);
	}
	
	public TileItemFluidProcessor(String name, int itemInSize, int fluidInSize, int itemOutSize, int fluidOutSize, @Nonnull List<ItemSorption> itemSorptions, @Nonnull List<Integer> fluidCapacity, @Nonnull List<TankSorption> tankSorptions, List<List<String>> allowedFluids, int time, int power, boolean shouldLoseProgress, boolean upgrades, @Nonnull NCRecipes.Type recipeType, int guiID) {
		super(name, itemInSize + itemOutSize + (upgrades ? 2 : 0), ITileInventory.inventoryConnectionAll(itemSorptions), IProcessor.getBaseCapacity(recipeType), power != 0 ? ITileEnergy.energyConnectionAll(EnergyConnection.IN) : ITileEnergy.energyConnectionAll(EnergyConnection.NON), fluidCapacity, fluidCapacity, allowedFluids, ITileFluid.fluidConnectionAll(tankSorptions));
		itemInputSize = itemInSize;
		fluidInputSize = fluidInSize;
		itemOutputSize = itemOutSize;
		fluidOutputSize = fluidOutSize;
		
		defaultProcessTime = time;
		defaultProcessPower = power;
		baseProcessTime = time;
		baseProcessPower = power;
		
		this.shouldLoseProgress = shouldLoseProgress;
		hasUpgrades = upgrades;
		this.guiID = guiID;
		setInputTanksSeparated(fluidInSize > 1);
		
		this.recipeType = recipeType;
		
		playersToUpdate = new HashSet<EntityPlayer>();
	}
	
	public static List<ItemSorption> defaultItemSorptions(int inSize, int outSize, boolean upgrades) {
		List<ItemSorption> itemSorptions = new ArrayList<ItemSorption>();
		for (int i = 0; i < inSize; i++) itemSorptions.add(ItemSorption.IN);
		for (int i = 0; i < outSize; i++) itemSorptions.add(ItemSorption.OUT);
		if (upgrades) {
			itemSorptions.add(ItemSorption.IN);
			itemSorptions.add(ItemSorption.IN);
		}
		return itemSorptions;
	}
	
	public static List<Integer> defaultTankCapacities(int capacity, int inSize, int outSize) {
		List<Integer> tankCapacities = new ArrayList<Integer>();
		for (int i = 0; i < inSize + outSize; i++) tankCapacities.add(capacity);
		return tankCapacities;
	}
	
	public static List<TankSorption> defaultTankSorptions(int inSize, int outSize) {
		List<TankSorption> tankSorptions = new ArrayList<TankSorption>();
		for (int i = 0; i < inSize; i++) tankSorptions.add(TankSorption.IN);
		for (int i = 0; i < outSize; i++) tankSorptions.add(TankSorption.OUT);
		return tankSorptions;
	}
	
	// Ticking
	
	@Override
	public void onAdded() {
		super.onAdded();
		if (!world.isRemote) {
			refreshRecipe();
			refreshActivity();
			refreshUpgrades();
			isProcessing = isProcessing();
		}
	}
	
	@Override
	public void update() {
		super.update();
		updateProcessor();
	}
	
	public void updateProcessor() {
		if (!world.isRemote) {
			boolean wasProcessing = isProcessing;
			isProcessing = isProcessing();
			boolean shouldUpdate = false;
			if (isProcessing) process();
			else {
				getRadiationSource().setRadiationLevel(0D);
				if (time > 0 && !isHaltedByRedstone()) loseProgress();
			}
			if (wasProcessing != isProcessing) {
				shouldUpdate = true;
				updateBlockType();
				sendUpdateToAllPlayers();
			}
			sendUpdateToListeningPlayers();
			if (shouldUpdate) markDirty();
		}
	}
	
	public void updateBlockType() {
		if (ModCheck.ic2Loaded()) removeTileFromENet();
		setState(isProcessing);
		world.notifyNeighborsOfStateChange(pos, getBlockType(), true);
		if (ModCheck.ic2Loaded()) addTileToENet();
	}
	
	@Override
	public void refreshRecipe() {
		RecipeMatchResult matchResult = recipeInfo == null ? RecipeMatchResult.FAIL : recipeInfo.getRecipe().matchInputs(getItemInputs(), getFluidInputs());
		if (!matchResult.matches()) {
			/** Temporary caching while looking for recipe map solution */
			matchResult = cachedRecipeInfo == null ? RecipeMatchResult.FAIL : cachedRecipeInfo.getRecipe().matchInputs(getItemInputs(), getFluidInputs());
			if (matchResult.matches()) {
				recipeInfo = new RecipeInfo(cachedRecipeInfo.getRecipe(), matchResult);
			}
			else {
				recipeInfo = getRecipeHandler().getRecipeInfoFromInputs(getItemInputs(), getFluidInputs());
			}
			if (recipeInfo != null) {
				cachedRecipeInfo = recipeInfo;
			}
		}
		else recipeInfo = new RecipeInfo(recipeInfo.getRecipe(), matchResult);
	}
	
	@Override
	public void refreshActivity() {
		canProcessInputs = canProcessInputs(false);
	}
	
	@Override
	public void refreshActivityOnProduction() {
		canProcessInputs = canProcessInputs(true);
	}
	
	// Processor Stats
	
	public double getProcessTime() {
		return Math.max(1, baseProcessTime/getSpeedMultiplier());
	}
	
	public int getProcessPower() {
		return Math.min(Integer.MAX_VALUE, (int) (baseProcessPower*getPowerMultiplier()));
	}
	
	public double getProcessEnergy() {
		return getProcessTime()*getProcessPower();
	}
	
	public boolean setRecipeStats() {
		if (recipeInfo == null) {
			baseProcessTime = defaultProcessTime;
			baseProcessPower = defaultProcessPower;
			baseProcessRadiation = 0D;
			return false;
		}
		baseProcessTime = recipeInfo.getRecipe().getBaseProcessTime(defaultProcessTime);
		baseProcessPower = recipeInfo.getRecipe().getBaseProcessPower(defaultProcessPower);
		baseProcessRadiation = recipeInfo.getRecipe().getBaseProcessRadiation();
		return true;
	}
	
	public void setCapacityFromSpeed() {
		getEnergyStorage().setStorageCapacity((int)MathHelper.clamp(NCConfig.machine_update_rate*defaultProcessPower*getPowerMultiplier(), IProcessor.getBaseCapacity(recipeType), Integer.MAX_VALUE));
		getEnergyStorage().setMaxTransfer((int)MathHelper.clamp(NCConfig.machine_update_rate*defaultProcessPower*getPowerMultiplier(), IProcessor.getBaseCapacity(recipeType), Integer.MAX_VALUE));
	}
	
	private int getMaxEnergyModified() { // Needed for Galacticraft
		return ModCheck.galacticraftLoaded() ? getMaxEnergyStored() - 16 : getMaxEnergyStored();
	}
	
	// Processing
	
	public boolean isProcessing() {
		return readyToProcess() && !isHaltedByRedstone();
	}
	
	public boolean isHaltedByRedstone() {
		return getRedstoneControl() && getIsRedstonePowered();
	}
	
	public boolean readyToProcess() {
		return canProcessInputs && hasSufficientEnergy();
	}
	
	public boolean canProcessInputs(boolean justProduced) {
		if (!setRecipeStats()) return false;
		else if (!justProduced && time >= baseProcessTime) return true;
		return canProduceProducts();
	}
	
	public boolean hasSufficientEnergy() {
		return (time <= 0 && ((getProcessEnergy() >= getMaxEnergyModified() && getEnergyStored() >= getMaxEnergyModified()) || getProcessEnergy() <= getEnergyStored())) || (time > 0 && getEnergyStored() >= getProcessPower());
	}
	
	public boolean canProduceProducts() {
		for (int j = 0; j < itemOutputSize; j++) {
			IItemIngredient itemProduct = getItemProducts().get(j);
			if (itemProduct.getMaxStackSize(0) <= 0) continue;
			if (itemProduct.getStack() == null || itemProduct.getStack() == ItemStack.EMPTY) return false;
			else if (!getInventoryStacks().get(j + itemInputSize).isEmpty()) {
				if (!getInventoryStacks().get(j + itemInputSize).isItemEqual(itemProduct.getStack())) {
					return false;
				} else if (getInventoryStacks().get(j + itemInputSize).getCount() + itemProduct.getMaxStackSize(0) > getInventoryStacks().get(j + itemInputSize).getMaxStackSize()) {
					return false;
				}
			}
		}
		for(int j = 0; j < fluidOutputSize; j++) {
			IFluidIngredient fluidProduct = getFluidProducts().get(j);
			if (fluidProduct.getMaxStackSize(0) <= 0) continue;
			if (fluidProduct.getStack() == null) return false;
			else if (!getTanks().get(j + fluidInputSize).isEmpty()) {
				if (!getTanks().get(j + fluidInputSize).getFluid().isFluidEqual(fluidProduct.getStack())) {
					return false;
				} else if (!getVoidExcessFluidOutput(j) && getTanks().get(j + fluidInputSize).getFluidAmount() + fluidProduct.getMaxStackSize(0) > getTanks().get(j + fluidInputSize).getCapacity()) {
					return false;
				}
			}
		}
		return true;
	}
	
	public void process() {
		time += getSpeedMultiplier();
		getEnergyStorage().changeEnergyStored(-getProcessPower());
		getRadiationSource().setRadiationLevel(baseProcessRadiation*getSpeedMultiplier());
		if (time >= baseProcessTime) finishProcess();
	}
	
	public void finishProcess() {
		double oldProcessTime = baseProcessTime;
		produceProducts();
		refreshRecipe();
		if (!setRecipeStats()) {
			time = 0;
			for (int i = 0; i < fluidInputSize; i++) {
				if (getVoidUnusableFluidInput(i)) getTanks().get(i).setFluid(null);
			}
		}
		else time = MathHelper.clamp(time - oldProcessTime, 0D, baseProcessTime);
		refreshActivityOnProduction();
		if (!canProcessInputs) time = 0;
	}
	
	public void produceProducts() {
		if (recipeInfo == null) return;
		List<Integer> itemInputOrder = recipeInfo.getItemInputOrder();
		List<Integer> fluidInputOrder = recipeInfo.getFluidInputOrder();
		if (itemInputOrder == AbstractRecipeHandler.INVALID || fluidInputOrder == AbstractRecipeHandler.INVALID) return;
		
		for (int i = 0; i < itemInputSize; i++) {
			int itemIngredientStackSize = getItemIngredients().get(itemInputOrder.get(i)).getMaxStackSize(recipeInfo.getItemIngredientNumbers().get(i));
			if (itemIngredientStackSize > 0) getInventoryStacks().get(i).shrink(itemIngredientStackSize);
			if (getInventoryStacks().get(i).getCount() <= 0) getInventoryStacks().set(i, ItemStack.EMPTY);
		}
		for (int i = 0; i < fluidInputSize; i++) {
			int fluidIngredientStackSize = getFluidIngredients().get(fluidInputOrder.get(i)).getMaxStackSize(recipeInfo.getFluidIngredientNumbers().get(i));
			if (fluidIngredientStackSize > 0) getTanks().get(i).changeFluidAmount(-fluidIngredientStackSize);
			if (getTanks().get(i).getFluidAmount() <= 0) getTanks().get(i).setFluidStored(null);
		}
		for (int j = 0; j < itemOutputSize; j++) {
			IItemIngredient itemProduct = getItemProducts().get(j);
			if (itemProduct.getMaxStackSize(0) <= 0) continue;
			if (getInventoryStacks().get(j + itemInputSize).isEmpty()) {
				getInventoryStacks().set(j + itemInputSize, itemProduct.getNextStack(0));
			} else if (getInventoryStacks().get(j + itemInputSize).isItemEqual(itemProduct.getStack())) {
				getInventoryStacks().get(j + itemInputSize).grow(itemProduct.getNextStackSize(0));
			}
		}
		for (int j = 0; j < fluidOutputSize; j++) {
			IFluidIngredient fluidProduct = getFluidProducts().get(j);
			if (fluidProduct.getMaxStackSize(0) <= 0) continue;
			if (getTanks().get(j + fluidInputSize).isEmpty()) {
				getTanks().get(j + fluidInputSize).setFluidStored(fluidProduct.getNextStack(0));
			} else if (getTanks().get(j + fluidInputSize).getFluid().isFluidEqual(fluidProduct.getStack())) {
				getTanks().get(j + fluidInputSize).changeFluidAmount(fluidProduct.getNextStackSize(0));
			}
		}
	}
	
	public void loseProgress() {
		time = MathHelper.clamp(time - 1.5D*getSpeedMultiplier(), 0D, baseProcessTime);
	}
	
	// IProcessor
	
	@Override
	public ProcessorRecipeHandler getRecipeHandler() {
		return recipeType.getRecipeHandler();
	}
	
	@Override
	public List<ItemStack> getItemInputs() {
		return getInventoryStacks().subList(0, itemInputSize);
	}
	
	@Override
	public List<Tank> getFluidInputs() {
		return getTanks().subList(0, fluidInputSize);
	}

	@Override
	public List<IItemIngredient> getItemIngredients() {
		return recipeInfo.getRecipe().itemIngredients();
	}
	
	@Override
	public List<IFluidIngredient> getFluidIngredients() {
		return recipeInfo.getRecipe().fluidIngredients();
	}
	
	@Override
	public List<IItemIngredient> getItemProducts() {
		return recipeInfo.getRecipe().itemProducts();
	}
	
	@Override
	public List<IFluidIngredient> getFluidProducts() {
		return recipeInfo.getRecipe().fluidProducts();
	}
	
	// Upgrades
	
	@Override
	public boolean hasUpgrades() {
		return hasUpgrades;
	}
	
	@Override
	public int getSpeedUpgradeSlot() {
		return itemInputSize + itemOutputSize;
	}
	
	@Override
	public int getEnergyUpgradeSlot() {
		return itemInputSize + itemOutputSize + 1;
	}
	
	@Override
	public int getSpeedCount() {
		return hasUpgrades ? getInventoryStacks().get(getSpeedUpgradeSlot()).getCount() + 1 : 1;
	}
	
	@Override
	public int getEnergyCount() {
		return hasUpgrades ? Math.min(getSpeedCount(), getInventoryStacks().get(getEnergyUpgradeSlot()).getCount() + 1) : 1;
	}
	
	@Override
	public void refreshUpgrades() {
		setCapacityFromSpeed();
	}
	
	// IC2 Tiers
	
	@Override
	public int getEUSourceTier() {
		return 1;
	}
		
	@Override
	public int getEUSinkTier() {
		return 10;
	}
	
	// ITileInventory
	
	@Override
	public ItemStack decrStackSize(int slot, int amount) {
		ItemStack stack = super.decrStackSize(slot, amount);
		if (!world.isRemote) {
			if (slot < itemInputSize) {
				refreshRecipe();
				refreshActivity();
			}
			else if (slot < itemInputSize + itemOutputSize) {
				refreshActivity();
			}
			else if (slot == getSpeedUpgradeSlot() || slot == getEnergyUpgradeSlot()) {
				refreshUpgrades();
			}
		}
		return stack;
	}
	
	@Override
	public void setInventorySlotContents(int slot, ItemStack stack) {
		super.setInventorySlotContents(slot, stack);
		if (!world.isRemote) {
			if (slot < itemInputSize) {
				refreshRecipe();
				refreshActivity();
			}
			else if (slot < itemInputSize + itemOutputSize) {
				refreshActivity();
			}
			else if (slot == getSpeedUpgradeSlot() || slot == getEnergyUpgradeSlot()) {
				refreshUpgrades();
			}
		}
	}
	
	@Override
	public void markDirty() {
		refreshRecipe();
		refreshActivity();
		refreshUpgrades();
		super.markDirty();
	}
	
	@Override
	public boolean isItemValidForSlot(int slot, ItemStack stack) {
		if (stack == ItemStack.EMPTY) return false;
		if (hasUpgrades) {
			if (stack.getItem() == NCItems.upgrade) {
				if (slot == getSpeedUpgradeSlot()) return stack.getMetadata() == 0;
				else if (slot == getEnergyUpgradeSlot()) return stack.getMetadata() == 1;
			}
		}
		if (slot >= itemInputSize) return false;
		return NCConfig.smart_processor_input ? getRecipeHandler().isValidItemInput(stack, getInventoryStacks().get(slot), inputItemStacksExcludingSlot(slot)) : getRecipeHandler().isValidItemInput(stack);
	}
	
	public List<ItemStack> inputItemStacksExcludingSlot(int slot) {
		List<ItemStack> inputItemsExcludingSlot = new ArrayList<ItemStack>(getItemInputs());
		inputItemsExcludingSlot.remove(slot);
		return inputItemsExcludingSlot;
	}
	
	@Override
	public boolean canInsertItem(int slot, ItemStack stack, EnumFacing side) {
		return super.canInsertItem(slot, stack, side) && isItemValidForSlot(slot, stack);
	}
	
	// NBT
	
	@Override
	public NBTTagCompound writeAll(NBTTagCompound nbt) {
		super.writeAll(nbt);
		nbt.setDouble("time", time);
		nbt.setBoolean("isProcessing", isProcessing);
		nbt.setBoolean("canProcessInputs", canProcessInputs);
		return nbt;
	}
	
	@Override
	public void readAll(NBTTagCompound nbt) {
		super.readAll(nbt);
		time = nbt.getDouble("time");
		isProcessing = nbt.getBoolean("isProcessing");
		canProcessInputs = nbt.getBoolean("canProcessInputs");
		if (nbt.hasKey("redstoneControl")) {
			setRedstoneControl(nbt.getBoolean("redstoneControl"));
		} else setRedstoneControl(true);
	}
	
	// IGui
	
	@Override
	public int getGuiID() {
		return guiID;
	}
	
	@Override
	public Set<EntityPlayer> getPlayersToUpdate() {
		return playersToUpdate;
	}
	
	@Override
	public ProcessorUpdatePacket getGuiUpdatePacket() {
		return new ProcessorUpdatePacket(pos, time, getEnergyStored(), baseProcessTime, baseProcessPower, getTanks());
	}
	
	@Override
	public void onGuiPacket(ProcessorUpdatePacket message) {
		time = message.time;
		getEnergyStorage().setEnergyStored(message.energyStored);
		baseProcessTime = message.baseProcessTime;
		baseProcessPower = message.baseProcessPower;
		for (int i = 0; i < getTanks().size(); i++) getTanks().get(i).readInfo(message.tanksInfo.get(i));
	}
}
