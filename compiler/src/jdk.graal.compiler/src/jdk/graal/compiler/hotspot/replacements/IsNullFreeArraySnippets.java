package jdk.graal.compiler.hotspot.replacements;

import static jdk.graal.compiler.hotspot.GraalHotSpotVMConfig.INJECTED_VMCONFIG;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.layoutHelperNullFreeMask;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.layoutHelperNullFreeShift;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.loadHub;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.loadWordFromObject;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.lockMaskInPlace;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.markOffset;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.nullFreeArrayMaskInPlace;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.nullFreeArrayPattern;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.readLayoutHelper;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.unlockedValue;
import static jdk.graal.compiler.replacements.SnippetTemplate.DEFAULT_REPLACER;

import org.graalvm.word.WordFactory;

import jdk.graal.compiler.api.replacements.Snippet;
import jdk.graal.compiler.core.common.type.ObjectStamp;
import jdk.graal.compiler.hotspot.word.KlassPointer;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.extended.IsNullFreeArrayNode;
import jdk.graal.compiler.nodes.spi.LoweringTool;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.util.Providers;
import jdk.graal.compiler.replacements.SnippetTemplate;
import jdk.graal.compiler.replacements.Snippets;
import jdk.graal.compiler.word.Word;
import jdk.vm.ci.code.TargetDescription;

public class IsNullFreeArraySnippets implements Snippets {
    public static class Templates extends SnippetTemplate.AbstractTemplates {
        private final SnippetTemplate.SnippetInfo isNullFreeArrayFromKlassSnippet;
        private final SnippetTemplate.SnippetInfo isNullFreeArrayFromMarkWordSnippet;
        private final TargetDescription target;

        @SuppressWarnings("this-escape")
        public Templates(OptionValues options, Providers providers, TargetDescription target) {
            super(options, providers);
            isNullFreeArrayFromKlassSnippet = snippet(providers, IsNullFreeArraySnippets.class, "isNullFreeArrayFromKlass");
            isNullFreeArrayFromMarkWordSnippet = snippet(providers, IsNullFreeArraySnippets.class, "isNullFreeArrayFromMarkWord");
            this.target = target;
        }

        public void lower(IsNullFreeArrayNode node, LoweringTool tool) {
            SnippetTemplate.Arguments args;
            StructuredGraph graph = node.graph();
            assert ((ObjectStamp) node.getValue().stamp(NodeView.DEFAULT)).nonNull();
            if (target.wordSize > 4) {
                args = new SnippetTemplate.Arguments(isNullFreeArrayFromMarkWordSnippet, graph.getGuardsStage(), tool.getLoweringStage());
                args.add("object", node.getValue());
            } else {
                args = new SnippetTemplate.Arguments(isNullFreeArrayFromKlassSnippet, graph.getGuardsStage(), tool.getLoweringStage());
                args.add("object", node.getValue());
            }
            template(tool, node, args).instantiate(tool.getMetaAccess(), node, DEFAULT_REPLACER, args);
        }

    }

    // see oop.inline.hpp is_null_free_array()
    @Snippet
    public static boolean isNullFreeArrayFromMarkWord(Object object) {
        HotSpotReplacementsUtil.verifyOop(object);

        final Word mark = loadWordFromObject(object, markOffset(INJECTED_VMCONFIG));
        final Word lockBits = mark.and(lockMaskInPlace(INJECTED_VMCONFIG));
        if (lockBits.equal(WordFactory.unsigned(unlockedValue(INJECTED_VMCONFIG)))) {
            return mark.and(WordFactory.unsigned(nullFreeArrayMaskInPlace(INJECTED_VMCONFIG))).equal(WordFactory.unsigned(nullFreeArrayPattern(INJECTED_VMCONFIG)));
        }
        return isNullFreeArrayFromKlass(object);
    }

    @Snippet
    public static boolean isNullFreeArrayFromKlass(Object object) {
        HotSpotReplacementsUtil.verifyOop(object);

        KlassPointer hub = loadHub(object);
        int layoutHelper = readLayoutHelper(hub);
        return WordFactory.signed(layoutHelper).signedShiftRight(WordFactory.signed(layoutHelperNullFreeShift(INJECTED_VMCONFIG))).and(
                        WordFactory.signed(layoutHelperNullFreeMask(INJECTED_VMCONFIG))).greaterThan(0);

    }

}
