/*
 * Copyright (c) 2011, 2024, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.nodes.calc;

import jdk.graal.compiler.core.common.calc.CanonicalCondition;
import jdk.graal.compiler.core.common.type.AbstractObjectStamp;
import jdk.graal.compiler.core.common.type.AbstractPointerStamp;
import jdk.graal.compiler.core.common.type.ObjectStamp;
import jdk.graal.compiler.core.common.type.TypeReference;
import jdk.graal.compiler.debug.Assertions;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.LogicConstantNode;
import jdk.graal.compiler.nodes.LogicNode;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.ProfileData;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.extended.BoxNode;
import jdk.graal.compiler.nodes.extended.FixedInlineTypeEqualityAnchorNode;
import jdk.graal.compiler.nodes.extended.GetClassNode;
import jdk.graal.compiler.nodes.java.AbstractNewObjectNode;
import jdk.graal.compiler.nodes.java.InstanceOfNode;
import jdk.graal.compiler.nodes.spi.CanonicalizerTool;
import jdk.graal.compiler.nodes.spi.Lowerable;
import jdk.graal.compiler.nodes.spi.Virtualizable;
import jdk.graal.compiler.nodes.spi.VirtualizerTool;
import jdk.graal.compiler.nodes.type.StampTool;
import jdk.graal.compiler.nodes.virtual.AllocatedObjectNode;
import jdk.graal.compiler.nodes.virtual.VirtualBoxingNode;
import jdk.graal.compiler.nodes.virtual.VirtualObjectNode;
import jdk.graal.compiler.options.OptionValues;
import jdk.vm.ci.hotspot.ACmpDataAccessor;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.ConstantReflectionProvider;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaType;

@NodeInfo(shortName = "==")
public final class ObjectEqualsNode extends PointerEqualsNode implements Virtualizable, Lowerable {

    public static final NodeClass<ObjectEqualsNode> TYPE = NodeClass.create(ObjectEqualsNode.class);
    private static final ObjectEqualsOp OP = new ObjectEqualsOp();

    private final boolean substituabilityCheck;

    public boolean substituabilityCheck() {
        return substituabilityCheck;
    }

    private ACmpDataAccessor profile;

    public ACmpDataAccessor getProfile() {
        return profile;
    }

    public void setProfile(ACmpDataAccessor profile) {
        this.profile = profile;
    }

    public ObjectEqualsNode(ValueNode x, ValueNode y) {
        super(TYPE, x, y);
        assert x.stamp(NodeView.DEFAULT) instanceof AbstractObjectStamp && y.stamp(NodeView.DEFAULT) instanceof AbstractObjectStamp : Assertions.errorMessageContext("x", x, "y", y);
        substituabilityCheck = x.stamp(NodeView.DEFAULT).canBeInlineType() && y.stamp(NodeView.DEFAULT).canBeInlineType();
    }

    public static LogicNode create(ValueNode x, ValueNode y, ConstantReflectionProvider constantReflection, NodeView view) {
        LogicNode result = tryConstantFold(CanonicalCondition.EQ, x, y, constantReflection, false);
        if (result != null) {
            return result;
        } else {
            result = findSynonym(x, y, view);
            if (result != null) {
                return result;
            }
            return new ObjectEqualsNode(x, y);
        }
    }

    public static LogicNode create(ConstantReflectionProvider constantReflection, MetaAccessProvider metaAccess, OptionValues options, ValueNode x, ValueNode y, NodeView view) {
        LogicNode result = OP.canonical(constantReflection, metaAccess, options, null, CanonicalCondition.EQ, false, x, y, view);
        if (result != null) {
            return result;
        }
        return create(x, y, constantReflection, view);
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool, ValueNode forX, ValueNode forY) {

        ValueNode updatedX = null;
        if (forX instanceof FixedInlineTypeEqualityAnchorNode xAnchorNode && !xAnchorNode.stamp(NodeView.DEFAULT).canBeInlineType()) {
            updatedX = xAnchorNode.object();
        }

        ValueNode updatedY = null;
        if (forY instanceof FixedInlineTypeEqualityAnchorNode yAnchorNode && !yAnchorNode.stamp(NodeView.DEFAULT).canBeInlineType()) {
            updatedY = yAnchorNode.object();
        }

        if (updatedX != null || updatedY != null) {
            return create(tool.getConstantReflection(), tool.getMetaAccess(),
                            tool.getOptions(), updatedX == null ? forX : updatedX, updatedY == null ? forY : updatedY, NodeView.DEFAULT);
        }

        NodeView view = NodeView.from(tool);

        ValueNode value = OP.canonical(tool.getConstantReflection(), tool.getMetaAccess(), tool.getOptions(), tool.smallestCompareWidth(), CanonicalCondition.EQ, false, forX, forY, view);
        if (value != null) {
            return value;
        }
        return this;
    }

    public static class ObjectEqualsOp extends PointerEqualsOp {

        @Override
        protected LogicNode canonicalizeSymmetricConstant(ConstantReflectionProvider constantReflection, MetaAccessProvider metaAccess, OptionValues options, Integer smallestCompareWidth,
                        CanonicalCondition condition, Constant constant, ValueNode nonConstant, boolean mirrored, boolean unorderedIsTrue, NodeView view) {
            ResolvedJavaType type = constantReflection.asJavaType(constant);
            if (type != null && nonConstant instanceof GetClassNode getClassNode) {
                ValueNode object = getClassNode.getObject();
                assert ((ObjectStamp) object.stamp(view)).nonNull() : "getClassNode %s object %s should have a non-null stamp, got: %s".formatted(getClassNode, object, object.stamp(view));
                if (!type.isPrimitive() && (type.isConcrete() || type.isArray())) {
                    return InstanceOfNode.create(TypeReference.createExactTrusted(type), object);
                }
                return LogicConstantNode.forBoolean(false);
            }
            if (nonConstant instanceof AbstractNewObjectNode || nonConstant instanceof AllocatedObjectNode) {
                // guard against class hierarchy changes
                assert !(nonConstant instanceof BoxNode) : Assertions.errorMessageContext("nonConstant", nonConstant);
                // a constant can never be equals to a new object
                return LogicConstantNode.forBoolean(false);
            }
            return super.canonicalizeSymmetricConstant(constantReflection, metaAccess, options, smallestCompareWidth, condition, constant, nonConstant, mirrored, unorderedIsTrue, view);
        }

        @Override
        protected LogicNode duplicateModified(ValueNode newX, ValueNode newY, boolean unorderedIsTrue, NodeView view) {
            if (newX.stamp(view) instanceof ObjectStamp && newY.stamp(view) instanceof ObjectStamp) {
                return ObjectEqualsNode.create(newX, newY, view);
            } else if (newX.stamp(view) instanceof AbstractPointerStamp && newY.stamp(view) instanceof AbstractPointerStamp) {
                return PointerEqualsNode.create(newX, newY, view);
            }
            throw GraalError.shouldNotReachHereUnexpectedValue(newX.stamp(view) + " " + newY.stamp(view)); // ExcludeFromJacocoGeneratedReport
        }
    }

    private static LogicNode virtualizeNonVirtualComparison(VirtualObjectNode virtual, ValueNode other, StructuredGraph graph, VirtualizerTool tool) {
        if (virtual instanceof VirtualBoxingNode virtualBoxingNode && other.isConstant()) {
            if (virtualBoxingNode.getBoxingKind() == JavaKind.Boolean) {
                JavaConstant otherUnboxed = tool.getConstantReflection().unboxPrimitive(other.asJavaConstant());
                if (otherUnboxed != null && otherUnboxed.getJavaKind() == JavaKind.Boolean) {
                    int expectedValue = otherUnboxed.asBoolean() ? 1 : 0;
                    return new IntegerEqualsNode(virtualBoxingNode.getBoxedValue(tool), ConstantNode.forInt(expectedValue, graph));
                } else {
                    return LogicConstantNode.contradiction(graph);
                }
            }

        }

        // TODO: return isNotNull info with check for null case
        if (virtual.hasIdentity() || JavaConstant.isNull(other.asConstant()) && StampTool.isPointerNonNull(virtual)) {
            // one of them is virtual and has identity or the other is null: they can never be the
            // same
            // objects
            return LogicConstantNode.contradiction(graph);
        }
        return null;
    }

    public static LogicNode virtualizeComparison(ValueNode x, ValueNode y, StructuredGraph graph, VirtualizerTool tool) {
        ValueNode xAlias = tool.getAlias(x);
        ValueNode yAlias = tool.getAlias(y);

        VirtualObjectNode xVirtual = xAlias instanceof VirtualObjectNode ? (VirtualObjectNode) xAlias : null;
        VirtualObjectNode yVirtual = yAlias instanceof VirtualObjectNode ? (VirtualObjectNode) yAlias : null;

        if (xVirtual != null && yVirtual == null) {
            return virtualizeNonVirtualComparison(xVirtual, yAlias, graph, tool);
        } else if (xVirtual == null && yVirtual != null) {
            return virtualizeNonVirtualComparison(yVirtual, xAlias, graph, tool);
        } else if (xVirtual != null && yVirtual != null) {
            if (xVirtual.hasIdentity() ^ yVirtual.hasIdentity()) {
                /*
                 * One of the two objects has identity, the other doesn't. In code, this looks like
                 * "Integer.valueOf(a) == new Integer(b)", which is always false.
                 *
                 * In other words: an object created via valueOf can never be equal to one created
                 * by new in the same compilation unit.
                 */
                return LogicConstantNode.contradiction(graph);
            } else if (!xVirtual.hasIdentity() && !yVirtual.hasIdentity()) {
                ResolvedJavaType type = xVirtual.type();
                if (type.equals(yVirtual.type())) {
                    MetaAccessProvider metaAccess = tool.getMetaAccess();

                    // node indicating the result of field comparison
                    LogicNode fieldComparison = LogicConstantNode.tautology(graph);

                    // node indicating the result of isNotNull comparison
                    LogicNode isNotNullComparison = LogicConstantNode.tautology();

                    // make sure nullable scalarized inline objects are handled correctly
                    ValueNode xIsNotNull = tool.getIsNotNull(xVirtual);
                    ValueNode yIsNotNull = tool.getIsNotNull(yVirtual);

                    LogicNode orWithFieldComparison = LogicConstantNode.contradiction();
                    if (!StampTool.isPointerNonNull(xVirtual) && !StampTool.isPointerNonNull(yVirtual)) {
                        assert xIsNotNull != null && yIsNotNull != null : "nullable scalarized object expected isNotNull information to be set";
                        isNotNullComparison = new IntegerEqualsNode(xIsNotNull, yIsNotNull);

                        // needs to make field comparison true, in case both are null
                        orWithFieldComparison = new IntegerEqualsNode(xIsNotNull, ConstantNode.forInt(0));
                    } else if (StampTool.isPointerNonNull(xVirtual) && StampTool.isPointerNonNull(yVirtual)) {
                        // nothing to do both are non-null
                    } else if (!StampTool.isPointerNonNull(xVirtual)) {
                        // x may be null, y is not therefore x needs to be non-null
                        assert xIsNotNull != null : "nullable scalarized object expected isNotNull information to be set";
                        isNotNullComparison = new IntegerEqualsNode(xIsNotNull, ConstantNode.forInt(1));
                    } else {
                        // y may be null, x is not therefore y needs to be non-null
                        assert yIsNotNull != null : "nullable scalarized object expected isNotNull information to be set";
                        isNotNullComparison = new IntegerEqualsNode(yIsNotNull, ConstantNode.forInt(1));
                    }

                    if (type.equals(metaAccess.lookupJavaType(Integer.class)) || type.equals(metaAccess.lookupJavaType(Long.class))) {
                        // both are virtual without identity: check contents
                        assert xVirtual.entryCount() == 1 && yVirtual.entryCount() == 1 : Assertions.errorMessageContext("x", xVirtual, "y", yVirtual);
                        assert xVirtual.entryKind(tool.getMetaAccessExtensionProvider(), 0).getStackKind() == JavaKind.Int ||
                                        xVirtual.entryKind(tool.getMetaAccessExtensionProvider(), 0) == JavaKind.Long : Assertions.errorMessage(x, y, xVirtual);
                        fieldComparison = LogicNode.andWithoutGraphAdd(fieldComparison, new IntegerEqualsNode(tool.getEntry(xVirtual, 0), tool.getEntry(yVirtual, 0)),
                                        ProfileData.BranchProbabilityData.unknown());
                        // return LogicNode.andWithoutGraphAdd(isNotNullComparison, new
                        // IntegerEqualsNode(tool.getEntry(xVirtual, 0), tool.getEntry(yVirtual,
                        // 0)), ProfileData.BranchProbabilityData.unknown());
                    } else {
                        boolean comparisonResultUnknown = false;

                        for (int i = 0; i < xVirtual.entryCount(); i++) {
                            ValueNode xFieldNode = tool.getEntry(xVirtual, i);
                            ValueNode yFieldNode = tool.getEntry(yVirtual, i);

                            // if fields reference the same node they are equal
                            if (xFieldNode.equals(yFieldNode))
                                continue;

                            LogicNode result = null;
                            if (xFieldNode instanceof VirtualObjectNode || yFieldNode instanceof VirtualObjectNode) {
                                // one field is virtual do a recursive call
                                result = virtualizeComparison(xFieldNode, yFieldNode, graph, tool);
                            } else {
                                // both fields are non-virtual
                                if (xFieldNode.stamp(NodeView.DEFAULT).isIntegerStamp()) {
                                    result = IntegerEqualsNode.create(tool.getConstantReflection(), tool.getMetaAccess(),
                                                    tool.getOptions(), null, xFieldNode, yFieldNode, NodeView.DEFAULT);
                                } else if (xFieldNode.stamp(NodeView.DEFAULT).isObjectStamp()) {
// ForeignCallNode logx = new ForeignCallNode(LOG_PRIMITIVE,
// ConstantNode.forInt(JavaKind.Long.getTypeChar(), graph), xFieldNode,
// ConstantNode.forBoolean(true,
// graph));
// ForeignCallNode logy = new ForeignCallNode(LOG_PRIMITIVE,
// ConstantNode.forInt(JavaKind.Long.getTypeChar(), graph), yFieldNode,
// ConstantNode.forBoolean(true,
// graph));
// ForeignCallNode logx = new ForeignCallNode(LOG_OBJECT, xFieldNode, ConstantNode.forBoolean(true,
// graph), ConstantNode.forBoolean(true, graph));
// ForeignCallNode logy = new ForeignCallNode(LOG_OBJECT, yFieldNode, ConstantNode.forBoolean(true,
// graph), ConstantNode.forBoolean(true, graph));
// tool.addNode(logx);
// tool.addNode(logy);
                                    result = ObjectEqualsNode.create(tool.getConstantReflection(), tool.getMetaAccess(),
                                                    tool.getOptions(), xFieldNode, yFieldNode, NodeView.DEFAULT);
                                    // result = LogicConstantNode.tautology();
                                } else if (xFieldNode.stamp(NodeView.DEFAULT).isFloatStamp()) {
                                    ValueNode normalizeNode = FloatNormalizeCompareNode.create(xFieldNode, yFieldNode, true, JavaKind.Int,
                                                    tool.getConstantReflection());
                                    ValueNode constantZero = ConstantNode.forConstant(JavaConstant.INT_0, tool.getMetaAccess(), graph);
                                    result = IntegerEqualsNode.create(tool.getConstantReflection(), tool.getMetaAccess(),
                                                    tool.getOptions(), null, normalizeNode, constantZero, NodeView.DEFAULT);
                                }

                            }


                            if (result == null) {
                                // field comparison result not known at compile time, but continue
                                // searching for contradiction
                                comparisonResultUnknown = true;
                                continue;
                            }

                            if (result.isContradiction())
                                return result;

                            fieldComparison = LogicNode.andWithoutGraphAdd(fieldComparison, result,
                                            ProfileData.BranchProbabilityData.unknown());
                        }

                        if (comparisonResultUnknown)
                            return null;

                        // comparison = LogicNode.orWithoutGraphAdd(comparison, orComparison,
                        // ProfileData.BranchProbabilityData.unknown());
                        // return comparison;
                    }
                    return LogicNode.andWithoutGraphAdd(isNotNullComparison, LogicNode.orWithoutGraphAdd(orWithFieldComparison, fieldComparison, ProfileData.BranchProbabilityData.unknown()),
                                    ProfileData.BranchProbabilityData.unknown());
                }
            } else {
                // both are virtual with identity: check if they refer to the same object
                return LogicConstantNode.forBoolean(xVirtual == yVirtual, graph);
            }
        }
        return null;
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
        ValueNode x = getX();
        ValueNode y = getY();

        if (getX() instanceof FixedInlineTypeEqualityAnchorNode xAnchorNode) {
            x = xAnchorNode.object();
        }

        if (getY() instanceof FixedInlineTypeEqualityAnchorNode yAnchorNode) {
            y = yAnchorNode.object();
        }

        ValueNode node = virtualizeComparison(x, y, graph(), tool);
        if (node == null) {

            /*
             * comparison can't be done at compile time, make sure that inputs stay wrapped with an
             * anchor node
             *
             * TODO: maybe not necessary and can be optimized away
             *
             */

            ValueNode xAlias = tool.getAlias(x);
            ValueNode yAlias = tool.getAlias(y);
            if (xAlias instanceof VirtualObjectNode xNode)
                if (!tool.ensureMaterialized(xNode))
                    return;
            if (yAlias instanceof VirtualObjectNode yNode)
                if (!tool.ensureMaterialized(yNode))
                    return;
            xAlias = tool.getAlias(x);
            yAlias = tool.getAlias(y);

            if (getX() instanceof FixedInlineTypeEqualityAnchorNode) {
                ValueNode xAnchor = new FixedInlineTypeEqualityAnchorNode(xAlias);
                tool.ensureAdded(xAnchor);
                tool.replaceFirstInput(getX(), xAnchor);
            }

            if (getY() instanceof FixedInlineTypeEqualityAnchorNode) {
                ValueNode yAnchor = new FixedInlineTypeEqualityAnchorNode(yAlias);
                tool.ensureAdded(yAnchor);
                tool.replaceFirstInput(getY(), yAnchor);
            }
            return;

        }

// ValueNode xAlias = tool.getAlias(x);
// ValueNode yAlias = tool.getAlias(y);
// if (xAlias instanceof VirtualObjectNode xNode && yAlias instanceof VirtualObjectNode yNode) {
// if (tool.getIsNotNull(xNode) != null && tool.getIsNotNull(yNode) != null) {
// node = LogicNode.andWithoutGraphAdd((LogicNode) node, new
// IntegerEqualsNode(tool.getIsNotNull(xNode), tool.getIsNotNull(yNode)),
// ProfileData.BranchProbabilityData.unknown());
// } else if (tool.getIsNotNull(xNode) == null && tool.getIsNotNull(yNode) == null) {
// // nothing to do
// } else {
// return;
// }
// }

        tool.ensureAdded(node);
        tool.replaceWithValue(node);
    }
}
