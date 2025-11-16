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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jdk.graal.compiler.debug.DebugCloseable;
import jdk.graal.compiler.nodes.GraphState;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.extended.InlineTypeNode;
import jdk.graal.compiler.nodes.extended.ReadMultiValueNode;
import jdk.graal.compiler.nodes.java.MethodCallTargetNode;
import jdk.graal.compiler.nodes.spi.CoreProviders;
import jdk.graal.compiler.nodes.util.InlineTypeUtil;
import jdk.graal.compiler.replacements.nodes.ResolvedMethodHandleCallTargetNode;
import jdk.graal.compiler.serviceprovider.GraalValhallaServices;
import jdk.vm.ci.meta.ResolvedJavaMethod;

/**
 * Replace the arguments of a {@link MethodCallTargetNode} by the scalarized arguments demanded from
 * the Valhalla Calling Convention and also attaches multiple nodes to each {@code Invoke} to
 * confirm to the Valhalla Return Convention. This is done after inlining such that overhead of dead
 * scalarization graphs is avoided, but before PEA such that materializations don't happen.
 */
public class ValhallaCallingConventionPhase extends PostRunCanonicalizationPhase<CoreProviders> {

    public ValhallaCallingConventionPhase(CanonicalizerPhase canonicalizer) {
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
        graph.getGraphState().setDuringStage(GraphState.StageFlag.VALHALLA_CALLING_CONVENTION);
        if (context.getValhallaOptionsProvider().callingConventionEnabled() || context.getValhallaOptionsProvider().returnConventionEnabled()) {
            for (MethodCallTargetNode n : graph.getNodes(MethodCallTargetNode.TYPE)) {
                try (DebugCloseable scope = n.graph().withNodeSourcePosition(n)) {
                    ResolvedJavaMethod targetMethod = n.targetMethod();
                    if (context.getValhallaOptionsProvider().callingConventionEnabled()) {
                        if (targetMethod.hasScalarizedParameters() && !(n instanceof ResolvedMethodHandleCallTargetNode) && !GraalValhallaServices.hasCallingConventionMismatch(targetMethod)) {
                            /*
                             * TODO: Phases like the MultiTypeGuardInliningInfo may delete the
                             * placeholder for the fallback invoke. We could make sure that
                             * placeholders are always re-inserted here instead of handling them
                             * explicitly somewhere else, but this would not be very clean. To do so
                             * use: InlineTypeUtil.handleDevirtualizationOnCallTarget(n,
                             * n.targetMethod(), targetMethod, false);
                             * 
                             */
                            List<ValueNode> arguments = n.arguments();
                            List<ValueNode> scalarizedArguments = new ArrayList<>(arguments);
                            boolean[] alreadyProcessed = new boolean[arguments.size()];
                            for (int i = arguments.size() - 1; i >= 0; i--) {
                                if (alreadyProcessed[i]) {
                                    continue;
                                }
                                ValueNode argument = arguments.get(i);
                                if (argument instanceof InlineTypeNode.Placeholder placeholder) {
                                    // handle the placeholder
                                    ReadMultiValueNode.MultiValues multiValues = placeholder.makeReplacement();
                                    for (int j = i; j >= 0; j--) {
                                        if (scalarizedArguments.get(j) == argument) {
                                            boolean isNonNull = GraalValhallaServices.isParameterNullFree(targetMethod, j, true);
                                            scalarizedArguments.remove(j);
                                            scalarizedArguments.addAll(j, List.of(multiValues.fieldValues()));
                                            if (!isNonNull) {
                                                scalarizedArguments.add(j, multiValues.nonNull());
                                            }
                                            alreadyProcessed[j] = true;
                                        }
                                    }
                                } else {
                                    // just add the argument
                                    scalarizedArguments.set(i, argument);
                                    alreadyProcessed[i] = true;
                                }
                            }
                            arguments.clear();
                            arguments.addAll(scalarizedArguments);
                        }
                    }

                    if (context.getValhallaOptionsProvider().returnConventionEnabled()) {
                        if (n.targetMethod().hasScalarizedReturn() && !(n instanceof ResolvedMethodHandleCallTargetNode)) {
                            InlineTypeUtil.handleScalarizedReturnOnInvoke(n.invoke(), context.getMetaAccess());
                        }
                    }

                }
            }
        }

    }

    @Override
    public void updateGraphState(GraphState graphState) {
        super.updateGraphState(graphState);
        graphState.setAfterStage(GraphState.StageFlag.VALHALLA_CALLING_CONVENTION);
    }

    @Override
    public boolean checkContract() {
        return false;
    }
}
