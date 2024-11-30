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
package jdk.graal.compiler.nodes.java;

import static jdk.graal.compiler.nodeinfo.NodeCycles.CYCLES_8;
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_8;

import java.util.ArrayList;
import java.util.List;

import org.graalvm.word.LocationIdentity;

import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.graph.NodeInputList;
import jdk.graal.compiler.nodeinfo.InputType;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.DeoptimizeNode;
import jdk.graal.compiler.nodes.FrameState;
import jdk.graal.compiler.nodes.StateSplit;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.extended.GuardingNode;
import jdk.graal.compiler.nodes.memory.SingleMemoryKill;
import jdk.graal.compiler.nodes.spi.Canonicalizable;
import jdk.graal.compiler.nodes.spi.CanonicalizerTool;
import jdk.graal.compiler.nodes.spi.Lowerable;
import jdk.graal.compiler.nodes.spi.Virtualizable;
import jdk.graal.compiler.nodes.spi.VirtualizerTool;
import jdk.vm.ci.meta.DeoptimizationAction;
import jdk.vm.ci.meta.DeoptimizationReason;
import jdk.vm.ci.meta.JavaKind;

/**
 * The {@code StoreFlatIndexedNode} represents a write to a flat array element.
 */
@NodeInfo(nameTemplate = "StoreFlatIndexedNode", cycles = CYCLES_8, size = SIZE_8)
public final class StoreFlatIndexedNode extends AccessIndexedNode implements StateSplit, Lowerable, Virtualizable, Canonicalizable, SingleMemoryKill {

    public static class StoreIndexedWrapper {

        private final int index;
        private final JavaKind elementKind;
        private final int additionalOffset;
        private final int shift;

        public StoreIndexedWrapper(int index, JavaKind elementKind, int additionalOffset, int shift) {
            this.index = index;
            this.elementKind = elementKind;
            this.additionalOffset = additionalOffset;
            this.shift = shift;
        }

// public StoreIndexedNode createStoreIndexedNode() {
// return new StoreIndexedNode(array, StoreFlatIndexedNode.this.index, getBoundsCheck(), storeCheck,
// elementKind, values.get(index));
// }
    }

    public static final NodeClass<StoreFlatIndexedNode> TYPE = NodeClass.create(StoreFlatIndexedNode.class);

    @OptionalInput(InputType.Guard) private GuardingNode storeCheck;
    @Input NodeInputList<ValueNode> values = new NodeInputList<>(this);
    @OptionalInput(InputType.State) FrameState stateAfter;

    private List<StoreIndexedWrapper> wrappers = new ArrayList<>();

    public List<StoreIndexedNode> getWriteOperations() {
        return wrappers.stream().map(w -> {
            StoreIndexedNode node = new StoreIndexedNode(array, index, getBoundsCheck(), storeCheck, w.elementKind, values.get(w.index));
            node.setFlatAccess(true);
            node.setAdditionalOffset(w.additionalOffset);
            node.setShift(w.shift);
            return node;
        }).toList();
    }

// public void addStoreIndexed(int index, JavaKind elementKind, int additionalOffset, int shift) {
// wrappers.add(new StoreIndexedWrapper(index, elementKind, additionalOffset, shift));
// }

    public List<ValueNode> getValues() {
        return values;
    }

    public void addValues(List<ValueNode> newValues) {
        values.addAll(newValues);
    }

// public void addValue(ValueNode value) {
// values.add(value);
// }

    // private final StoreIndexedNode[] values;

    public GuardingNode getStoreCheck() {
        return storeCheck;
    }

    @Override
    public FrameState stateAfter() {
        return stateAfter;
    }

    @Override
    public void setStateAfter(FrameState x) {
        assert x == null || x.isAlive() : "frame state must be in a graph";
        updateUsages(stateAfter, x);
        stateAfter = x;
    }

    @Override
    public LocationIdentity getKilledLocationIdentity() {
        return getLocationIdentity();
    }

    @Override
    public boolean hasSideEffect() {
        return true;
    }

// public StoreIndexedNode[] getWriteOperations() {
// return values;
// }

    public StoreFlatIndexedNode(ValueNode array, ValueNode index, GuardingNode boundsCheck, GuardingNode storeCheck, JavaKind elementKind,
                    List<StoreFlatIndexedNode.StoreIndexedWrapper> writeOperations) {
        super(TYPE, StampFactory.forVoid(), array, index, boundsCheck, elementKind);
        this.storeCheck = storeCheck;
        this.wrappers.addAll(writeOperations);
    }

    public LocationIdentity getKilledLocation() {
        return getLocationIdentity();
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
// ValueNode alias = tool.getAlias(array());
// if (alias instanceof VirtualObjectNode) {
// ValueNode indexValue = tool.getAlias(index());
// int idx = indexValue.isConstant() ? indexValue.asJavaConstant().asInt() : -1;
// VirtualArrayNode virtual = (VirtualArrayNode) alias;
// if (idx >= 0 && idx < virtual.entryCount()) {
// ResolvedJavaType componentType = virtual.type().getComponentType();
// if (elementKind.isPrimitive() || StampTool.isPointerAlwaysNull(value) ||
// componentType.isJavaLangObject() ||
// (StampTool.typeReferenceOrNull(value) != null &&
// componentType.isAssignableFrom(StampTool.typeOrNull(value)))) {
// boolean success = tool.setVirtualEntry(virtual, idx, value(), elementKind(), 0);
// if (success) {
// tool.delete();
// } else {
// GraalError.guarantee(virtual.isVirtualByteArray(tool.getMetaAccessExtensionProvider()), "only
// stores to virtual byte arrays can fail: %s", virtual);
// }
// }
// }
// }
    }

    public FrameState getState() {
        return stateAfter;
    }

    @Override
    public Node canonical(CanonicalizerTool tool) {
        if (array().isNullConstant()) {
            return new DeoptimizeNode(DeoptimizationAction.InvalidateReprofile, DeoptimizationReason.NullCheckException);
        }
        return this;
    }
}
