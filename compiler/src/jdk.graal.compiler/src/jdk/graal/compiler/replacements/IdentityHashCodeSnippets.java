/*
 * Copyright (c) 2016, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package jdk.graal.compiler.replacements;

import static jdk.graal.compiler.core.common.spi.ForeignCallDescriptor.CallSideEffect.NO_SIDE_EFFECT;
import static jdk.graal.compiler.hotspot.GraalHotSpotVMConfig.INJECTED_VMCONFIG;
import static jdk.graal.compiler.hotspot.meta.HotSpotForeignCallDescriptor.Transition.LEAF;
import static jdk.graal.compiler.hotspot.meta.HotSpotForeignCallsProviderImpl.NO_LOCATIONS;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.inlineTypePattern;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.loadWordFromObject;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.markOffset;
import static jdk.graal.compiler.nodes.extended.BranchProbabilityNode.NOT_FREQUENT_PROBABILITY;
import static jdk.graal.compiler.nodes.extended.BranchProbabilityNode.probability;

import org.graalvm.word.LocationIdentity;

import jdk.graal.compiler.api.replacements.Snippet;
import jdk.graal.compiler.core.common.spi.ForeignCallDescriptor;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.hotspot.meta.HotSpotForeignCallDescriptor;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.extended.ForeignCallNode;
import jdk.graal.compiler.nodes.spi.LoweringTool;
import jdk.graal.compiler.nodes.type.StampTool;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.util.Providers;
import jdk.graal.compiler.replacements.SnippetTemplate.AbstractTemplates;
import jdk.graal.compiler.replacements.SnippetTemplate.Arguments;
import jdk.graal.compiler.replacements.SnippetTemplate.SnippetInfo;
import jdk.graal.compiler.replacements.nodes.IdentityHashCodeNode;
import jdk.graal.compiler.word.Word;

public abstract class IdentityHashCodeSnippets implements Snippets {

    @Snippet
    private int identityHashCodeSnippet(final Object thisObj, @Snippet.ConstantParameter boolean canBeInlineType, @Snippet.ConstantParameter boolean isInlineType) {
        if (probability(NOT_FREQUENT_PROBABILITY, thisObj == null)) {
            return 0;
        }

        // check mark word for inline type
        if (canBeInlineType) {
            Word mark = loadWordFromObject(thisObj, markOffset(INJECTED_VMCONFIG));
            if (isInlineType || mark.and(inlineTypePattern(INJECTED_VMCONFIG)).equal(inlineTypePattern(INJECTED_VMCONFIG))) {
                return valueObjectHashCodeStubC(VALUEOBJECTHASHCODE, thisObj);
            }
        }

        return computeIdentityHashCode(thisObj);
    }

    public static final HotSpotForeignCallDescriptor VALUEOBJECTHASHCODE = new HotSpotForeignCallDescriptor(LEAF, NO_SIDE_EFFECT, NO_LOCATIONS, "valueObjectHashCode", int.class,
                    Object.class);

    @Node.NodeIntrinsic(ForeignCallNode.class)
    private static native int valueObjectHashCodeStubC(@Node.ConstantNodeParameter ForeignCallDescriptor descriptor, Object x);

    protected abstract int computeIdentityHashCode(Object thisObj);

    public static class Templates extends AbstractTemplates {

        private final SnippetInfo identityHashCodeSnippet;

        @SuppressWarnings("this-escape")
        public Templates(IdentityHashCodeSnippets receiver, OptionValues options, Providers providers, LocationIdentity locationIdentity) {
            super(options, providers);

            identityHashCodeSnippet = snippet(providers,
                            IdentityHashCodeSnippets.class,
                            "identityHashCodeSnippet",
                            null,
                            receiver,
                            locationIdentity);
        }

        public void lower(IdentityHashCodeNode node, LoweringTool tool) {
            StructuredGraph graph = node.graph();
            Arguments args = new Arguments(identityHashCodeSnippet, graph.getGuardsStage(), tool.getLoweringStage());
            args.add("thisObj", node.object());
            args.add("canBeInlineType", StampTool.canBeInlineType(node.object().stamp(NodeView.DEFAULT), tool.getValhallaOptionsProvider()));
            args.add("isInlineType", StampTool.isInlineType(node.object().stamp(NodeView.DEFAULT), tool.getValhallaOptionsProvider()));
            SnippetTemplate template = template(tool, node, args);
            template.instantiate(tool.getMetaAccess(), node, SnippetTemplate.DEFAULT_REPLACER, args);
        }
    }
}
