package gregtech.common.covers;

import codechicken.lib.raytracer.CuboidRayTraceResult;
import codechicken.lib.render.CCRenderState;
import codechicken.lib.render.pipeline.IVertexOperation;
import codechicken.lib.vec.Cuboid6;
import codechicken.lib.vec.Matrix4;
import com.google.common.math.IntMath;
import gregtech.api.GTValues;
import gregtech.api.capability.GregtechDataCodes;
import gregtech.api.capability.GregtechTileCapabilities;
import gregtech.api.capability.IControllable;
import gregtech.api.capability.impl.FluidHandlerDelegate;
import gregtech.api.cover.CoverWithUI;
import gregtech.api.cover2.CoverBase;
import gregtech.api.cover2.CoverDefinition2;
import gregtech.api.cover2.CoverableView;
import gregtech.api.gui.GuiTextures;
import gregtech.api.gui.ModularUI;
import gregtech.api.gui.widgets.*;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.util.GTTransferUtils;
import gregtech.client.renderer.texture.Textures;
import gregtech.client.renderer.texture.cube.SimpleSidedCubeRenderer;
import gregtech.common.covers.filter.FluidFilterContainer;
import net.minecraft.block.Block;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Function;
import java.util.function.IntSupplier;

public class CoverPump extends CoverBase implements CoverWithUI, ITickable, IControllable {

    public final int tier;
    public final int maxFluidTransferRate;
    protected int transferRate;
    protected PumpMode pumpMode = PumpMode.EXPORT;
    protected ManualImportExportMode manualImportExportMode = ManualImportExportMode.DISABLED;
    protected DistributionMode distributionMode = DistributionMode.INSERT_FIRST;
    protected int fluidLeftToTransferLastSecond;
    private CoverableFluidHandlerWrapper fluidHandlerWrapper;
    protected boolean isWorkingAllowed = true;
    protected FluidFilterContainer fluidFilter;
    protected BucketMode bucketMode = BucketMode.MILLI_BUCKET;

    public CoverPump(@NotNull CoverDefinition2 definition, @NotNull CoverableView coverableView,
                     @NotNull EnumFacing attachedSide, int tier, int mbPerTick) {
        super(definition, coverableView, attachedSide);
        this.tier = tier;
        this.maxFluidTransferRate = mbPerTick;
        this.transferRate = mbPerTick;
        this.fluidLeftToTransferLastSecond = transferRate;
        this.fluidFilter = new FluidFilterContainer(this, this::shouldShowTip);
    }

    protected boolean shouldShowTip() {
        return false;
    }

    public void setTransferRate(int transferRate) {
        this.transferRate = transferRate;
        markDirty();
    }

    public int getTransferRate() {
        return transferRate;
    }

    protected void adjustTransferRate(int amount) {
        amount *= this.bucketMode == BucketMode.BUCKET ? 1000 : 1;
        setTransferRate(MathHelper.clamp(transferRate + amount, 1, maxFluidTransferRate));
    }

    public void setPumpMode(PumpMode pumpMode) {
        this.pumpMode = pumpMode;
        writeCustomData(GregtechDataCodes.UPDATE_COVER_MODE, buf -> buf.writeEnumValue(pumpMode));
        markDirty();
    }

    public PumpMode getPumpMode() {
        return pumpMode;
    }

    public void setBucketMode(BucketMode bucketMode) {
        this.bucketMode = bucketMode;
        if (this.bucketMode == BucketMode.BUCKET)
            setTransferRate(transferRate / 1000 * 1000);
        markDirty();
    }

    public BucketMode getBucketMode() {
        return bucketMode;
    }

    public ManualImportExportMode getManualImportExportMode() {
        return manualImportExportMode;
    }

    protected void setManualImportExportMode(ManualImportExportMode manualImportExportMode) {
        this.manualImportExportMode = manualImportExportMode;
        markDirty();
    }

    public FluidFilterContainer getFluidFilterContainer() {
        return fluidFilter;
    }

    @Override
    public void update() {
        long timer = getOffsetTimer();
        if (isWorkingAllowed && fluidLeftToTransferLastSecond > 0) {
            this.fluidLeftToTransferLastSecond -= doTransferFluids(fluidLeftToTransferLastSecond);
        }
        if (timer % 20 == 0) {
            this.fluidLeftToTransferLastSecond = transferRate;
        }
    }

    protected int doTransferFluids(int transferLimit) {
        BlockPos pos = getPos().offset(getAttachedSide());
        TileEntity tileEntity = getWorld().getTileEntity(pos);
        IFluidHandler fluidHandler = tileEntity == null ? null : tileEntity.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, getAttachedSide().getOpposite());
        IFluidHandler myFluidHandler = getCoverable().getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, getAttachedSide());
        if (fluidHandler == null || myFluidHandler == null) {
            return 0;
        }
        return doTransferFluidsInternal(myFluidHandler, fluidHandler, transferLimit);
    }

    protected int doTransferFluidsInternal(IFluidHandler myFluidHandler, IFluidHandler fluidHandler, int transferLimit) {
        if (pumpMode == PumpMode.IMPORT) {
            return GTTransferUtils.transferFluids(fluidHandler, myFluidHandler, transferLimit, fluidFilter::testFluidStack);
        } else if (pumpMode == PumpMode.EXPORT) {
            return GTTransferUtils.transferFluids(myFluidHandler, fluidHandler, transferLimit, fluidFilter::testFluidStack);
        }
        return 0;
    }

    protected boolean checkInputFluid(FluidStack fluidStack) {
        return fluidFilter.testFluidStack(fluidStack);
    }

    protected ModularUI buildUI(ModularUI.Builder builder, EntityPlayer player) {
        return builder.build(this, player);
    }

    protected String getUITitle() {
        return "cover.pump.title";
    }

    @SuppressWarnings("UnstableApiUsage")
    @Override
    public ModularUI createUI(EntityPlayer player) {
        WidgetGroup primaryGroup = new WidgetGroup();
        primaryGroup.addWidget(new LabelWidget(10, 5, getUITitle(), GTValues.VN[tier]));

        primaryGroup.addWidget(new ImageWidget(44, 20, 62, 20, GuiTextures.DISPLAY));

        primaryGroup.addWidget(new IncrementButtonWidget(136, 20, 30, 20, 1, 10, 100, 1000, this::adjustTransferRate)
                .setDefaultTooltip()
                .setShouldClientCallback(false));
        primaryGroup.addWidget(new IncrementButtonWidget(10, 20, 34, 20, -1, -10, -100, -1000, this::adjustTransferRate)
                .setDefaultTooltip()
                .setShouldClientCallback(false));

        TextFieldWidget2 textField = new TextFieldWidget2(45, 26, 60, 20, () -> bucketMode == BucketMode.BUCKET ? Integer.toString(transferRate / 1000) : Integer.toString(transferRate), val -> {
            if (val != null && !val.isEmpty()) {
                int amount = Integer.parseInt(val);
                if (this.bucketMode == BucketMode.BUCKET) {
                    amount = IntMath.saturatedMultiply(amount, 1000);
                }
                setTransferRate(amount);
            }
        })
                .setCentered(true)
                .setNumbersOnly(1, bucketMode == BucketMode.BUCKET ? maxFluidTransferRate / 1000 : maxFluidTransferRate)
                .setMaxLength(8);
        primaryGroup.addWidget(textField);

        primaryGroup.addWidget(new CycleButtonWidget(106, 20, 30, 20,
                BucketMode.class, this::getBucketMode, mode -> {
            if (mode != bucketMode) {
                setBucketMode(mode);
            }
        }));

        primaryGroup.addWidget(new CycleButtonWidget(10, 43, 75, 18,
                PumpMode.class, this::getPumpMode, this::setPumpMode));

        primaryGroup.addWidget(new CycleButtonWidget(7, 160, 116, 20,
                ManualImportExportMode.class, this::getManualImportExportMode, this::setManualImportExportMode)
                .setTooltipHoverString("cover.universal.manual_import_export.mode.description"));

        this.fluidFilter.initUI(88, primaryGroup::addWidget);

        ModularUI.Builder builder = ModularUI.builder(GuiTextures.BACKGROUND, 176, 184 + 82)
                .widget(primaryGroup)
                .bindPlayerInventory(player.inventory, GuiTextures.SLOT, 7, 184);
        return buildUI(builder, player);
    }

    public static Function<String, String> getTextFieldValidator(IntSupplier maxSupplier) {
        int min = 1;
        return val -> {
            if (val.isEmpty()) {
                return String.valueOf(min);
            }
            int max = maxSupplier.getAsInt();
            int num;
            try {
                num = Integer.parseInt(val);
            } catch (NumberFormatException ignored) {
                return String.valueOf(max);
            }
            if (num < min) {
                return String.valueOf(min);
            }
            if (num > max) {
                return String.valueOf(max);
            }
            return val;
        };
    }

    @Override
    public @NotNull EnumActionResult onScrewdriverClick(@NotNull EntityPlayer playerIn, @NotNull EnumHand hand, @NotNull CuboidRayTraceResult hitResult) {
        if (!getWorld().isRemote) {
            openUI((EntityPlayerMP) playerIn);
        }
        return EnumActionResult.SUCCESS;
    }

    @Override
    public void readCustomData(int discriminator, @NotNull PacketBuffer buf) {
        super.readCustomData(discriminator, buf);
        if (discriminator == GregtechDataCodes.UPDATE_COVER_MODE) {
            this.pumpMode = buf.readEnumValue(PumpMode.class);
            scheduleRenderUpdate();
        }
    }

    @Override
    public void writeInitialSyncData(@NotNull PacketBuffer packetBuffer) {
        super.writeInitialSyncData(packetBuffer);
        packetBuffer.writeEnumValue(pumpMode);
    }

    @Override
    public void readInitialSyncData(@NotNull PacketBuffer packetBuffer) {
        super.readInitialSyncData(packetBuffer);
        this.pumpMode = packetBuffer.readEnumValue(PumpMode.class);
    }

    @Override
    public boolean canAttach(@NotNull CoverableView coverable, @NotNull EnumFacing side) {
        return coverable.hasCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, side);
    }

    @Override
    public boolean canInteractWithOutputSide() {
        return true;
    }

    @Override
    public void onRemoval() {
        NonNullList<ItemStack> drops = NonNullList.create();
        MetaTileEntity.clearInventory(drops, fluidFilter.getFilterInventory());
        for (ItemStack itemStack : drops) {
            Block.spawnAsEntity(getWorld(), getPos(), itemStack);
        }
    }

    @Override
    public void renderCover(@NotNull CCRenderState renderState, @NotNull Matrix4 translation, IVertexOperation[] pipeline, @NotNull Cuboid6 plateBox, @NotNull BlockRenderLayer layer) {
        if (pumpMode == PumpMode.EXPORT) {
            Textures.PUMP_OVERLAY.renderSided(getAttachedSide(), plateBox, renderState, pipeline, translation);
        } else {
            Textures.PUMP_OVERLAY_INVERTED.renderSided(getAttachedSide(), plateBox, renderState, pipeline, translation);
        }
    }

    @Override
    public <T> T getCapability(@NotNull Capability<T> capability, T defaultValue) {
        if (capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY) {
            if (defaultValue == null) {
                return null;
            }
            IFluidHandler delegate = (IFluidHandler) defaultValue;
            if (fluidHandlerWrapper == null || fluidHandlerWrapper.delegate != delegate) {
                this.fluidHandlerWrapper = new CoverableFluidHandlerWrapper(delegate);
            }
            return CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY.cast(fluidHandlerWrapper);
        }
        if (capability == GregtechTileCapabilities.CAPABILITY_CONTROLLABLE) {
            return GregtechTileCapabilities.CAPABILITY_CONTROLLABLE.cast(this);
        }
        return defaultValue;
    }

    @Override
    public boolean isWorkingEnabled() {
        return isWorkingAllowed;
    }

    @Override
    public void setWorkingEnabled(boolean isActivationAllowed) {
        this.isWorkingAllowed = isActivationAllowed;
    }

    @Override
    public void writeToNBT(@NotNull NBTTagCompound tagCompound) {
        super.writeToNBT(tagCompound);
        tagCompound.setInteger("TransferRate", transferRate);
        tagCompound.setInteger("PumpMode", pumpMode.ordinal());
        tagCompound.setInteger("DistributionMode", distributionMode.ordinal());
        tagCompound.setBoolean("WorkingAllowed", isWorkingAllowed);
        tagCompound.setInteger("ManualImportExportMode", manualImportExportMode.ordinal());
        tagCompound.setTag("Filter", fluidFilter.serializeNBT());
    }

    @Override
    public void readFromNBT(@NotNull NBTTagCompound tagCompound) {
        super.readFromNBT(tagCompound);
        this.transferRate = tagCompound.getInteger("TransferRate");
        this.pumpMode = PumpMode.values()[tagCompound.getInteger("PumpMode")];
        this.distributionMode = DistributionMode.values()[tagCompound.getInteger("DistributionMode")];
        this.isWorkingAllowed = tagCompound.getBoolean("WorkingAllowed");
        this.manualImportExportMode = ManualImportExportMode.values()[tagCompound.getInteger("ManualImportExportMode")];
        this.fluidFilter.deserializeNBT(tagCompound.getCompoundTag("Filter"));
    }

    @Override
    @SideOnly(Side.CLIENT)
    protected @NotNull TextureAtlasSprite getPlateSprite() {
        return Textures.VOLTAGE_CASINGS[this.tier].getSpriteOnSide(SimpleSidedCubeRenderer.RenderSide.SIDE);
    }

    public enum PumpMode implements IStringSerializable, IIOMode {
        IMPORT("cover.pump.mode.import"),
        EXPORT("cover.pump.mode.export");

        public final String localeName;

        PumpMode(String localeName) {
            this.localeName = localeName;
        }

        @Nonnull
        @Override
        public String getName() {
            return localeName;
        }

        @Override
        public boolean isImport() {
            return this == IMPORT;
        }
    }

    public enum BucketMode implements IStringSerializable {
        BUCKET("cover.bucket.mode.bucket"),
        MILLI_BUCKET("cover.bucket.mode.milli_bucket");

        public final String localeName;

        BucketMode(String localeName) {
            this.localeName = localeName;
        }

        @Nonnull
        @Override
        public String getName() {
            return localeName;
        }
    }

    private class CoverableFluidHandlerWrapper extends FluidHandlerDelegate {

        public CoverableFluidHandlerWrapper(IFluidHandler delegate) {
            super(delegate);
        }

        @Override
        public int fill(FluidStack resource, boolean doFill) {
            if (pumpMode == PumpMode.EXPORT && manualImportExportMode == ManualImportExportMode.DISABLED) {
                return 0;
            }
            if (!checkInputFluid(resource) && manualImportExportMode == ManualImportExportMode.FILTERED) {
                return 0;
            }
            return super.fill(resource, doFill);
        }

        @Nullable
        @Override
        public FluidStack drain(FluidStack resource, boolean doDrain) {
            if (pumpMode == PumpMode.IMPORT && manualImportExportMode == ManualImportExportMode.DISABLED) {
                return null;
            }
            if (manualImportExportMode == ManualImportExportMode.FILTERED && !checkInputFluid(resource)) {
                return null;
            }
            return super.drain(resource, doDrain);
        }

        @Nullable
        @Override
        public FluidStack drain(int maxDrain, boolean doDrain) {
            if (pumpMode == PumpMode.IMPORT && manualImportExportMode == ManualImportExportMode.DISABLED) {
                return null;
            }
            if (manualImportExportMode == ManualImportExportMode.FILTERED) {
                FluidStack result = super.drain(maxDrain, false);
                if (result == null || result.amount <= 0 || !checkInputFluid(result)) {
                    return null;
                }
                return doDrain ? super.drain(maxDrain, true) : result;
            }
            return super.drain(maxDrain, doDrain);
        }
    }
}
