package jdk.graal.compiler.hotspot.replacements;

import static jdk.graal.compiler.nodes.extended.BranchProbabilityNode.LIKELY_PROBABILITY;
import static jdk.graal.compiler.nodes.extended.BranchProbabilityNode.probability;
import static jdk.graal.compiler.replacements.SnippetTemplate.DEFAULT_REPLACER;

import jdk.graal.compiler.api.replacements.Snippet;
import jdk.graal.compiler.hotspot.word.KlassPointer;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.extended.ReturnResultDeciderNode;
import jdk.graal.compiler.nodes.spi.LoweringTool;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.util.Providers;
import jdk.graal.compiler.replacements.SnippetTemplate;
import jdk.graal.compiler.replacements.Snippets;
import jdk.graal.compiler.word.Word;

public class ReturnResultDeciderSnippets implements Snippets {
    public static class Templates extends SnippetTemplate.AbstractTemplates {
        private final SnippetTemplate.SnippetInfo returnResultSnippet;

        @SuppressWarnings("this-escape")
        public Templates(OptionValues options, Providers providers) {
            super(options, providers);
            returnResultSnippet = snippet(providers, ReturnResultDeciderSnippets.class, "returnResultSnippet");

        }

        public void lower(ReturnResultDeciderNode node, LoweringTool tool) {
            SnippetTemplate.Arguments args;
            StructuredGraph graph = node.graph();
            args = new SnippetTemplate.Arguments(returnResultSnippet, graph.getGuardsStage(), tool.getLoweringStage());

            args.add("isNotNull", node.getIsNotNull());
            args.add("oop", node.getOop());
            args.add("hub", node.getHub());
            template(tool, node, args).instantiate(tool.getMetaAccess(), node, DEFAULT_REPLACER, args);
        }

    }

    @Snippet
    public static Word returnResultSnippet(int isNotNull, Object oop, KlassPointer hub) {
        // @formatter:off
        /*
         * if(scalarized inline object is not null){
         *      if(oop is not null) return oop;
         *      else return taggedHub;
         * } else {
         *      return null;
         * }
         */
        // @formatter:on
        if (probability(LIKELY_PROBABILITY, isNotNull == 1)) {
            Word wordOop = Word.objectToTrackedPointer(oop);
            Word wordHub = hub.asWord();
            Word wordTaggedHub = wordHub.or(1);
            if (wordOop.isNull()) {
                return wordTaggedHub;
            } else {
                return wordOop;
            }
        } else {
            return Word.nullPointer();
        }
    }
}
