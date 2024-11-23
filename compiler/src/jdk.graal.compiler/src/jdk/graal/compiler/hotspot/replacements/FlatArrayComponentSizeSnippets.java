package jdk.graal.compiler.hotspot.replacements;

import static jdk.graal.compiler.hotspot.GraalHotSpotVMConfig.INJECTED_VMCONFIG;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.layoutHelperLog2ElementSizeMask;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.layoutHelperLog2ElementSizeShift;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.loadHub;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.readLayoutHelper;
import static jdk.graal.compiler.replacements.SnippetTemplate.DEFAULT_REPLACER;

import jdk.graal.compiler.api.replacements.Snippet;
import jdk.graal.compiler.hotspot.word.KlassPointer;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.extended.FlatArrayComponentSizeNode;
import jdk.graal.compiler.nodes.spi.LoweringTool;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.util.Providers;
import jdk.graal.compiler.replacements.SnippetTemplate;
import jdk.graal.compiler.replacements.Snippets;

public class FlatArrayComponentSizeSnippets implements Snippets {
    public static class Templates extends SnippetTemplate.AbstractTemplates {
        private final SnippetTemplate.SnippetInfo flatComponentSizeSnippet;

        @SuppressWarnings("this-escape")
        public Templates(OptionValues options, Providers providers) {
            super(options, providers);
            flatComponentSizeSnippet = snippet(providers, FlatArrayComponentSizeSnippets.class, "flatComponentSize");
        }

        public void lower(FlatArrayComponentSizeNode flatArrayComponentSizeNode, LoweringTool tool) {
            StructuredGraph graph = flatArrayComponentSizeNode.graph();

            SnippetTemplate.Arguments args = new SnippetTemplate.Arguments(flatComponentSizeSnippet, graph.getGuardsStage(), tool.getLoweringStage());
            args.add("object", flatArrayComponentSizeNode.getValue());
            template(tool, flatArrayComponentSizeNode, args).instantiate(tool.getMetaAccess(), flatArrayComponentSizeNode,
                            DEFAULT_REPLACER, args);
        }

    }

    @Snippet
    public static int flatComponentSize(Object object) {
        HotSpotReplacementsUtil.verifyOop(object);

        KlassPointer hub = loadHub(object);
        int layoutHelper = readLayoutHelper(hub);
        int log2ElementSize = (layoutHelper >> layoutHelperLog2ElementSizeShift(INJECTED_VMCONFIG)) & layoutHelperLog2ElementSizeMask(INJECTED_VMCONFIG);
        return log2ElementSize;
    }

}
