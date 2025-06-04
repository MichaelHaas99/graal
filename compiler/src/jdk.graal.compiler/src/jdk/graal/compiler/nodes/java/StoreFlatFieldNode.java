/*
 * Copyright (c) 2009, 2022, Oracle and/or its affiliates. All rights reserved.
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
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_2;
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_8;

import java.util.ArrayList;
import java.util.List;

import org.graalvm.word.LocationIdentity;

import jdk.graal.compiler.core.common.memory.MemoryOrderMode;
import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.graph.NodeInputList;
import jdk.graal.compiler.nodeinfo.InputType;
import jdk.graal.compiler.nodeinfo.NodeCycles;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodeinfo.NodeSize;
import jdk.graal.compiler.nodeinfo.Verbosity;
import jdk.graal.compiler.nodes.DeoptimizeNode;
import jdk.graal.compiler.nodes.FieldLocationIdentity;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.FrameState;
import jdk.graal.compiler.nodes.StateSplit;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.memory.MultiWrite;
import jdk.graal.compiler.nodes.memory.OrderedMemoryAccess;
import jdk.graal.compiler.nodes.spi.Canonicalizable;
import jdk.graal.compiler.nodes.spi.CanonicalizerTool;
import jdk.graal.compiler.nodes.spi.Lowerable;
import jdk.graal.compiler.nodes.spi.Virtualizable;
import jdk.graal.compiler.nodes.spi.VirtualizerTool;
import jdk.graal.compiler.nodes.virtual.VirtualInstanceNode;
import jdk.graal.compiler.nodes.virtual.VirtualObjectNode;
import jdk.vm.ci.meta.DeoptimizationAction;
import jdk.vm.ci.meta.DeoptimizationReason;
import jdk.vm.ci.meta.ResolvedJavaField;

/**
 * The {@code StoreFlatFieldNode} performs a (maybe not atomic) store operation for a flat instance
 * field.
 */
@NodeInfo(nameTemplate = "StoreFlatField", cycles = CYCLES_8, size = SIZE_8)
public final class StoreFlatFieldNode extends FixedWithNextNode implements StateSplit, Virtualizable, Canonicalizable, MultiWrite, Lowerable, OrderedMemoryAccess {
    public static final NodeClass<StoreFlatFieldNode> TYPE = NodeClass.create(StoreFlatFieldNode.class);
    @OptionalInput ValueNode object;
    private final LocationIdentity location;
    private final ResolvedJavaField field;
    private final MemoryOrderMode memoryOrder;

    public ValueNode object() {
        return object;
    }

    @Input NodeInputList<ValueNode> values = new NodeInputList<>(this);
    @OptionalInput(InputType.State) FrameState stateAfter;

    private final List<SingleWriteOperation> singleWriteOperations = new ArrayList<>();
    private LocationIdentity[] killedLocations;

    public void setObject(ValueNode otherObject) {
        updateUsages(object, otherObject);
        this.object = otherObject;
    }

    /**
     * Constructs a new store flat field node.
     *
     * @param object the instruction producing the receiver object
     * @param field the compiler interface representation of the field
     * @param memoryOrder specifies the memory ordering requirements of the access. This overrides
     *            the field volatile modifier.
     */
    private StoreFlatFieldNode(Stamp stamp, ValueNode object, ResolvedJavaField field, MemoryOrderMode memoryOrder, boolean immutable) {
        super(TYPE, stamp);
        assert !immutable || field.isFinal() : "immutable fields must also be final";
        assert !immutable || !field.isStatic() : "immutable fields must also be non-static";
        this.object = object;
        this.field = field;
        this.memoryOrder = memoryOrder;
        this.location = LocationIdentity.any();
    }

    public StoreFlatFieldNode(ValueNode object, ResolvedJavaField field, List<SingleWriteOperation> writeOperations) {
        this(StampFactory.forVoid(), object, field, MemoryOrderMode.getMemoryOrder(field), false);
        this.singleWriteOperations.addAll(writeOperations);
        if (ordersMemoryAccesses()) {
            this.killedLocations = new LocationIdentity[]{LocationIdentity.any()};
        } else {
            this.killedLocations = singleWriteOperations.stream().map(info -> new FieldLocationIdentity(info.field, false)).toArray(LocationIdentity[]::new);
        }
    }

    @Override
    public LocationIdentity getLocationIdentity() {
        return location;
    }

    /**
     * Gets the compiler interface field for this field access.
     *
     * @return the compiler interface field for this field access
     */
    public ResolvedJavaField field() {
        return field;
    }

    /**
     * Checks whether this field access is an access to a static field.
     *
     * @return {@code true} if this field access is to a static field
     */
    public boolean isStatic() {
        return field.isStatic();
    }

    /**
     * Note the field access semantics are coupled to the access and not to the field. e.g. it's
     * possible to access volatile fields using non-volatile semantics via VarHandles.
     */
    @Override
    public MemoryOrderMode getMemoryOrder() {
        return memoryOrder;
    }

    @Override
    public String toString(Verbosity verbosity) {
        if (verbosity == Verbosity.Name && field != null) {
            return super.toString(verbosity) + "#" + field.getName();
        } else {
            return super.toString(verbosity);
        }
    }

    @Override
    public boolean verifyNode() {
        assertTrue((object == null) == isStatic(), "static field must not have object, instance field must have object");
        assertTrue(!values.isEmpty(), "must have at least one value to write");
        return super.verifyNode();
    }

    @Override
    protected NodeSize dynamicNodeSizeEstimate() {
        if (ordersMemoryAccesses()) {
            return SIZE_2;
        }
        return super.dynamicNodeSizeEstimate();
    }

    public static class SingleWriteOperation {
        private final ResolvedJavaField field;

        public SingleWriteOperation(ResolvedJavaField field) {
            this.field = field;
        }

        public ResolvedJavaField getField() {
            return field;
        }
    }

    public List<SingleWriteOperation> getSingleWriteOperations() {
        return singleWriteOperations;
    }

    public List<ValueNode> getValues() {
        return values;
    }

    public void addValues(List<ValueNode> newValues) {
        values.addAll(newValues);
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
    public boolean hasSideEffect() {
        return true;
    }

    public LocationIdentity getKilledLocation(int index) {
        assert index >= 0 && index < singleWriteOperations.size() : "wrong index";
        if (killedLocations.length == 1) {
            return killedLocations[0];
        }
        return killedLocations[index];
    }

    @Override
    public LocationIdentity[] getKilledLocationIdentities() {
        return killedLocations;
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
        ValueNode alias = tool.getAlias(object());
        if (alias instanceof VirtualObjectNode) {
            for (int i = 0; i < singleWriteOperations.size(); i++) {
                VirtualInstanceNode virtual = (VirtualInstanceNode) alias;
                int fieldIndex = virtual.fieldIndex(singleWriteOperations.get(i).field);
                if (fieldIndex != -1) {
                    tool.setVirtualEntry(virtual, fieldIndex, tool.getAlias(values.get(i)));
                } else {
                    return;
                }
            }
            tool.delete();

        }
    }

    public FrameState getState() {
        return stateAfter;
    }

    @Override
    public NodeCycles estimatedNodeCycles() {
        if (ordersMemoryAccesses()) {
            return CYCLES_8;
        }
        return super.estimatedNodeCycles();
    }

    @Override
    public Node canonical(CanonicalizerTool tool) {
        if (!field.isStatic() && object.isNullConstant()) {
            return new DeoptimizeNode(DeoptimizationAction.InvalidateReprofile, DeoptimizationReason.NullCheckException);
        }
        return this;
    }
}
