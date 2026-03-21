/*
 * Copyright (c) 2013, 2022, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.phases.common;

import java.util.Optional;

import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.nodes.GraphState;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.extended.InlineTypeNode;
import jdk.graal.compiler.nodes.extended.ReadMultiValueNode;
import jdk.graal.compiler.nodes.java.LoadFieldNode;
import jdk.graal.compiler.nodes.spi.CoreProviders;
import jdk.graal.compiler.serviceprovider.GraalValhallaServices;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaType;

public class ValhallaMultiValuePhase extends PostRunCanonicalizationPhase<CoreProviders> {

    public ValhallaMultiValuePhase(CanonicalizerPhase canonicalizer) {
        super(canonicalizer);
    }

    @Override
    public Optional<NotApplicable> notApplicableTo(GraphState graphState) {
        return NotApplicable.ifAny(
                        NotApplicable.unlessRunBefore(this, GraphState.StageFlag.HIGH_TIER_LOWERING, graphState),
                        NotApplicable.unlessRunBefore(this, GraphState.StageFlag.FINAL_CANONICALIZATION, graphState));
    }

    @SuppressWarnings("try")
    @Override
    protected void run(StructuredGraph graph, CoreProviders context) {
        if (context.getValhallaOptionsProvider().valhallaEnabled()) {
            for (LoadFieldNode node : graph.getNodes(LoadFieldNode.TYPE)) {
                if (!node.isMultiValue() || node.onlyReadMuliValueUsages()) {
                    continue;
                }
                ResolvedJavaField field = node.field();
                if (GraalValhallaServices.isFlat(field)) {
                    if (!GraalValhallaServices.isNullFreeInlineType(field)) {
                        // field is flat and nullable
                        GraalError.shouldNotReachHere("can't handle nullable flat fields");

                    } else {
                        // field is flat and null-restricted
                        ResolvedJavaType type = (ResolvedJavaType) field.getType();
                        ReadMultiValueNode.MultiValues multiValues = ReadMultiValueNode.createNodes(node, type, graph.getAssumptions());
                        InlineTypeNode inlineTypeNode = graph.addOrUniqueWithInputs(new InlineTypeNode(type, multiValues.oop(), multiValues.fieldValues(), multiValues.nonNull(), false));
                        graph.addAfterFixed(node, inlineTypeNode);
                        node.replaceAtUsages(inlineTypeNode, v -> !(v instanceof ReadMultiValueNode n && n.getMultiValueNode() == node));
                    }
                }
            }
        }
    }

    @Override
    public boolean checkContract() {
        return false;
    }
}
