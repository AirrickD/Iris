package net.coderbot.iris.shaderpack.rendergraph.lowering;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.coderbot.iris.shaderpack.PackDirectives;
import net.coderbot.iris.shaderpack.PackRenderTargetDirectives;
import net.coderbot.iris.shaderpack.PackShadowDirectives;
import net.coderbot.iris.shaderpack.ProgramDirectives;
import net.coderbot.iris.shaderpack.ProgramSet;
import net.coderbot.iris.shaderpack.ProgramSource;
import net.coderbot.iris.shaderpack.rendergraph.ColorAttachments;
import net.coderbot.iris.shaderpack.rendergraph.TextureFilteringMode;
import net.coderbot.iris.shaderpack.rendergraph.TextureHandle;
import net.coderbot.iris.shaderpack.rendergraph.TextureSize;
import net.coderbot.iris.shaderpack.rendergraph.pass.GenerateMipmapPassInfo;
import net.coderbot.iris.shaderpack.rendergraph.pass.PassInfo;
import net.coderbot.iris.shaderpack.rendergraph.pass.ScreenRenderPassInfo;
import net.coderbot.iris.shaderpack.rendergraph.pass.ScreenRenderPassInfoBuilder;
import net.coderbot.iris.shaderpack.rendergraph.pass.SetTextureMinFilteringPassInfo;
import net.coderbot.iris.shaderpack.rendergraph.sampler.EdgeBehavior;
import net.coderbot.iris.shaderpack.rendergraph.sampler.SamplerBinding;
import net.coderbot.iris.shaderpack.rendergraph.sampler.SamplerFiltering;
import net.coderbot.iris.vendored.joml.Vector2f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Entrypoint for the ShaderPack lowering system. As in compilers, the lowering system converts high-level ShaderPack
 * concepts to our low-level RenderGraph representation. This lowering system encapsulates and handles a large portion
 * of the complexity and legacy weirdness involved in the existing shader pack format, significantly simplifying the
 * version-dependent portions of the Iris codebase that work with OpenGL & Minecraft.
 */
public class ShaderPackLowering {
	public static List<PassInfo> lowerCompositePasses(ProgramSet programSet, PackDirectives packDirectives, FlipTracker flipTracker) {
		boolean waterShadowEnabled = false;

		// TODO: Initialize these things!!!!!!!!!!
		//   (PROPERLY)
		ColorTargets mainColorTargets = new ColorTargets("main_color_", 16);
		DepthTargets mainDepthTargets = new DepthTargets("main_depth_", 3);
		ColorTargets shadowColorTargets = new ColorTargets("shadow_color_", 2);
		DepthTargets shadowDepthTargets = new DepthTargets("shadow_depth_", 2);
		TextureHandle noisetexHandle = new TextureHandle("noise_tex");

		// TODO: Noisetex specified as custom texture???
		SamplerBinding noisetex = new SamplerBinding(noisetexHandle, EdgeBehavior.REPEAT, SamplerFiltering.LINEAR);

		Map<String, SamplerBinding> customTextures = new HashMap<>();

		// -----

		ImmutableSet<Integer> flippedBeforeComposite = flipTracker.snapshot();

		ColorTargetMipmapping mainColorMipmaps = new ColorTargetMipmapping(mainColorTargets.numColorTargets());
		// The mipmapped shadow depth / color targets, will only be enabled after the shadow pass has executed.
		IntSet shadowDepthMipmaps = new IntOpenHashSet();
		ColorTargetMipmapping shadowColorMipmaps = new ColorTargetMipmapping(shadowColorTargets.numColorTargets());

		{
			List<PackShadowDirectives.DepthSamplingSettings> depthSamplingSettings =
					packDirectives.getShadowDirectives().getDepthSamplingSettings();

			for (int i = 0; i < depthSamplingSettings.size(); i++) {
				PackShadowDirectives.DepthSamplingSettings settings = depthSamplingSettings.get(i);

				if (settings.getMipmap()) {
					shadowDepthMipmaps.add(i);
				}
			}
		}

		List<PackShadowDirectives.SamplingSettings> shadowColorSamplingSettings =
				packDirectives.getShadowDirectives().getColorSamplingSettings();

		{


			for (int i = 0; i < shadowColorSamplingSettings.size(); i++) {
				PackShadowDirectives.SamplingSettings settings = shadowColorSamplingSettings.get(i);

				if (settings.getMipmap()) {
					shadowColorMipmaps.enableMainMipmapping(i);
				}
			}
		}

		packDirectives.getExplicitFlips("composite_pre").forEach((colorTarget, shouldFlip) -> {
			if (shouldFlip) {
				flipTracker.flip(colorTarget);
			}
		});

		ProgramSource[] composite = programSet.getComposite();
		final ImmutableSet.Builder<Integer> flippedAtLeastOnce = new ImmutableSet.Builder<>();
		List<ProtoPass> protoPasses = new ArrayList<>();

		for (ProgramSource source : composite) {
			if (source == null || !source.isValid()) {
				continue;
			}

			ProgramDirectives directives = source.getDirectives();

			int[] drawBuffers = directives.getDrawBuffers();

			ImmutableMap<Integer, Boolean> explicitFlips = source.getDirectives().getExplicitFlips();

			ImmutableSet<Integer> flipped = flipTracker.snapshot();
			ImmutableSet<Integer> flippedAtLeastOnceSnapshot = flippedAtLeastOnce.build();

			ProtoPass protoPass = new ProtoPass(flipped, flippedAtLeastOnceSnapshot, source);
			protoPasses.add(protoPass);

			IntSet flippedAfterThisPass = new IntOpenHashSet();

			for (int buffer : drawBuffers) {
				flippedAfterThisPass.add(buffer);
			}

			explicitFlips.forEach((buffer, isFlipped) -> {
				if (isFlipped) {
					flippedAfterThisPass.add(buffer.intValue());
				} else {
					flippedAfterThisPass.remove(buffer.intValue());
				}
			});

			for (int buffer : flippedAfterThisPass) {
				flipTracker.flip(buffer);
			}
		}

		IntSet needsParitySwap = new IntOpenHashSet();
		PackRenderTargetDirectives renderTargetDirectives = packDirectives.getRenderTargetDirectives();
		IntList buffersToBeCleared = renderTargetDirectives.getBuffersToBeCleared();

		flipTracker.snapshot().forEach((target) -> {
			if (buffersToBeCleared.contains(target.intValue())) {
				return;
			}

			needsParitySwap.add(target.intValue());
		});

		List<PassInfo> builtPasses = new ArrayList<>();

		for (ProtoPass protoPass : protoPasses) {
			FlipState mainFlipState = new FlipState(protoPass.flippedBeforePass, protoPass.flippedAtLeastOnce, needsParitySwap);
			// TODO: This will need changing to support shadowcomp
			FlipState shadowFlipState = FlipState.unflipped();

			ScreenRenderPassInfoBuilder builder = ScreenRenderPassInfo.builder();

			builder.setSource(protoPass.source);

			ProgramDirectives directives = protoPass.source.getDirectives();

			for (int buffer : directives.getMipmappedBuffers()) {
				// Matches TextureInputs#getInputHandles.
				if (mainFlipState.isFlippedBeforePass(buffer)) {
					mainColorMipmaps.enableAltMipmapping(buffer);
				} else {
					mainColorMipmaps.enableMainMipmapping(buffer);
				}

				TextureHandle[] inputs = TextureInputs.getInputHandles(mainColorTargets, mainFlipState, buffer);

				// TODO: Only generate the mipmap if a valid mipmap hasn't been generated or if we've written to the buffer
				// (since the last mipmap was generated)
				//
				// NB: We leave mipmapping enabled even if the buffer is written to again, this appears to match the
				// behavior of ShadersMod/OptiFine, however I'm not sure if it's desired behavior. It's possible that a
				// program could use mipmapped sampling with a stale mipmap, which probably isn't great. However, the
				// sampling mode is always reset between frames, so this only persists after the first program to use
				// mipmapping on this buffer.
				//
				// Also note that this only applies to one of the two buffers in a render target buffer pair - making it
				// unlikely that this issue occurs in practice with most shader packs.
				builtPasses.add(new GenerateMipmapPassInfo(inputs));

				// TODO: This won't be needed with the new sampler object system once that's added.
				builtPasses.add(new SetTextureMinFilteringPassInfo(inputs, TextureFilteringMode.LINEAR_MIPMAP_LINEAR));

				// TODO: Add passes at the end to reset the filtering mode, currently this is handled by the old
				//  system in FinalPassRenderer
			}

			builder.setViewportScale(new Vector2f(directives.getViewportScale(), directives.getViewportScale()));

			int[] drawBuffers = directives.getDrawBuffers();
			builder.setAttachmentsByParity(resolveColorAttachments(mainColorTargets, drawBuffers, mainFlipState));

			TextureInputs textureInputs = new TextureInputs();

			// Main color / depth
			textureInputs.resolveMainColorTargetInputs(mainColorTargets, customTextures, mainFlipState,
					mainColorMipmaps);
			textureInputs.resolveMainDepthTargetInputs(mainDepthTargets, customTextures);

			// Shadow color / depth
			textureInputs.resolveShadowColorTargetInputs(shadowColorTargets, customTextures, shadowFlipState,
					shadowColorMipmaps, shadowColorSamplingSettings);
			textureInputs.resolveShadowDepthTargetInputs(shadowDepthTargets, customTextures, waterShadowEnabled,
					shadowDepthMipmaps, packDirectives.getShadowDirectives().getDepthSamplingSettings());

			// noisetex
			textureInputs.resolveNoiseTex(noisetex, customTextures);

			builder.setDefaultSamplerName(textureInputs.getDefaultSamplerName());
			builder.setSamplers(textureInputs.getSamplers());
			builder.setImages(textureInputs.getImages());

			// TODO: Uniforms???
			builder.setUniforms(new HashSet<>());

			builtPasses.add(builder.build());
		}

		return builtPasses;
	}

	private static ColorAttachments[] resolveColorAttachments(ColorTargets renderTargets, int[] drawBuffers,
															  FlipState flipState) {
		TextureSize pickedSize = null;
		TextureHandle[] textureHandles0 = new TextureHandle[drawBuffers.length];
		TextureHandle[] textureHandles1 = new TextureHandle[drawBuffers.length];

		for (int i = 0; i < drawBuffers.length; i++) {
			int drawBuffer = drawBuffers[i];

			if (drawBuffer >= renderTargets.numColorTargets()) {
				throw new IllegalStateException("Shader pass tried to write to an unavailable color target with index "
						+ drawBuffer + "; only " + renderTargets.numColorTargets() + " color targets are available");
			}

			// Resolve sizes if needed
			TextureSize size = renderTargets.getSize(drawBuffer);

			if (pickedSize == null) {
				pickedSize = size;
			} else if (!pickedSize.equals(size)) {
				throw new IllegalArgumentException("Color target size mismatch when resolving draw buffer array " +
						Arrays.toString(drawBuffers) + ": color target index " + drawBuffer + " uses size " + size
						+ " mismatching with the current picked size " + pickedSize);
			}

			// color attachments start as alt unless flipped.
			boolean alt0 = !flipState.isFlippedBeforePass(drawBuffer);

			// B ^ false = B
			// B ^ true = !B
			boolean alt1 = alt0 ^ flipState.isParitySwapped(drawBuffer);

			textureHandles0[i] = renderTargets.get(drawBuffer, alt0);
			textureHandles1[i] = renderTargets.get(drawBuffer, alt1);
		}

		return new ColorAttachments[] {
				new ColorAttachments(textureHandles0, pickedSize),
				new ColorAttachments(textureHandles1, pickedSize)
		};
	}

	private static class ProtoPass {
		private final ImmutableSet<Integer> flippedBeforePass;
		private final ImmutableSet<Integer> flippedAtLeastOnce;
		private final ProgramSource source;

		public ProtoPass(ImmutableSet<Integer> flippedBeforePass, ImmutableSet<Integer> flippedAtLeastOnce,
						 ProgramSource source) {
			this.flippedBeforePass = flippedBeforePass;
			this.flippedAtLeastOnce = flippedAtLeastOnce;
			this.source = source;
		}
	}
}
