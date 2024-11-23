package jdk.graal.compiler.hotspot.replacements;

import static jdk.graal.compiler.hotspot.GraalHotSpotVMConfig.INJECTED_VMCONFIG;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.layoutHelperNullFreeMask;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.layoutHelperNullFreeShift;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.loadHub;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.loadWordFromObject;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.markOffset;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.nullFreeArrayPattern;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.readLayoutHelper;

import jdk.graal.compiler.api.replacements.Snippet;
import jdk.graal.compiler.core.common.type.ObjectStamp;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.hotspot.word.KlassPointer;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.extended.IsFlatArrayNode;
import jdk.graal.compiler.nodes.spi.LoweringTool;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.util.Providers;
import jdk.graal.compiler.replacements.InstanceOfSnippetsTemplates;
import jdk.graal.compiler.replacements.SnippetTemplate;
import jdk.graal.compiler.replacements.Snippets;
import jdk.graal.compiler.word.Word;
import jdk.vm.ci.code.TargetDescription;

public class IsNullFreeArraySnippets implements Snippets {
    public static class Templates extends InstanceOfSnippetsTemplates {
        private final SnippetTemplate.SnippetInfo isNullFreeArrayFromKlassSnippet;
        private final SnippetTemplate.SnippetInfo isNullFreeArrayFromMarkWordSnippet;
        private final TargetDescription target;

        @SuppressWarnings("this-escape")
        public Templates(OptionValues options, Providers providers, TargetDescription target) {
            super(options, providers);
            isNullFreeArrayFromKlassSnippet = snippet(providers, IsFlatArraySnippets.class, "isNullFreeArrayFromKlass");
            isNullFreeArrayFromMarkWordSnippet = snippet(providers, IsFlatArraySnippets.class, "isNullFreeArrayFromMarkWord");
            this.target = target;
        }

        @Override
        protected SnippetTemplate.Arguments makeArguments(InstanceOfUsageReplacer replacer, LoweringTool tool) {
            ValueNode node = replacer.instanceOf;
            SnippetTemplate.Arguments args;
            if (node instanceof IsFlatArrayNode isFlatArrayNode) {
                StructuredGraph graph = node.graph();
                assert ((ObjectStamp) isFlatArrayNode.getValue().stamp(NodeView.DEFAULT)).nonNull();
                if (target.wordSize > 4) {
                    args = new SnippetTemplate.Arguments(isNullFreeArrayFromMarkWordSnippet, graph.getGuardsStage(), tool.getLoweringStage());
                    args.add("object", isFlatArrayNode.getValue());
                } else {
                    args = new SnippetTemplate.Arguments(isNullFreeArrayFromKlassSnippet, graph.getGuardsStage(), tool.getLoweringStage());
                    args.add("object", isFlatArrayNode.getValue());
                }
                args.add("trueValue", replacer.trueValue);
                args.add("falseValue", replacer.falseValue);
                return args;
            } else {
                throw GraalError.shouldNotReachHere(node + " " + replacer); // ExcludeFromJacocoGeneratedReport
            }
        }

    }

    @Snippet
    public static Object isNullFreeArrayFromMarkWord(Object object, Object trueValue, Object falseValue) {
        HotSpotReplacementsUtil.verifyOop(object);

        final Word mark = loadWordFromObject(object, markOffset(INJECTED_VMCONFIG));
        if (mark.and(nullFreeArrayPattern(INJECTED_VMCONFIG)).equal(nullFreeArrayPattern(INJECTED_VMCONFIG)))
            return trueValue;
        return falseValue;
    }

    @Snippet
    public static Object isNullFreeArrayFromKlass(Object object, Object trueValue, Object falseValue) {
        HotSpotReplacementsUtil.verifyOop(object);

        KlassPointer hub = loadHub(object);
        int layoutHelper = readLayoutHelper(hub);
        int maskedValue = (layoutHelper >> layoutHelperNullFreeShift(INJECTED_VMCONFIG)) & layoutHelperNullFreeMask(INJECTED_VMCONFIG);
        return maskedValue > 0 ? trueValue : falseValue;

    }

}
