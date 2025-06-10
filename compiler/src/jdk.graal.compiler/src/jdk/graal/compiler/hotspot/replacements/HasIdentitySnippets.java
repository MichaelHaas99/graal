package jdk.graal.compiler.hotspot.replacements;

import static jdk.graal.compiler.hotspot.GraalHotSpotVMConfig.INJECTED_VMCONFIG;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.KLASS_ACCESS_FLAGS_LOCATION;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.inlineTypeMaskInPlace;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.inlineTypePattern;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.jvmAccIsIdentityClass;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.klassAccessFlagsOffset;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.loadHub;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.loadWordFromObject;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.markOffset;

import jdk.graal.compiler.api.replacements.Snippet;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.hotspot.word.KlassPointer;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.extended.HasIdentityNode;
import jdk.graal.compiler.nodes.spi.LoweringTool;
import jdk.graal.compiler.nodes.type.StampTool;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.util.Providers;
import jdk.graal.compiler.replacements.InstanceOfSnippetsTemplates;
import jdk.graal.compiler.replacements.SnippetTemplate;
import jdk.graal.compiler.replacements.Snippets;
import jdk.graal.compiler.word.Word;

public class HasIdentitySnippets implements Snippets {
    public static class Templates extends InstanceOfSnippetsTemplates {
        private final SnippetTemplate.SnippetInfo hasIdentitySnippet;

        @SuppressWarnings("this-escape")
        public Templates(OptionValues options, Providers providers) {
            super(options, providers);
            boolean useMarkWord = false;
            if (useMarkWord) {
                hasIdentitySnippet = snippet(providers, HasIdentitySnippets.class, "hasIdentityFromMarkWord");
            } else {
                hasIdentitySnippet = snippet(providers, HasIdentitySnippets.class, "hasIdentityFromKlass");
            }

        }

        @Override
        protected SnippetTemplate.Arguments makeArguments(InstanceOfUsageReplacer replacer, LoweringTool tool) {
            ValueNode node = replacer.instanceOf;
            StructuredGraph graph = node.graph();
            SnippetTemplate.Arguments args = new SnippetTemplate.Arguments(hasIdentitySnippet, graph.getGuardsStage(), tool.getLoweringStage());
            if (node instanceof HasIdentityNode hasIdentityNode) {
                assert StampTool.isPointerNonNull(hasIdentityNode.getValue());
                args.add("object", hasIdentityNode.getValue());
            } else {
                throw GraalError.shouldNotReachHere(node + " " + replacer);
            }

            args.add("trueValue", replacer.trueValue);
            args.add("falseValue", replacer.falseValue);
            return args;
        }

    }

    @Snippet
    public static Object hasIdentityFromMarkWord(Object object, Object trueValue, Object falseValue) {
        HotSpotReplacementsUtil.verifyOop(object);

        // check mark word for inline type
        final Word mark = loadWordFromObject(object, markOffset(INJECTED_VMCONFIG));
        return !mark.and(inlineTypeMaskInPlace(INJECTED_VMCONFIG)).equal(inlineTypePattern(INJECTED_VMCONFIG)) ? trueValue : falseValue;

    }

    @Snippet
    public static Object hasIdentityFromKlass(Object object, Object trueValue, Object falseValue) {
        HotSpotReplacementsUtil.verifyOop(object);

        KlassPointer hub = loadHub(object);
        return (hub.readInt(klassAccessFlagsOffset(INJECTED_VMCONFIG), KLASS_ACCESS_FLAGS_LOCATION) &
                        jvmAccIsIdentityClass(INJECTED_VMCONFIG)) != 0 ? trueValue : falseValue;

    }

}
