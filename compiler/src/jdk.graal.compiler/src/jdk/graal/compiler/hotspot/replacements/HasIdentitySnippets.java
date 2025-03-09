package jdk.graal.compiler.hotspot.replacements;

import static jdk.graal.compiler.hotspot.GraalHotSpotVMConfig.INJECTED_VMCONFIG;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.KLASS_ACCESS_FLAGS_LOCATION;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.inlineTypePattern;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.jvmAccIsIdentityClass;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.klassAccessFlagsOffset;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.loadHub;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.loadWordFromObject;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.markOffset;
import static jdk.graal.compiler.replacements.SnippetTemplate.DEFAULT_REPLACER;

import jdk.graal.compiler.api.replacements.Snippet;
import jdk.graal.compiler.core.common.type.ObjectStamp;
import jdk.graal.compiler.hotspot.word.KlassPointer;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.extended.HasIdentityNode;
import jdk.graal.compiler.nodes.spi.LoweringTool;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.util.Providers;
import jdk.graal.compiler.replacements.SnippetTemplate;
import jdk.graal.compiler.replacements.Snippets;
import jdk.graal.compiler.word.Word;

public class HasIdentitySnippets implements Snippets {
    public static class Templates extends SnippetTemplate.AbstractTemplates {
        private final SnippetTemplate.SnippetInfo hasIdentitySnippet;

        @SuppressWarnings("this-escape")
        public Templates(OptionValues options, Providers providers) {
            super(options, providers);
            boolean useMarkWord = true;
            if (useMarkWord) {
                hasIdentitySnippet = snippet(providers, HasIdentitySnippets.class, "hasIdentityFromMarkWord");
            } else {
                hasIdentitySnippet = snippet(providers, HasIdentitySnippets.class, "hasIdentityFromKlass");
            }

        }

        public void lower(HasIdentityNode node, LoweringTool tool) {
            SnippetTemplate.Arguments args;
            StructuredGraph graph = node.graph();
            assert ((ObjectStamp) node.getValue().stamp(NodeView.DEFAULT)).nonNull() : "null-check should be performed before a check on identity";
            args = new SnippetTemplate.Arguments(hasIdentitySnippet, graph.getGuardsStage(), tool.getLoweringStage());
            args.add("object", node.getValue());
            template(tool, node, args).instantiate(tool.getMetaAccess(), node, DEFAULT_REPLACER, args);
        }

    }

    @Snippet
    public static boolean hasIdentityFromMarkWord(Object object) {
        HotSpotReplacementsUtil.verifyOop(object);

        // check mark word for inline type
        final Word mark = loadWordFromObject(object, markOffset(INJECTED_VMCONFIG));
        return !mark.and(inlineTypePattern(INJECTED_VMCONFIG)).equal(inlineTypePattern(INJECTED_VMCONFIG));

    }

    @Snippet
    public static boolean hasIdentityFromKlass(Object object) {
        HotSpotReplacementsUtil.verifyOop(object);

        // alternative: from klass object
        KlassPointer hub = loadHub(object);
        return (hub.readInt(klassAccessFlagsOffset(INJECTED_VMCONFIG), KLASS_ACCESS_FLAGS_LOCATION) &
                        jvmAccIsIdentityClass(INJECTED_VMCONFIG)) != 0;

    }
}
