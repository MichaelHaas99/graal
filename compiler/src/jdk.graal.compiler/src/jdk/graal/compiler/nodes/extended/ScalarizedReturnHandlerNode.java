/*
 * Copyright (c) 2011, 2020, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.nodes.extended;

import static jdk.graal.compiler.replacements.MethodHandlePlugin.STORE_INLINE_TYPE_FIELDS_TO;

import org.graalvm.word.LocationIdentity;

import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.DeoptBciSupplier;
import jdk.graal.compiler.nodes.FrameState;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.memory.AbstractMemoryCheckpoint;
import jdk.graal.compiler.nodes.memory.SingleMemoryKill;
import jdk.graal.compiler.nodes.spi.Lowerable;
import jdk.graal.compiler.nodes.spi.LoweringTool;

/**
 * This node handles the case that we don't know at compile time if a call returns a value object in
 * scalarized form. This appears at method handles calls or at calls with an unresolved return type.
 */
@NodeInfo
public class ScalarizedReturnHandlerNode extends AbstractMemoryCheckpoint implements SingleMemoryKill, Lowerable, DeoptBciSupplier {
    public static final NodeClass<ScalarizedReturnHandlerNode> TYPE = NodeClass.create(ScalarizedReturnHandlerNode.class);

    @Input ValueNode input;
    int bci;

    private ScalarizedReturnHandlerNode(NodeClass<? extends AbstractMemoryCheckpoint> c, Stamp stamp) {
        super(c, stamp);
    }

    private ScalarizedReturnHandlerNode(NodeClass<? extends AbstractMemoryCheckpoint> c, Stamp stamp, FrameState stateAfter) {
        super(c, stamp, stateAfter);
    }

    public ScalarizedReturnHandlerNode(ValueNode input, Stamp stamp) {
        super(TYPE, stamp);
        this.input = input;
    }

    public ScalarizedReturnHandlerNode(ValueNode input, Stamp stamp, FrameState stateAfter) {
        super(TYPE, stamp, stateAfter);
        this.input = input;
    }

    @Override
    public LocationIdentity getKilledLocationIdentity() {
        return LocationIdentity.init();
    }

    @Override
    public int bci() {
        return bci;
    }

    @Override
    public void setBci(int bci) {
        this.bci = bci;
    }

    @Override
    public void lower(LoweringTool tool) {
        if (tool.getLoweringStage() == LoweringTool.StandardLoweringStage.HIGH_TIER) {
            // TODO: implement fast path see PhaseMacroExpand::expand_mh_intrinsic_return
            StructuredGraph graph = this.graph();
            graph.addAfterFixed(this, graph.add(MembarNode.forInitialization()));
            ForeignCallNode allocateValueObject = graph.add(new ForeignCallNode(tool.getForeignCalls().lookupForeignCall(STORE_INLINE_TYPE_FIELDS_TO).getDescriptor(), input));
            allocateValueObject.setBci(bci);
            allocateValueObject.setStateAfter(stateAfter());
            graph.replaceFixedWithFixed(this, allocateValueObject);
        }
    }
}
