package net.minecraft.client.renderer;

import org.lwjgl.opengl.GL11;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.EnumAction;
import net.minecraft.item.Item;
import net.minecraft.item.ItemMap;
import net.minecraft.item.ItemStack;
import net.minecraft.src.Config;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumWorldBlockLayer;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.storage.MapData;
import net.optifine.DynamicLights;
import net.optifine.reflect.Reflector;
import net.optifine.shaders.Shaders;
import pisi.unitedmeows.minecraft.Settings;

public class ItemRenderer {
	private static final ResourceLocation RES_MAP_BACKGROUND = new ResourceLocation("textures/map/map_background.png");
	private static final ResourceLocation RES_UNDERWATER_OVERLAY = new ResourceLocation("textures/misc/underwater.png");
	/** A reference to the Minecraft object. */
	private final Minecraft mc;
	private ItemStack itemToRender;
	/**
	 * How far the current item has been equipped (0 disequipped and 1 fully up)
	 */
	private float equippedProgress;
	private float prevEquippedProgress;
	private final RenderManager renderManager;
	private final RenderItem itemRenderer;
	/** The index of the currently held item (0-8, or -1 if not yet updated) */
	private int equippedItemSlot = -1;

	public ItemRenderer(final Minecraft mcIn)
	{
		this.mc = mcIn;
		this.renderManager = mcIn.getRenderManager();
		this.itemRenderer = mcIn.getRenderItem();
	}

	public void renderItem(final EntityLivingBase entityIn, final ItemStack heldStack, final ItemCameraTransforms.TransformType transform) {
		if (heldStack != null) {
			final Item item = heldStack.getItem();
			final Block block = Block.getBlockFromItem(item);
			GlStateManager.pushMatrix();
			if (this.itemRenderer.shouldRenderItemIn3D(heldStack)) {
				GlStateManager.scale(2.0F, 2.0F, 2.0F);
				if (this.isBlockTranslucent(block) && (!Config.isShaders() || !Shaders.renderItemKeepDepthMask)) GlStateManager.depthMask(false);
			}
			this.itemRenderer.renderItemModelForEntity(heldStack, entityIn, transform);
			if (this.isBlockTranslucent(block)) GlStateManager.depthMask(true);
			GlStateManager.popMatrix();
		}
	}

	/**
	 * Returns true if given block is translucent
	 */
	private boolean isBlockTranslucent(final Block blockIn) { return blockIn != null && blockIn.getBlockLayer() == EnumWorldBlockLayer.TRANSLUCENT; }

	/**
	 * Rotate the render around X and Y
	 *
	 * @param angleY The angle for the rotation arround Y
	 */
	private void rotateArroundXAndY(final float angle, final float angleY) {
		GlStateManager.pushMatrix();
		GlStateManager.rotate(angle, 1.0F, 0.0F, 0.0F);
		GlStateManager.rotate(angleY, 0.0F, 1.0F, 0.0F);
		RenderHelper.enableStandardItemLighting();
		GlStateManager.popMatrix();
	}

	/**
	 * Set the OpenGL LightMapTextureCoords based on the AbstractClientPlayer
	 */
	private void setLightMapFromPlayer(final AbstractClientPlayer clientPlayer) {
		int i = this.mc.theWorld.getCombinedLight(new BlockPos(clientPlayer.posX, clientPlayer.posY + clientPlayer.getEyeHeight(), clientPlayer.posZ), 0);
		if (Config.isDynamicLights()) i = DynamicLights.getCombinedLight(this.mc.getRenderViewEntity(), i);
		final float f = i & 65535;
		final float f1 = i >> 16;
		OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, f, f1);
	}

	/**
	 * Rotate the render according to the player's yaw and pitch
	 */
	private void rotateWithPlayerRotations(final EntityPlayerSP entityplayerspIn, final float partialTicks) {
		final float f = entityplayerspIn.prevRenderArmPitch + (entityplayerspIn.renderArmPitch - entityplayerspIn.prevRenderArmPitch) * partialTicks;
		final float f1 = entityplayerspIn.prevRenderArmYaw + (entityplayerspIn.renderArmYaw - entityplayerspIn.prevRenderArmYaw) * partialTicks;
		GlStateManager.rotate((entityplayerspIn.rotationPitch - f) * 0.1F, 1.0F, 0.0F, 0.0F);
		GlStateManager.rotate((entityplayerspIn.rotationYaw - f1) * 0.1F, 0.0F, 1.0F, 0.0F);
	}

	/**
	 * Return the angle to render the Map
	 *
	 * @param pitch The player's pitch
	 */
	private float getMapAngleFromPitch(final float pitch) {
		float f = 1.0F - pitch / 45.0F + 0.1F;
		f = MathHelper.clamp_float(f, 0.0F, 1.0F);
		f = -MathHelper.cos(f * (float) Math.PI) * 0.5F + 0.5F;
		return f;
	}

	private void renderRightArm(final RenderPlayer renderPlayerIn) {
		GlStateManager.pushMatrix();
		GlStateManager.rotate(54.0F, 0.0F, 1.0F, 0.0F);
		GlStateManager.rotate(64.0F, 1.0F, 0.0F, 0.0F);
		GlStateManager.rotate(-62.0F, 0.0F, 0.0F, 1.0F);
		GlStateManager.translate(0.25F, -0.85F, 0.75F);
		renderPlayerIn.renderRightArm(this.mc.thePlayer);
		GlStateManager.popMatrix();
	}

	private void renderLeftArm(final RenderPlayer renderPlayerIn) {
		GlStateManager.pushMatrix();
		GlStateManager.rotate(92.0F, 0.0F, 1.0F, 0.0F);
		GlStateManager.rotate(45.0F, 1.0F, 0.0F, 0.0F);
		GlStateManager.rotate(41.0F, 0.0F, 0.0F, 1.0F);
		GlStateManager.translate(-0.3F, -1.1F, 0.45F);
		renderPlayerIn.renderLeftArm(this.mc.thePlayer);
		GlStateManager.popMatrix();
	}

	private void renderPlayerArms(final AbstractClientPlayer clientPlayer) {
		this.mc.getTextureManager().bindTexture(clientPlayer.getLocationSkin());
		final Render<AbstractClientPlayer> render = this.renderManager.<AbstractClientPlayer>getEntityRenderObject(this.mc.thePlayer);
		final RenderPlayer renderplayer = (RenderPlayer) render;
		if (!clientPlayer.isInvisible()) {
			GlStateManager.disableCull();
			this.renderRightArm(renderplayer);
			this.renderLeftArm(renderplayer);
			GlStateManager.enableCull();
		}
	}

	private void renderItemMap(final AbstractClientPlayer clientPlayer, final float pitch, final float equipmentProgress, final float swingProgress) {
		final float f = -0.4F * MathHelper.sin(MathHelper.sqrt_float(swingProgress) * (float) Math.PI);
		final float f1 = 0.2F * MathHelper.sin(MathHelper.sqrt_float(swingProgress) * (float) Math.PI * 2.0F);
		final float f2 = -0.2F * MathHelper.sin(swingProgress * (float) Math.PI);
		GlStateManager.translate(f, f1, f2);
		final float f3 = this.getMapAngleFromPitch(pitch);
		GlStateManager.translate(0.0F, 0.04F, -0.72F);
		GlStateManager.translate(0.0F, equipmentProgress * -1.2F, 0.0F);
		GlStateManager.translate(0.0F, f3 * -0.5F, 0.0F);
		GlStateManager.rotate(90.0F, 0.0F, 1.0F, 0.0F);
		GlStateManager.rotate(f3 * -85.0F, 0.0F, 0.0F, 1.0F);
		GlStateManager.rotate(0.0F, 1.0F, 0.0F, 0.0F);
		this.renderPlayerArms(clientPlayer);
		final float f4 = MathHelper.sin(swingProgress * swingProgress * (float) Math.PI);
		final float f5 = MathHelper.sin(MathHelper.sqrt_float(swingProgress) * (float) Math.PI);
		GlStateManager.rotate(f4 * -20.0F, 0.0F, 1.0F, 0.0F);
		GlStateManager.rotate(f5 * -20.0F, 0.0F, 0.0F, 1.0F);
		GlStateManager.rotate(f5 * -80.0F, 1.0F, 0.0F, 0.0F);
		GlStateManager.scale(0.38F, 0.38F, 0.38F);
		GlStateManager.rotate(90.0F, 0.0F, 1.0F, 0.0F);
		GlStateManager.rotate(180.0F, 0.0F, 0.0F, 1.0F);
		GlStateManager.rotate(0.0F, 1.0F, 0.0F, 0.0F);
		GlStateManager.translate(-1.0F, -1.0F, 0.0F);
		GlStateManager.scale(0.015625F, 0.015625F, 0.015625F);
		this.mc.getTextureManager().bindTexture(RES_MAP_BACKGROUND);
		final Tessellator tessellator = Tessellator.getInstance();
		final WorldRenderer worldrenderer = tessellator.getWorldRenderer();
		GL11.glNormal3f(0.0F, 0.0F, -1.0F);
		worldrenderer.begin(7, DefaultVertexFormats.POSITION_TEX);
		worldrenderer.pos(-7.0D, 135.0D, 0.0D).tex(0.0D, 1.0D).endVertex();
		worldrenderer.pos(135.0D, 135.0D, 0.0D).tex(1.0D, 1.0D).endVertex();
		worldrenderer.pos(135.0D, -7.0D, 0.0D).tex(1.0D, 0.0D).endVertex();
		worldrenderer.pos(-7.0D, -7.0D, 0.0D).tex(0.0D, 0.0D).endVertex();
		tessellator.draw();
		final MapData mapdata = Items.filled_map.getMapData(this.itemToRender, this.mc.theWorld);
		if (mapdata != null) this.mc.entityRenderer.getMapItemRenderer().renderMap(mapdata, false);
	}

	/**
	 * Render the player's arm
	 *
	 * @param equipProgress The progress of equiping the item
	 * @param swingProgress The swing movement progression
	 */
	private void renderPlayerArm(final AbstractClientPlayer clientPlayer, final float equipProgress, final float swingProgress) {
		final float f = -0.3F * MathHelper.sin(MathHelper.sqrt_float(swingProgress) * (float) Math.PI);
		final float f1 = 0.4F * MathHelper.sin(MathHelper.sqrt_float(swingProgress) * (float) Math.PI * 2.0F);
		final float f2 = -0.4F * MathHelper.sin(swingProgress * (float) Math.PI);
		GlStateManager.translate(f, f1, f2);
		GlStateManager.translate(0.64000005F, -0.6F, -0.71999997F);
		GlStateManager.translate(0.0F, equipProgress * -0.6F, 0.0F);
		GlStateManager.rotate(45.0F, 0.0F, 1.0F, 0.0F);
		final float f3 = MathHelper.sin(swingProgress * swingProgress * (float) Math.PI);
		final float f4 = MathHelper.sin(MathHelper.sqrt_float(swingProgress) * (float) Math.PI);
		GlStateManager.rotate(f4 * 70.0F, 0.0F, 1.0F, 0.0F);
		GlStateManager.rotate(f3 * -20.0F, 0.0F, 0.0F, 1.0F);
		this.mc.getTextureManager().bindTexture(clientPlayer.getLocationSkin());
		GlStateManager.translate(-1.0F, 3.6F, 3.5F);
		GlStateManager.rotate(120.0F, 0.0F, 0.0F, 1.0F);
		GlStateManager.rotate(200.0F, 1.0F, 0.0F, 0.0F);
		GlStateManager.rotate(-135.0F, 0.0F, 1.0F, 0.0F);
		GlStateManager.scale(1.0F, 1.0F, 1.0F);
		GlStateManager.translate(5.6F, 0.0F, 0.0F);
		final Render<AbstractClientPlayer> render = this.renderManager.<AbstractClientPlayer>getEntityRenderObject(this.mc.thePlayer);
		GlStateManager.disableCull();
		final RenderPlayer renderplayer = (RenderPlayer) render;
		renderplayer.renderRightArm(this.mc.thePlayer);
		GlStateManager.enableCull();
	}

	/**
	 * Rotate and translate render to show item consumption
	 *
	 * @param swingProgress The swing movement progress
	 */
	private void doItemUsedTransformations(final float swingProgress) {
		final float f = -0.4F * MathHelper.sin(MathHelper.sqrt_float(swingProgress) * (float) Math.PI);
		final float f1 = 0.2F * MathHelper.sin(MathHelper.sqrt_float(swingProgress) * (float) Math.PI * 2.0F);
		final float f2 = -0.2F * MathHelper.sin(swingProgress * (float) Math.PI);
		GlStateManager.translate(f, f1, f2);
	}

	/**
	 * Perform the drinking animation movement
	 *
	 * @param partialTicks Partials ticks
	 */
	private void performDrinking(final AbstractClientPlayer clientPlayer, final float partialTicks) {
		final float f = clientPlayer.getItemInUseCount() - partialTicks + 1.0F;
		final float f1 = f / this.itemToRender.getMaxItemUseDuration();
		float f2 = MathHelper.abs(MathHelper.cos(f / 4.0F * (float) Math.PI) * 0.1F);
		if (f1 >= 0.8F) f2 = 0.0F;
		GlStateManager.translate(0.0F, f2, 0.0F);
		final float f3 = 1.0F - (float) Math.pow(f1, 27.0D);
		GlStateManager.translate(f3 * 0.6F, f3 * -0.5F, f3 * 0.0F);
		GlStateManager.rotate(f3 * 90.0F, 0.0F, 1.0F, 0.0F);
		GlStateManager.rotate(f3 * 10.0F, 1.0F, 0.0F, 0.0F);
		GlStateManager.rotate(f3 * 30.0F, 0.0F, 0.0F, 1.0F);
	}

	/**
	 * Performs transformations prior to the rendering of a held item in first person.
	 */
	private void transformFirstPersonItem(final float equipProgress, final float swingProgress) {
		GlStateManager.translate(0.56F, -0.52F, -0.71999997F);
		GlStateManager.translate(0.0F, equipProgress * -0.6F, 0.0F);
		GlStateManager.rotate(45.0F, 0.0F, 1.0F, 0.0F);
		final float f = MathHelper.sin(swingProgress * swingProgress * (float) Math.PI);
		final float f1 = MathHelper.sin(MathHelper.sqrt_float(swingProgress) * (float) Math.PI);
		GlStateManager.rotate(f * -20.0F, 0.0F, 1.0F, 0.0F);
		GlStateManager.rotate(f1 * -20.0F, 0.0F, 0.0F, 1.0F);
		GlStateManager.rotate(f1 * -80.0F, 1.0F, 0.0F, 0.0F);
		GlStateManager.scale(0.4F, 0.4F, 0.4F);
	}

	/**
	 * Translate and rotate the render to look like holding a bow
	 *
	 * @param partialTicks Partial ticks
	 */
	private void doBowTransformations(final float partialTicks, final AbstractClientPlayer clientPlayer) {
		GlStateManager.rotate(-18.0F, 0.0F, 0.0F, 1.0F);
		GlStateManager.rotate(-12.0F, 0.0F, 1.0F, 0.0F);
		GlStateManager.rotate(-8.0F, 1.0F, 0.0F, 0.0F);
		GlStateManager.translate(-0.9F, 0.2F, 0.0F);
		final float f = this.itemToRender.getMaxItemUseDuration() - (clientPlayer.getItemInUseCount() - partialTicks + 1.0F);
		float f1 = f / 20.0F;
		f1 = (f1 * f1 + f1 * 2.0F) / 3.0F;
		if (f1 > 1.0F) f1 = 1.0F;
		if (f1 > 0.1F) {
			final float f2 = MathHelper.sin((f - 0.1F) * 1.3F);
			final float f3 = f1 - 0.1F;
			final float f4 = f2 * f3;
			GlStateManager.translate(f4 * 0.0F, f4 * 0.01F, f4 * 0.0F);
		}
		GlStateManager.translate(f1 * 0.0F, f1 * 0.0F, f1 * 0.1F);
		GlStateManager.scale(1.0F, 1.0F, 1.0F + f1 * 0.2F);
	}

	/**
	 * Translate and rotate the render for holding a block
	 */
	private void doBlockTransformations() {
		GlStateManager.translate(-0.5F, 0.2F, 0.0F);
		GlStateManager.rotate(30.0F, 0.0F, 1.0F, 0.0F);
		GlStateManager.rotate(-80.0F, 1.0F, 0.0F, 0.0F);
		GlStateManager.rotate(60.0F, 0.0F, 1.0F, 0.0F);
	}

	/**
	 * Renders the active item in the player's hand when in first person mode. Args: partialTickTime
	 */
	public void renderItemInFirstPerson(final float partialTicks) {
		if (!Config.isShaders() || !Shaders.isSkipRenderHand()) {
			final float equipProgress = 1.0F - (this.prevEquippedProgress + (this.equippedProgress - this.prevEquippedProgress) * partialTicks);
			final AbstractClientPlayer abstractclientplayer = this.mc.thePlayer;
			final float swingProgress = abstractclientplayer.getSwingProgress(partialTicks);
			final float pitch = abstractclientplayer.prevRotationPitch + (abstractclientplayer.rotationPitch - abstractclientplayer.prevRotationPitch) * partialTicks;
			final float yaw = abstractclientplayer.prevRotationYaw + (abstractclientplayer.rotationYaw - abstractclientplayer.prevRotationYaw) * partialTicks;
			this.rotateArroundXAndY(pitch, yaw);
			this.setLightMapFromPlayer(abstractclientplayer);
			this.rotateWithPlayerRotations((EntityPlayerSP) abstractclientplayer, partialTicks);
			GlStateManager.enableRescaleNormal();
			GlStateManager.pushMatrix();
			if (this.itemToRender != null) {
				if (this.itemToRender.getItem() instanceof ItemMap) this.renderItemMap(abstractclientplayer, pitch, equipProgress, swingProgress);
				else if (abstractclientplayer.getItemInUseCount() > 0) {
					final boolean oldAnimations = Settings.OLD_ANIMATIONS;
					final EnumAction enumaction = this.itemToRender.getItemUseAction();
					switch (enumaction) {
					case NONE:
						this.transformFirstPersonItem(equipProgress, 0.0F);
						break;
					case EAT:
					case DRINK:
						this.performDrinking(abstractclientplayer, partialTicks);
						this.transformFirstPersonItem(equipProgress, 0.0F);
						break;
					case BLOCK:
						final float oldAnimationProgress = MathHelper.sin(MathHelper.sqrt_float(swingProgress) * (float) Math.PI);

						if (oldAnimations) {
							this.transformFirstPersonItem(equipProgress, 0F);
							this.doBlockTransformations();
							GlStateManager.translate(-0.05f, -0.0f, 0.3f);
							GlStateManager.rotate(-oldAnimationProgress * (float) 20.0 / 2.0f, -15.0f, -0.0f, 20.0f);
							GlStateManager.rotate(-oldAnimationProgress * (float) 40.0, 1.0f, -0.4f, 2f);
						} else {
							/**
							 * actual code for 1.7.10 is swingProgress instead of 0F
							 */
							this.transformFirstPersonItem(equipProgress, 0F);
							this.doBlockTransformations();
						}
						GL11.glRotatef(60.0F, 0.0F, 0.0F, 1.0F);
						GL11.glRotatef(10.0F, 1.0F, 0.0F, 0.0F);
						GL11.glRotatef(50.0F, 0.0F, 1.0F, 0.0F);
						if (this.mc.thePlayer.isSneaking()) {
							GL11.glTranslatef(0.1F, -0.05F, -0.05F);
							break;
						}
						GL11.glTranslatef(0.1F, -0.05F, 0.1F);
						break;
					case BOW:
						this.transformFirstPersonItem(equipProgress, 0.0F);
						this.doBowTransformations(partialTicks, abstractclientplayer);
					}
				} else {
					this.doItemUsedTransformations(swingProgress);
					this.transformFirstPersonItem(equipProgress, swingProgress);
				}
				this.renderItem(abstractclientplayer, this.itemToRender, ItemCameraTransforms.TransformType.FIRST_PERSON);
			} else if (!abstractclientplayer.isInvisible()) this.renderPlayerArm(abstractclientplayer, equipProgress, swingProgress);
			GlStateManager.popMatrix();
			GlStateManager.disableRescaleNormal();
			RenderHelper.disableStandardItemLighting();
		}
	}

	/**
	 * Renders all the overlays that are in first person mode. Args: partialTickTime
	 */
	public void renderOverlays(final float partialTicks) {
		GlStateManager.disableAlpha();
		if (this.mc.thePlayer.isEntityInsideOpaqueBlock()) {
			IBlockState iblockstate = this.mc.theWorld.getBlockState(new BlockPos(this.mc.thePlayer));
			BlockPos blockpos = new BlockPos(this.mc.thePlayer);
			final EntityPlayer entityplayer = this.mc.thePlayer;
			for (int i = 0; i < 8; ++i) {
				final double d0 = entityplayer.posX + ((i >> 0) % 2 - 0.5F) * entityplayer.width * 0.8F;
				final double d1 = entityplayer.posY + ((i >> 1) % 2 - 0.5F) * 0.1F;
				final double d2 = entityplayer.posZ + ((i >> 2) % 2 - 0.5F) * entityplayer.width * 0.8F;
				final BlockPos blockpos1 = new BlockPos(d0, d1 + entityplayer.getEyeHeight(), d2);
				final IBlockState iblockstate1 = this.mc.theWorld.getBlockState(blockpos1);
				if (iblockstate1.getBlock().isVisuallyOpaque()) {
					iblockstate = iblockstate1;
					blockpos = blockpos1;
				}
			}
			if (iblockstate.getBlock().getRenderType() != -1) {
				final Object object = Reflector.getFieldValue(Reflector.RenderBlockOverlayEvent_OverlayType_BLOCK);
				if (!Reflector.callBoolean(Reflector.ForgeEventFactory_renderBlockOverlay, this.mc.thePlayer, Float.valueOf(partialTicks), object, iblockstate, blockpos))
					this.renderBlockInHand(partialTicks, this.mc.getBlockRendererDispatcher().getBlockModelShapes().getTexture(iblockstate));
			}
		}
		if (!this.mc.thePlayer.isSpectator()) {
			if (this.mc.thePlayer.isInsideOfMaterial(Material.water) && !Reflector.callBoolean(Reflector.ForgeEventFactory_renderWaterOverlay, this.mc.thePlayer, Float.valueOf(partialTicks))) this.renderWaterOverlayTexture(partialTicks);
			if (this.mc.thePlayer.isBurning() && !Reflector.callBoolean(Reflector.ForgeEventFactory_renderFireOverlay, this.mc.thePlayer, Float.valueOf(partialTicks))) this.renderFireInFirstPerson(partialTicks);
		}
		GlStateManager.enableAlpha();
	}

	/**
	 * Render the block in the player's hand
	 *
	 * @param partialTicks Partial ticks
	 * @param atlas        The TextureAtlasSprite to render
	 */
	private void renderBlockInHand(final float partialTicks, final TextureAtlasSprite atlas) {
		this.mc.getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
		final Tessellator tessellator = Tessellator.getInstance();
		final WorldRenderer worldrenderer = tessellator.getWorldRenderer();
		final float f = 0.1F;
		GlStateManager.color(0.1F, 0.1F, 0.1F, 0.5F);
		GlStateManager.pushMatrix();
		final float f1 = -1.0F;
		final float f2 = 1.0F;
		final float f3 = -1.0F;
		final float f4 = 1.0F;
		final float f5 = -0.5F;
		final float f6 = atlas.getMinU();
		final float f7 = atlas.getMaxU();
		final float f8 = atlas.getMinV();
		final float f9 = atlas.getMaxV();
		worldrenderer.begin(7, DefaultVertexFormats.POSITION_TEX);
		worldrenderer.pos(-1.0D, -1.0D, -0.5D).tex(f7, f9).endVertex();
		worldrenderer.pos(1.0D, -1.0D, -0.5D).tex(f6, f9).endVertex();
		worldrenderer.pos(1.0D, 1.0D, -0.5D).tex(f6, f8).endVertex();
		worldrenderer.pos(-1.0D, 1.0D, -0.5D).tex(f7, f8).endVertex();
		tessellator.draw();
		GlStateManager.popMatrix();
		GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
	}

	/**
	 * Renders a texture that warps around based on the direction the player is looking. Texture needs
	 * to be bound before being called. Used for the water overlay. Args: parialTickTime
	 *
	 * @param partialTicks Partial ticks
	 */
	private void renderWaterOverlayTexture(final float partialTicks) {
		if (!Config.isShaders() || Shaders.isUnderwaterOverlay()) {
			this.mc.getTextureManager().bindTexture(RES_UNDERWATER_OVERLAY);
			final Tessellator tessellator = Tessellator.getInstance();
			final WorldRenderer worldrenderer = tessellator.getWorldRenderer();
			final float f = this.mc.thePlayer.getBrightness(partialTicks);
			GlStateManager.color(f, f, f, 0.5F);
			GlStateManager.enableBlend();
			GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
			GlStateManager.pushMatrix();
			final float f1 = 4.0F;
			final float f2 = -1.0F;
			final float f3 = 1.0F;
			final float f4 = -1.0F;
			final float f5 = 1.0F;
			final float f6 = -0.5F;
			final float f7 = -this.mc.thePlayer.rotationYaw / 64.0F;
			final float f8 = this.mc.thePlayer.rotationPitch / 64.0F;
			worldrenderer.begin(7, DefaultVertexFormats.POSITION_TEX);
			worldrenderer.pos(-1.0D, -1.0D, -0.5D).tex(4.0F + f7, 4.0F + f8).endVertex();
			worldrenderer.pos(1.0D, -1.0D, -0.5D).tex(0.0F + f7, 4.0F + f8).endVertex();
			worldrenderer.pos(1.0D, 1.0D, -0.5D).tex(0.0F + f7, 0.0F + f8).endVertex();
			worldrenderer.pos(-1.0D, 1.0D, -0.5D).tex(4.0F + f7, 0.0F + f8).endVertex();
			tessellator.draw();
			GlStateManager.popMatrix();
			GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
			GlStateManager.disableBlend();
		}
	}

	/**
	 * Renders the fire on the screen for first person mode. Arg: partialTickTime
	 *
	 * @param partialTicks Partial ticks
	 */
	private void renderFireInFirstPerson(final float partialTicks) {
		final Tessellator tessellator = Tessellator.getInstance();
		final WorldRenderer worldrenderer = tessellator.getWorldRenderer();
		GlStateManager.color(1.0F, 1.0F, 1.0F, 0.9F);
		GlStateManager.depthFunc(519);
		GlStateManager.depthMask(false);
		GlStateManager.enableBlend();
		GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
		final float f = 1.0F;
		for (int i = 0; i < 2; ++i) {
			GlStateManager.pushMatrix();
			final TextureAtlasSprite textureatlassprite = this.mc.getTextureMapBlocks().getAtlasSprite("minecraft:blocks/fire_layer_1");
			this.mc.getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
			final float f1 = textureatlassprite.getMinU();
			final float f2 = textureatlassprite.getMaxU();
			final float f3 = textureatlassprite.getMinV();
			final float f4 = textureatlassprite.getMaxV();
			final float f5 = (0.0F - f) / 2.0F;
			final float f6 = f5 + f;
			final float f7 = 0.0F - f / 2.0F;
			final float f8 = f7 + f;
			final float f9 = -0.5F;
			GlStateManager.translate((-(i * 2 - 1)) * 0.24F, -0.3F, 0.0F);
			GlStateManager.rotate((i * 2 - 1) * 10.0F, 0.0F, 1.0F, 0.0F);
			worldrenderer.begin(7, DefaultVertexFormats.POSITION_TEX);
			worldrenderer.setSprite(textureatlassprite);
			worldrenderer.pos(f5, f7, f9).tex(f2, f4).endVertex();
			worldrenderer.pos(f6, f7, f9).tex(f1, f4).endVertex();
			worldrenderer.pos(f6, f8, f9).tex(f1, f3).endVertex();
			worldrenderer.pos(f5, f8, f9).tex(f2, f3).endVertex();
			tessellator.draw();
			GlStateManager.popMatrix();
		}
		GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
		GlStateManager.disableBlend();
		GlStateManager.depthMask(true);
		GlStateManager.depthFunc(515);
	}

	public void updateEquippedItem() {
		this.prevEquippedProgress = this.equippedProgress;
		final EntityPlayer entityplayer = this.mc.thePlayer;
		final ItemStack itemstack = entityplayer.inventory.getCurrentItem();
		boolean flag = false;
		if (this.itemToRender != null && itemstack != null) {
			if (!this.itemToRender.getIsItemStackEqual(itemstack)) {
				if (Reflector.ForgeItem_shouldCauseReequipAnimation.exists()) {
					final boolean flag1 = Reflector.callBoolean(this.itemToRender.getItem(), Reflector.ForgeItem_shouldCauseReequipAnimation, this.itemToRender, itemstack, Boolean.valueOf(this.equippedItemSlot != entityplayer.inventory.currentItem));
					if (!flag1) {
						this.itemToRender = itemstack;
						this.equippedItemSlot = entityplayer.inventory.currentItem;
						return;
					}
				}
				flag = true;
			}
		} else if (this.itemToRender == null && itemstack == null) flag = false;
		else flag = true;
		final float f2 = 0.4F;
		final float f = flag ? 0.0F : 1.0F;
		final float f1 = MathHelper.clamp_float(f - this.equippedProgress, -f2, f2);
		this.equippedProgress += f1;
		if (this.equippedProgress < 0.1F) {
			this.itemToRender = itemstack;
			this.equippedItemSlot = entityplayer.inventory.currentItem;
			if (Config.isShaders()) Shaders.setItemToRenderMain(itemstack);
		}
	}

	/**
	 * Resets equippedProgress
	 */
	public void resetEquippedProgress() { this.equippedProgress = 0.0F; }

	/**
	 * Resets equippedProgress
	 */
	public void resetEquippedProgress2() { this.equippedProgress = 0.0F; }
}
