package jdk.graal.compiler.hotspot.replacements;

import static jdk.graal.compiler.replacements.SnippetTemplate.DEFAULT_REPLACER;

import org.graalvm.word.LocationIdentity;

import jdk.graal.compiler.api.replacements.Snippet;
import jdk.graal.compiler.api.replacements.Snippet.ConstantParameter;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.extended.DelayedRawComparison;
import jdk.graal.compiler.nodes.extended.RawLoadNode;
import jdk.graal.compiler.nodes.spi.LoweringTool;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.util.Providers;
import jdk.graal.compiler.replacements.SnippetTemplate;
import jdk.graal.compiler.replacements.Snippets;
import jdk.vm.ci.meta.JavaKind;

public class DelayedRawComparisonSnippets implements Snippets {
    public static class Templates extends SnippetTemplate.AbstractTemplates {
        private final SnippetTemplate.SnippetInfo delayedRawBooleanComparisonSnippet;
        private final SnippetTemplate.SnippetInfo delayedRawByteComparisonSnippet;
        private final SnippetTemplate.SnippetInfo delayedRawCharComparisonSnippet;
        private final SnippetTemplate.SnippetInfo delayedRawShortComparisonSnippet;
        private final SnippetTemplate.SnippetInfo delayedRawIntComparisonSnippet;
        private final SnippetTemplate.SnippetInfo delayedRawLongComparisonSnippet;
        private final SnippetTemplate.SnippetInfo delayedRawFloatComparisonSnippet;
        private final SnippetTemplate.SnippetInfo delayedRawDoubleComparisonSnippet;
        private final SnippetTemplate.SnippetInfo delayedRawObjectComparisonSnippet;

        @SuppressWarnings("this-escape")
        public Templates(OptionValues options, Providers providers) {
            super(options, providers);
            delayedRawBooleanComparisonSnippet = snippet(providers, DelayedRawComparisonSnippets.class, "delayedRawBooleanComparison");
            delayedRawByteComparisonSnippet = snippet(providers, DelayedRawComparisonSnippets.class, "delayedRawByteComparison");
            delayedRawCharComparisonSnippet = snippet(providers, DelayedRawComparisonSnippets.class, "delayedRawCharComparison");
            delayedRawShortComparisonSnippet = snippet(providers, DelayedRawComparisonSnippets.class, "delayedRawShortComparison");
            delayedRawIntComparisonSnippet = snippet(providers, DelayedRawComparisonSnippets.class, "delayedRawIntComparison");
            delayedRawLongComparisonSnippet = snippet(providers, DelayedRawComparisonSnippets.class, "delayedRawLongComparison");
            delayedRawFloatComparisonSnippet = snippet(providers, DelayedRawComparisonSnippets.class, "delayedRawFloatComparison");
            delayedRawDoubleComparisonSnippet = snippet(providers, DelayedRawComparisonSnippets.class, "delayedRawDoubleComparison");
            delayedRawObjectComparisonSnippet = snippet(providers, DelayedRawComparisonSnippets.class, "delayedRawObjectComparison");
        }

        public void lower(DelayedRawComparison node, LoweringTool tool) {
            SnippetTemplate.Arguments args;
            StructuredGraph graph = node.graph();
            JavaKind kind = node.getConstantKind();
            if (kind == JavaKind.Boolean) {
                args = new SnippetTemplate.Arguments(delayedRawBooleanComparisonSnippet, graph.getGuardsStage(), tool.getLoweringStage());
            } else if (kind == JavaKind.Byte) {
                args = new SnippetTemplate.Arguments(delayedRawByteComparisonSnippet, graph.getGuardsStage(), tool.getLoweringStage());
            } else if (kind == JavaKind.Char) {
                args = new SnippetTemplate.Arguments(delayedRawCharComparisonSnippet, graph.getGuardsStage(), tool.getLoweringStage());
            } else if (kind == JavaKind.Short) {
                args = new SnippetTemplate.Arguments(delayedRawShortComparisonSnippet, graph.getGuardsStage(), tool.getLoweringStage());
            } else if (kind == JavaKind.Int) {
                args = new SnippetTemplate.Arguments(delayedRawIntComparisonSnippet, graph.getGuardsStage(), tool.getLoweringStage());
            } else if (kind == JavaKind.Long) {
                args = new SnippetTemplate.Arguments(delayedRawLongComparisonSnippet, graph.getGuardsStage(), tool.getLoweringStage());
            } else if (kind == JavaKind.Float) {
                args = new SnippetTemplate.Arguments(delayedRawFloatComparisonSnippet, graph.getGuardsStage(), tool.getLoweringStage());
            } else if (kind == JavaKind.Double) {
                args = new SnippetTemplate.Arguments(delayedRawDoubleComparisonSnippet, graph.getGuardsStage(), tool.getLoweringStage());
            } else if (kind == JavaKind.Object) {
                args = new SnippetTemplate.Arguments(delayedRawObjectComparisonSnippet, graph.getGuardsStage(), tool.getLoweringStage());
            } else {
                // should not be reachable
                throw GraalError.shouldNotReachHere("no valid java kind");
            }
            args.add("x", node.getObject1());
            args.add("y", node.getObject2());
            args.addConst("offset", node.getIntOffset()/* node.asJavaConstant().asInt() */);
            args.addConst("locationIdentity", node.getKilledLocationIdentity());
            template(tool, node, args).instantiate(tool.getMetaAccess(), node, DEFAULT_REPLACER, args);
        }

    }

    @Snippet
    public static boolean delayedRawBooleanComparison(Object x, Object y, @ConstantParameter int offset, @ConstantParameter LocationIdentity locationIdentity) {
        boolean xLoad = RawLoadNode.loadBoolean(x, offset, JavaKind.Boolean, locationIdentity);
        boolean yLoad = RawLoadNode.loadBoolean(y, offset, JavaKind.Boolean, locationIdentity);
        return xLoad == yLoad;
    }

    @Snippet
    public static boolean delayedRawByteComparison(Object x, Object y, @ConstantParameter int offset, @ConstantParameter LocationIdentity locationIdentity) {
        byte xLoad = RawLoadNode.loadByte(x, offset, JavaKind.Byte, locationIdentity);
        byte yLoad = RawLoadNode.loadByte(y, offset, JavaKind.Byte, locationIdentity);
        return xLoad == yLoad;
    }

    @Snippet
    public static boolean delayedRawCharComparison(Object x, Object y, @ConstantParameter int offset, @ConstantParameter LocationIdentity locationIdentity) {
        char xLoad = RawLoadNode.loadChar(x, offset, JavaKind.Char, locationIdentity);
        char yLoad = RawLoadNode.loadChar(y, offset, JavaKind.Char, locationIdentity);
        return xLoad == yLoad;
    }

    @Snippet
    public static boolean delayedRawShortComparison(Object x, Object y, @ConstantParameter int offset, @ConstantParameter LocationIdentity locationIdentity) {
        short xLoad = RawLoadNode.loadShort(x, offset, JavaKind.Short, locationIdentity);
        short yLoad = RawLoadNode.loadShort(y, offset, JavaKind.Short, locationIdentity);
        return xLoad == yLoad;
    }

    @Snippet
    public static boolean delayedRawIntComparison(Object x, Object y, @ConstantParameter int offset, @ConstantParameter LocationIdentity locationIdentity) {
        int xLoad = RawLoadNode.loadInt(x, offset, JavaKind.Int, locationIdentity);
        int yLoad = RawLoadNode.loadInt(y, offset, JavaKind.Int, locationIdentity);
        return xLoad == yLoad;
    }

    @Snippet
    public static boolean delayedRawLongComparison(Object x, Object y, @ConstantParameter int offset, @ConstantParameter LocationIdentity locationIdentity) {
        long xLoad = RawLoadNode.loadLong(x, offset, JavaKind.Long, locationIdentity);
        long yLoad = RawLoadNode.loadLong(y, offset, JavaKind.Long, locationIdentity);
        return xLoad == yLoad;
    }

    @Snippet
    public static boolean delayedRawFloatComparison(Object x, Object y, @ConstantParameter int offset, @ConstantParameter LocationIdentity locationIdentity) {
        float xLoad = RawLoadNode.loadFloat(x, offset, JavaKind.Float, locationIdentity);
        float yLoad = RawLoadNode.loadFloat(y, offset, JavaKind.Float, locationIdentity);
        return xLoad == yLoad;
    }

    @Snippet
    public static boolean delayedRawDoubleComparison(Object x, Object y, @ConstantParameter int offset, @ConstantParameter LocationIdentity locationIdentity) {
        double xLoad = RawLoadNode.loadDouble(x, offset, JavaKind.Double, locationIdentity);
        double yLoad = RawLoadNode.loadDouble(y, offset, JavaKind.Double, locationIdentity);
        return xLoad == yLoad;
    }

    @Snippet
    public static boolean delayedRawObjectComparison(Object x, Object y, @ConstantParameter int offset, @ConstantParameter LocationIdentity locationIdentity) {
        Object xLoad = RawLoadNode.load(x, offset, JavaKind.Object, locationIdentity);
        Object yLoad = RawLoadNode.load(y, offset, JavaKind.Object, locationIdentity);
        return xLoad == yLoad;
    }

}
