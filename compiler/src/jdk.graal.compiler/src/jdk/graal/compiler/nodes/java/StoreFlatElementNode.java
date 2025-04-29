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

import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.graph.NodeInputList;
import jdk.graal.compiler.nodeinfo.InputType;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.DeoptimizeNode;
import jdk.graal.compiler.nodes.FrameState;
import jdk.graal.compiler.nodes.NamedLocationIdentity;
import jdk.graal.compiler.nodes.StateSplit;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.extended.GuardingNode;
import jdk.graal.compiler.nodes.memory.MultiWrite;
import jdk.graal.compiler.nodes.spi.Canonicalizable;
import jdk.graal.compiler.nodes.spi.CanonicalizerTool;
import jdk.graal.compiler.nodes.spi.Lowerable;
import jdk.graal.compiler.nodes.spi.Virtualizable;
import jdk.graal.compiler.nodes.spi.VirtualizerTool;
import jdk.graal.compiler.nodes.virtual.VirtualObjectNode;
import jdk.vm.ci.meta.DeoptimizationAction;
import jdk.vm.ci.meta.DeoptimizationReason;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaField;

/**
 * The {@code StoreFlatElementNode} performs a (maybe not atomic) store operation for a flat array
 * element.
 */
@NodeInfo(nameTemplate = "StoreFlatElement", cycles = CYCLES_8, size = SIZE_8)
public final class StoreFlatElementNode extends AccessArrayNode implements StateSplit, Lowerable, Virtualizable, Canonicalizable, MultiWrite {

    public static final NodeClass<StoreFlatElementNode> TYPE = NodeClass.create(StoreFlatElementNode.class);
    @Input ValueNode index;
    @OptionalInput(InputType.Guard) private GuardingNode boundsCheck;
    private final JavaKind elementKind;
    private LocationIdentity location;

    @OptionalInput(InputType.Guard) private GuardingNode storeCheck;
    @OptionalInput(InputType.State) FrameState stateAfter;
    @Input NodeInputList<ValueNode> values = new NodeInputList<>(this);
    private LocationIdentity[] killedLocations;
    private final List<SingleWriteOperation> singleWriteOperations = new ArrayList<>();

    public ValueNode index() {
        return index;
    }

    /**
     * Create an new StoreFlatElementNode.
     *
     * @param stamp the result kind of the access
     * @param array the instruction producing the array
     * @param index the instruction producing the index
     * @param boundsCheck the explicit array bounds check already performed before the access, or
     *            null if no check was performed yet
     */
    private StoreFlatElementNode(NodeClass<? extends StoreFlatElementNode> c, Stamp stamp, ValueNode array, ValueNode index, GuardingNode boundsCheck) {
        super(c, stamp, array);
        this.index = index;
        this.boundsCheck = boundsCheck;
        this.elementKind = JavaKind.Object;
    }

    public StoreFlatElementNode(ValueNode array, ValueNode index, GuardingNode boundsCheck, GuardingNode storeCheck,
                    List<SingleWriteOperation> writeOperations) {
        this(TYPE, StampFactory.forVoid(), array, index, boundsCheck);
        this.location = LocationIdentity.any();
        this.storeCheck = storeCheck;
        this.singleWriteOperations.addAll(writeOperations);
        this.killedLocations = singleWriteOperations.stream().map(info -> NamedLocationIdentity.getFlatArrayLocation(info.getField())).toArray(LocationIdentity[]::new);

    }

    public GuardingNode getBoundsCheck() {
        return boundsCheck;
    }

    /**
     * Gets the element type of the array.
     *
     * @return the element type
     */
    public JavaKind elementKind() {
        return elementKind;
    }

    @Override
    public LocationIdentity getLocationIdentity() {
        return location;
    }

    public static class SingleWriteOperation {

        private final ResolvedJavaField field;
        private final int offset;
        private final int shift;

        public SingleWriteOperation(ResolvedJavaField field, int shift) {
            this.field = field;
            this.offset = field.getOffset();
            this.shift = shift;
        }

        public ResolvedJavaField getField() {
            return field;
        }

        public int getOffset() {
            return offset;
        }

        public int getShift() {
            return shift;
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
    public LocationIdentity[] getKilledLocationIdentities() {
        return killedLocations;
    }

    @Override
    public boolean hasSideEffect() {
        return true;
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
        ValueNode alias = tool.getAlias(array());
        if (alias instanceof VirtualObjectNode) {
            // TODO: flat arrays can't be virtual, but should be in the future
            throw new GraalError("flat arrays shouldn't be virtual yet");
        }

    }

    public FrameState getState() {
        return stateAfter;
    }

    @Override
    public boolean verifyNode() {
        assertTrue(!values.isEmpty(), "must have at least one value to write");
        return super.verifyNode();
    }

    @Override
    public Node canonical(CanonicalizerTool tool) {
        if (array().isNullConstant()) {
            return new DeoptimizeNode(DeoptimizationAction.InvalidateReprofile, DeoptimizationReason.NullCheckException);
        }
        return this;
    }
}
