package jdk.graal.compiler.hotspot.replacements;

import static jdk.graal.compiler.hotspot.GraalHotSpotVMConfig.INJECTED_VMCONFIG;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.KLASS_KIND_LOCATION;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.flatArrayKlassKind;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.flatArrayMaskInPlace;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.flatArrayPattern;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.isUnlocked;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.klassKindOffset;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.layoutHelperNullFreeMask;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.layoutHelperNullFreeShift;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.loadHub;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.loadWordFromObject;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.markOffset;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.nullFreeArrayMaskInPlace;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.nullFreeArrayPattern;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.readLayoutHelper;
import static jdk.graal.compiler.nodes.extended.BranchProbabilityNode.FAST_PATH_PROBABILITY;
import static jdk.graal.compiler.nodes.extended.BranchProbabilityNode.probability;

import jdk.graal.compiler.api.replacements.Snippet;
import jdk.graal.compiler.core.common.type.ObjectStamp;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.hotspot.word.KlassPointer;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.extended.IsFlatArrayNode;
import jdk.graal.compiler.nodes.extended.IsNullFreeArrayNode;
import jdk.graal.compiler.nodes.spi.LoweringTool;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.util.Providers;
import jdk.graal.compiler.replacements.InstanceOfSnippetsTemplates;
import jdk.graal.compiler.replacements.SnippetTemplate;
import jdk.graal.compiler.replacements.Snippets;
import jdk.graal.compiler.word.Word;
import jdk.vm.ci.code.TargetDescription;

public class ValhallaArrayLayoutKindSnippets implements Snippets {
    public static class Templates extends InstanceOfSnippetsTemplates {
        private final SnippetTemplate.SnippetInfo isFlatArrayFromKlassSnippet;
        private final SnippetTemplate.SnippetInfo isFlatArrayFromMarkWordSnippet;
        private final SnippetTemplate.SnippetInfo isNullFreeArrayFromKlassSnippet;
        private final SnippetTemplate.SnippetInfo isNullFreeArrayFromMarkWordSnippet;
        private final TargetDescription target;

        @SuppressWarnings("this-escape")
        public Templates(OptionValues options, Providers providers, TargetDescription target) {
            super(options, providers);
            isFlatArrayFromKlassSnippet = snippet(providers, ValhallaArrayLayoutKindSnippets.class, "isFlatArrayFromKlass");
            isFlatArrayFromMarkWordSnippet = snippet(providers, ValhallaArrayLayoutKindSnippets.class, "isFlatArrayFromMarkWord");
            isNullFreeArrayFromKlassSnippet = snippet(providers, ValhallaArrayLayoutKindSnippets.class, "isNullFreeArrayFromKlass");
            isNullFreeArrayFromMarkWordSnippet = snippet(providers, ValhallaArrayLayoutKindSnippets.class, "isNullFreeArrayFromMarkWord");
            this.target = target;
        }

        @Override
        protected SnippetTemplate.Arguments makeArguments(InstanceOfUsageReplacer replacer, LoweringTool tool) {
            ValueNode node = replacer.instanceOf;
            SnippetTemplate.Arguments args;
            StructuredGraph graph = node.graph();
            if (node instanceof IsFlatArrayNode isFlatArrayNode) {
                assert ((ObjectStamp) isFlatArrayNode.getValue().stamp(NodeView.DEFAULT)).nonNull();
                if (target.wordSize > 4) {
                    args = new SnippetTemplate.Arguments(isFlatArrayFromMarkWordSnippet, graph.getGuardsStage(), tool.getLoweringStage());
                } else {
                    args = new SnippetTemplate.Arguments(isFlatArrayFromKlassSnippet, graph.getGuardsStage(), tool.getLoweringStage());
                }
                args.add("object", isFlatArrayNode.getValue());
            } else if (node instanceof IsNullFreeArrayNode isNullFreeArrayNode) {
                assert ((ObjectStamp) isNullFreeArrayNode.getValue().stamp(NodeView.DEFAULT)).nonNull();
                if (target.wordSize > 4) {
                    args = new SnippetTemplate.Arguments(isNullFreeArrayFromMarkWordSnippet, graph.getGuardsStage(), tool.getLoweringStage());
                } else {
                    args = new SnippetTemplate.Arguments(isNullFreeArrayFromKlassSnippet, graph.getGuardsStage(), tool.getLoweringStage());
                }
                args.add("object", isNullFreeArrayNode.getValue());

            } else {
                throw GraalError.shouldNotReachHere(node + " " + replacer);
            }

            args.add("trueValue", replacer.trueValue);
            args.add("falseValue", replacer.falseValue);
            return args;
        }

    }

    // see oop.inline.hpp is_flatArray()
    @Snippet
    public static Object isFlatArrayFromMarkWord(Object object, Object trueValue, Object falseValue) {
        HotSpotReplacementsUtil.verifyOop(object);

        final Word mark = loadWordFromObject(object, markOffset(INJECTED_VMCONFIG));
        if (probability(FAST_PATH_PROBABILITY, isUnlocked(mark))) {
            return mark.and(Word.unsigned(flatArrayMaskInPlace(INJECTED_VMCONFIG))).equal(Word.unsigned(flatArrayPattern(INJECTED_VMCONFIG))) ? trueValue : falseValue;
        }
        return isFlatArrayFromKlass(object, trueValue, falseValue);

    }

    @Snippet
    public static Object isFlatArrayFromKlass(Object object, Object trueValue, Object falseValue) {
        HotSpotReplacementsUtil.verifyOop(object);
        KlassPointer hub = loadHub(object);
        return hub.readInt(klassKindOffset(INJECTED_VMCONFIG), KLASS_KIND_LOCATION) == flatArrayKlassKind(INJECTED_VMCONFIG) ? trueValue : falseValue;

    }

    // see oop.inline.hpp is_null_free_array()
    @Snippet
    public static Object isNullFreeArrayFromMarkWord(Object object, Object trueValue, Object falseValue) {
        HotSpotReplacementsUtil.verifyOop(object);

        final Word mark = loadWordFromObject(object, markOffset(INJECTED_VMCONFIG));
        if (probability(FAST_PATH_PROBABILITY, isUnlocked(mark))) {
            return mark.and(Word.unsigned(nullFreeArrayMaskInPlace(INJECTED_VMCONFIG))).equal(Word.unsigned(nullFreeArrayPattern(INJECTED_VMCONFIG))) ? trueValue : falseValue;
        }
        return isNullFreeArrayFromKlass(object, trueValue, falseValue);
    }

    @Snippet
    public static Object isNullFreeArrayFromKlass(Object object, Object trueValue, Object falseValue) {
        HotSpotReplacementsUtil.verifyOop(object);

        KlassPointer hub = loadHub(object);
        int layoutHelper = readLayoutHelper(hub);
        return Word.signed(layoutHelper).signedShiftRight(Word.signed(layoutHelperNullFreeShift(INJECTED_VMCONFIG))).and(
                        Word.signed(layoutHelperNullFreeMask(INJECTED_VMCONFIG))).greaterThan(0) ? trueValue : falseValue;

    }

}
