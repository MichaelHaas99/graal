package jdk.graal.compiler.hotspot.replacements;

import static jdk.graal.compiler.hotspot.GraalHotSpotVMConfig.INJECTED_VMCONFIG;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.KLASS_ACCESS_FLAGS_LOCATION;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.flatArrayKlassKind;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.flatArrayPattern;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.klassKindOffset;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.loadHub;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.loadWordFromObject;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.lockMaskInPlace;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.markOffset;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.unlockedValue;
import static jdk.graal.compiler.replacements.SnippetTemplate.DEFAULT_REPLACER;

import org.graalvm.word.WordFactory;

import jdk.graal.compiler.api.replacements.Snippet;
import jdk.graal.compiler.core.common.type.ObjectStamp;
import jdk.graal.compiler.hotspot.word.KlassPointer;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.extended.IsFlatArrayNode;
import jdk.graal.compiler.nodes.spi.LoweringTool;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.util.Providers;
import jdk.graal.compiler.replacements.SnippetTemplate;
import jdk.graal.compiler.replacements.Snippets;
import jdk.graal.compiler.word.Word;
import jdk.vm.ci.code.TargetDescription;

public class IsFlatArraySnippets implements Snippets {
    public static class Templates extends SnippetTemplate.AbstractTemplates {
        private final SnippetTemplate.SnippetInfo isFlatArrayFromKlassSnippet;
        private final SnippetTemplate.SnippetInfo isFlatArrayFromMarkWordSnippet;
        private final TargetDescription target;

        @SuppressWarnings("this-escape")
        public Templates(OptionValues options, Providers providers, TargetDescription target) {
            super(options, providers);
            isFlatArrayFromKlassSnippet = snippet(providers, IsFlatArraySnippets.class, "isFlatArrayFromKlass");
            isFlatArrayFromMarkWordSnippet = snippet(providers, IsFlatArraySnippets.class, "isFlatArrayFromMarkWord");
            this.target = target;
        }

        public void lower(IsFlatArrayNode node, LoweringTool tool) {
            SnippetTemplate.Arguments args;
            StructuredGraph graph = node.graph();
            assert ((ObjectStamp) node.getValue().stamp(NodeView.DEFAULT)).nonNull();
            if (target.wordSize > 4) {
                args = new SnippetTemplate.Arguments(isFlatArrayFromMarkWordSnippet, graph.getGuardsStage(), tool.getLoweringStage());
                args.add("object", node.getValue());
            } else {
                args = new SnippetTemplate.Arguments(isFlatArrayFromKlassSnippet, graph.getGuardsStage(), tool.getLoweringStage());
                args.add("object", node.getValue());
            }
            template(tool, node, args).instantiate(tool.getMetaAccess(), node, DEFAULT_REPLACER, args);
        }

    }

    // see oop.inline.hpp is_flatArray()
    @Snippet
    public static boolean isFlatArrayFromMarkWord(Object object) {
        HotSpotReplacementsUtil.verifyOop(object);

        final Word mark = loadWordFromObject(object, markOffset(INJECTED_VMCONFIG));
        final Word lockBits = mark.and(lockMaskInPlace(INJECTED_VMCONFIG));
        if (lockBits.equal(WordFactory.unsigned(unlockedValue(INJECTED_VMCONFIG)))) {
            return mark.and(WordFactory.unsigned(flatArrayPattern(INJECTED_VMCONFIG))).equal(WordFactory.unsigned(flatArrayPattern(INJECTED_VMCONFIG)));
        }
        return isFlatArrayFromKlass(object);

    }

    @Snippet
    public static boolean isFlatArrayFromKlass(Object object) {
        HotSpotReplacementsUtil.verifyOop(object);
        KlassPointer hub = loadHub(object);
        return hub.readInt(klassKindOffset(INJECTED_VMCONFIG), KLASS_ACCESS_FLAGS_LOCATION) == flatArrayKlassKind(INJECTED_VMCONFIG);

    }

}
