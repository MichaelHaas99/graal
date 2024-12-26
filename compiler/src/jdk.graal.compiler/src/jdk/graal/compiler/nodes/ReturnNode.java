/*
 * Copyright (c) 2009, 2021, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.nodes;

import static jdk.graal.compiler.core.common.type.StampFactory.objectNonNull;
import static jdk.graal.compiler.nodeinfo.NodeCycles.CYCLES_2;
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_4;

import java.util.ArrayList;
import java.util.List;

import jdk.graal.compiler.core.common.type.ObjectStamp;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.core.common.type.TypeReference;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.graph.NodeInputList;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.calc.IsNullNode;
import jdk.graal.compiler.nodes.extended.TagHubNode;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import jdk.graal.compiler.nodes.java.LoadFieldNode;
import jdk.graal.compiler.nodes.memory.MemoryMapNode;
import jdk.graal.compiler.nodes.spi.LIRLowerable;
import jdk.graal.compiler.nodes.spi.NodeLIRBuilderTool;
import jdk.graal.compiler.nodes.spi.Virtualizable;
import jdk.graal.compiler.nodes.spi.VirtualizerTool;
import jdk.graal.compiler.nodes.type.StampTool;
import jdk.graal.compiler.nodes.virtual.VirtualObjectNode;
import jdk.vm.ci.code.BytecodeFrame;
import jdk.vm.ci.code.TargetDescription;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.Value;

@NodeInfo(cycles = CYCLES_2, size = SIZE_4, cyclesRationale = "Restore frame + ret", sizeRationale = "Restore frame + ret")
public final class ReturnNode extends MemoryMapControlSinkNode implements LIRLowerable, Virtualizable {

    public static final NodeClass<ReturnNode> TYPE = NodeClass.create(ReturnNode.class);

    /**
     * For a return of a scalarized inline object {@code result} contains
     *
     * Null if the scalarized inline object respresents a null value
     *
     * A pointer to an oop if the inline object has already been allocated
     *
     * The address to the class hub, with bit zero set to 1, indicating a non-null scalarized inline
     * object
     */
    @OptionalInput ValueNode result;

    @OptionalInput NodeInputList<ValueNode> scalarizedInlineType;

    public static ReturnNode returnScalarized(GraphBuilderContext b, ValueNode result, ResolvedJavaType type) {
        ArrayList<ValueNode> list = new ArrayList<>();

        ResolvedJavaField[] fields = type.getInstanceFields(true);

        // init
        LogicNode init = b.add(new LogicNegationNode(b.add(new IsNullNode(result))));
        ValuePhiNode[] phis = genScalarizedCFG(b, result, fields, init);
        list.addAll(List.of(phis));
        // list.add(b.add(new ConditionalNode(init, ConstantNode.forByte((byte) 1, b.getGraph()),
        // ConstantNode.forByte((byte) 0, b.getGraph()))));

        // PEA will replace oop with tagged hub if it is virtual
        return b.add(new ReturnNode(result, list, null));
    }

    public static ValuePhiNode[] genScalarizedCFG(GraphBuilderContext b, ValueNode result, ResolvedJavaField[] fields, LogicNode init) {
        BeginNode trueBegin = b.getGraph().add(new BeginNode());
        BeginNode falseBegin = b.getGraph().add(new BeginNode());

        b.add(new IfNode(b.add(init), trueBegin, falseBegin, ProfileData.BranchProbabilityData.unknown()));

        // plus init
        ValueNode[] loads = new ValueNode[fields.length + 1];
        ValueNode[] consts = new ValueNode[fields.length + 1];

        // true branch - inline object is non-null

        ValueNode nonNull = PiNode.create(result, objectNonNull(), trueBegin);
        for (int i = 0; i < fields.length; i++) {
            LoadFieldNode load = b.add(LoadFieldNode.create(b.getAssumptions(), nonNull, fields[i]));
            loads[i] = load;
            if (trueBegin.next() == null && load instanceof FixedNode)
                trueBegin.setNext((FixedNode) load);
        }
        loads[loads.length - 1] = ConstantNode.forByte((byte) 1, b.getGraph());
        EndNode trueEnd = b.add(new EndNode());
        if (trueBegin.next() == null)
            trueBegin.setNext(trueEnd);

        // false branch - inline object is null

        for (int i = 0; i < fields.length; i++) {
            ConstantNode load = b.add(ConstantNode.defaultForKind(fields[i].getJavaKind()));
            consts[i] = load;
        }
        consts[consts.length - 1] = ConstantNode.forByte((byte) 0, b.getGraph());
        EndNode falseEnd = b.add(new EndNode());
        if (falseBegin.next() == null)
            falseBegin.setNext(falseEnd);

        // falseBegin.setNext(falseEnd);

        // merge
        MergeNode merge = b.append(new MergeNode());
        merge.setStateAfter(b.add(new FrameState(BytecodeFrame.INVALID_FRAMESTATE_BCI)));

        ValuePhiNode[] phis = new ValuePhiNode[fields.length + 1];
        for (int i = 0; i < fields.length; i++) {
            // TODO look at inline type plugin, use merged stamp
            phis[i] = b.add(new ValuePhiNode(StampFactory.forDeclaredType(b.getAssumptions(), fields[i].getType(), false).getTrustedStamp(), merge, loads[i], consts[i]));
        }
        phis[phis.length - 1] = b.add(new ValuePhiNode(StampFactory.forKind(JavaKind.Boolean), merge, loads[phis.length - 1], consts[phis.length - 1]));

        merge.addForwardEnd(trueEnd);
        merge.addForwardEnd(falseEnd);
        return phis;
    }

    public ValueNode result() {
        return result;
    }

    public ReturnNode(ValueNode result) {
        this(result, null);
    }

    public ReturnNode(ValueNode result, ArrayList<ValueNode> scalarizedInlineType, MemoryMapNode memoryMap) {
        this(result);
        this.scalarizedInlineType = new NodeInputList<>(this, scalarizedInlineType);
    }

    public ReturnNode(ValueNode result, MemoryMapNode memoryMap) {
        super(TYPE, memoryMap);
        this.result = result;
    }

    @Override
    public void generate(NodeLIRBuilderTool gen) {
        assert verifyReturn(gen.getLIRGeneratorTool().target());
        if (scalarizedInlineType != null) {
            // return scalarized inline typ
            JavaKind[] stackKinds = new JavaKind[scalarizedInlineType.size()];
            Value[] operands = new Value[scalarizedInlineType.size()];
            for (int i = 0; i < scalarizedInlineType.size(); i++) {
                ValueNode valueNode = scalarizedInlineType.get(i);
                stackKinds[i] = valueNode.getStackKind();
                operands[i] = gen.operand(valueNode);
            }
            gen.getLIRGeneratorTool().emitScalarizedReturn(result.getStackKind(), gen.operand(result), stackKinds, operands);
        } else if (result == null) {
            gen.getLIRGeneratorTool().emitReturn(JavaKind.Void, null);
        } else {
            gen.getLIRGeneratorTool().emitReturn(result.getStackKind(), gen.operand(result));
        }
    }

    private boolean verifyReturn(TargetDescription target) {
        if (scalarizedInlineType != null) {
            return true;
        }
        if (graph().method() != null) {
            JavaKind actual = result == null ? JavaKind.Void : result.getStackKind();
            JavaKind expected = graph().method().getSignature().getReturnKind().getStackKind();
            if (actual == target.wordJavaKind && expected == JavaKind.Object) {
                // OK, we're compiling a snippet that returns a Word
                return true;
            }
            assert actual == expected : "return kind doesn't match: actual " + actual + ", expected: " + expected;
        }
        return true;
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
        ValueNode alias = tool.getAlias(result);
        if (alias instanceof VirtualObjectNode) {
            // TODO tag the hub at bit position 0
            TypeReference type = StampTool.typeReferenceOrNull(result);
            assert type != null && type.isExact() : "type should not be null for constant hub node in scalarized return";
            ConstantNode hub = ConstantNode.forConstant(tool.getStampProvider().createHubStamp(((ObjectStamp) result.stamp(NodeView.DEFAULT))),
                            tool.getConstantReflection().asObjectHub(type.getType()), tool.getMetaAccess());
            tool.addNode(hub);
            ValueNode taggedHub = new TagHubNode(hub);
            tool.addNode(taggedHub);
            // make sure oop stays virtual and return class object instead
            tool.replaceFirstInput(result, taggedHub);
        }
    }
}
