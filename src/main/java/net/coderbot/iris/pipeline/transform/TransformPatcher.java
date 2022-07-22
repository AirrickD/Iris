package net.coderbot.iris.pipeline.transform;

import java.util.Optional;
import java.util.function.Supplier;

import org.antlr.v4.runtime.Token;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.github.douira.glsl_transformer.ast.node.Identifier;
import io.github.douira.glsl_transformer.ast.print.PrintType;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.ASTTransformer;
import io.github.douira.glsl_transformer.cst.core.SemanticException;
import io.github.douira.glsl_transformer.cst.token_filter.ChannelFilter;
import io.github.douira.glsl_transformer.cst.token_filter.TokenChannel;
import io.github.douira.glsl_transformer.cst.token_filter.TokenFilter;
import net.coderbot.iris.IrisLogging;
import net.coderbot.iris.gbuffer_overrides.matching.InputAvailability;
import net.coderbot.iris.gl.shader.ShaderType;

/**
 * The transform patcher (triforce 2) uses glsl-transformer to do shader
 * transformation.
 *
 * NOTE: This patcher expects (and ensures) that the string doesn't contain any
 * (!) preprocessor directives. The only allowed ones are #extension and #pragma
 * as they are considered "parsed" directives. If any other directive appears in
 * the string, it will throw.
 */
public class TransformPatcher {
	static Logger LOGGER = LogManager.getLogger(TransformPatcher.class);
	private static ASTTransformer<Parameters> transformer;

	/**
	 * PREV TODO: Only do the NewLines patches if the source code isn't from
	 * gbuffers_lines
	 */

	static TokenFilter<Parameters> parseTokenFilter = new ChannelFilter<Parameters>(TokenChannel.PREPROCESSOR) {
		@Override
		public boolean isTokenAllowed(Token token) {
			if (!super.isTokenAllowed(token)) {
				throw new SemanticException("Unparsed preprocessor directives such as '" + token.getText()
						+ "' may not be present at this stage of shader processing!");
			}
			return true;
		}
	};

	static {
		transformer = new ASTTransformer<>();
		transformer.setTransformation((tree, root, parameters) -> {
			// check for illegal references to internal Iris shader interfaces
			Optional<Identifier> violation = root.identifierIndex.prefixQueryFlat("iris_").findAny();
			if (!violation.isPresent()) {
				violation = root.identifierIndex.prefixQueryFlat("irisMain").findAny();
			}
			violation.ifPresent(id -> {
				throw new SemanticException(
						"Detected a potential reference to unstable and internal Iris shader interfaces (iris_ and irisMain). This isn't currently supported. Violation: "
								+ id.getName());
			});

			Root.indexBuildSession(tree, () -> {
				switch (parameters.patch) {
					case ATTRIBUTES:
						AttributeTransformer.transform(transformer, tree, root, (AttributeParameters) parameters);
						break;
					case SODIUM_TERRAIN:
						SodiumTerrainTransformer.transform(transformer, tree, root, parameters);
						break;
				}
			});
		});
		transformer.getInternalParser().setParseTokenFilter(parseTokenFilter);
	}

	private static String inspectPatch(String source, String patchInfo, Supplier<String> patcher, boolean doLogging) {
		if (source == null) {
			return null;
		}

		if (IrisLogging.ENABLE_SPAM && doLogging) {
			LOGGER.debug("INPUT: " + source + " END INPUT");
		}

		long time = System.currentTimeMillis();
		String patched = patcher.get();

		if (IrisLogging.ENABLE_SPAM && doLogging) {
			LOGGER.debug("INFO: " + patchInfo);
			LOGGER.debug("TIME: patching took " + (System.currentTimeMillis() - time) + "ms");
			LOGGER.debug("PATCHED: " + patched + " END PATCHED");
		}
		return patched;
	}

	private static String inspectPatch(String source, String patchInfo, Supplier<String> patcher) {
		return inspectPatch(source, patchInfo, patcher, true);
	}

	private static String transform(String source, Parameters parameters) {
		return transformer.transform(PrintType.COMPACT, source, parameters);
	}

	public static String patchAttributes(String source, ShaderType type, boolean hasGeometry, InputAvailability inputs) {
		return inspectPatch(source,
				"TYPE: " + type + " HAS_GEOMETRY: " + hasGeometry,
				() -> {
					String str = source;
					str = transform(str, new AttributeParameters(Patch.ATTRIBUTES, type,
							hasGeometry, inputs));
					// str = AttributeShaderTransformer.patch(str, type, hasGeometry, inputs);
					return str;
				});
	}

	public static String patchSodiumTerrain(String source, ShaderType type) {
		return inspectPatch(source,
				"TYPE: " + type,
				() -> {
					String str = source;
					str = transform(str, new Parameters(Patch.SODIUM_TERRAIN, type));
					// str = type == ShaderType.VERTEX
					// 		? SodiumTerrainPipeline.transformVertexShader(str)
					// 		: SodiumTerrainPipeline.transformFragmentShader(str);
					return str;
				},
				false);
	}
}
