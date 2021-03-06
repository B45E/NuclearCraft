package nc.tile.internal.inventory;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;

public class InventoryConnection {
	
	private List<ItemSorption> sorptionList;
	
	public InventoryConnection(List<ItemSorption> sorptionList) {
		this.sorptionList = new ArrayList<ItemSorption>(sorptionList);
	}
	
	public InventoryConnection copy() {
		return new InventoryConnection(sorptionList);
	}
	
	public static InventoryConnection[] cloneArray(@Nonnull InventoryConnection[] connections) {
		InventoryConnection[] clone = new InventoryConnection[6];
		for (int i = 0; i < 6; i++) {
			clone[i] = connections[i].copy();
		}
		return clone;
	}
	
	public ItemSorption getItemSorption(int tankNumber) {
		return sorptionList.get(tankNumber);
	}
	
	public void setItemSorption(int tankNumber, ItemSorption sorption) {
		sorptionList.set(tankNumber, sorption);
	}
	
	public boolean canConnect() {
		for (ItemSorption sorption : sorptionList) {
			if (sorption.canConnect()) {
				return true;
			}
		}
		return false;
	}
	
	public int[] getSlotsForFace() {
		List<Integer> slotList = new ArrayList<Integer>();
		for (int i = 0; i < sorptionList.size(); i++) {
			if (getItemSorption(i).canConnect()) {
				slotList.add(i);
			}
		}
		return slotList.stream().mapToInt(i -> i).toArray();
	}
	
	public void toggleItemSorption(int tankNumber) {
		setItemSorption(tankNumber, getItemSorption(tankNumber).next());
	}
	
	public final NBTTagCompound writeToNBT(NBTTagCompound nbt, @Nonnull EnumFacing side) {
		NBTTagCompound connectionTag = new NBTTagCompound();
		for (int i = 0; i < sorptionList.size(); i++) {
			connectionTag.setInteger("sorption" + i, getItemSorption(i).ordinal());
		}
		nbt.setTag("inventoryConnection" + side.getIndex(), connectionTag);
		return nbt;

	}
	
	public final InventoryConnection readFromNBT(NBTTagCompound nbt, @Nonnull EnumFacing side) {
		if (nbt.hasKey("inventoryConnection" + side.getIndex())) {
			NBTTagCompound connectionTag = nbt.getCompoundTag("inventoryConnection" + side.getIndex());
			for (int i = 0; i < sorptionList.size(); i++) {
				if (connectionTag.hasKey("sorption" + i)) {
					setItemSorption(i, ItemSorption.values()[connectionTag.getInteger("sorption" + i)]);
				}
			}
			
		}
		return this;
	}
}
