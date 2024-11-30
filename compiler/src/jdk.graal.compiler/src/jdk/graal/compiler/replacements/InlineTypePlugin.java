package jdk.graal.compiler.replacements;

import static jdk.vm.ci.meta.DeoptimizationAction.None;

import java.util.ArrayList;
import java.util.List;

import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.nodes.BeginNode;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.DeoptimizeNode;
import jdk.graal.compiler.nodes.EndNode;
import jdk.graal.compiler.nodes.FixedNode;
import jdk.graal.compiler.nodes.FrameState;
import jdk.graal.compiler.nodes.IfNode;
import jdk.graal.compiler.nodes.LogicNode;
import jdk.graal.compiler.nodes.MergeNode;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.ProfileData;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.ValuePhiNode;
import jdk.graal.compiler.nodes.calc.IntegerEqualsNode;
import jdk.graal.compiler.nodes.extended.GuardingNode;
import jdk.graal.compiler.nodes.extended.IsFlatArrayNode;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import jdk.graal.compiler.nodes.graphbuilderconf.NodePlugin;
import jdk.graal.compiler.nodes.java.FinalFieldBarrierNode;
import jdk.graal.compiler.nodes.java.LoadFieldNode;
import jdk.graal.compiler.nodes.java.LoadIndexedNode;
import jdk.graal.compiler.nodes.java.NewInstanceNode;
import jdk.graal.compiler.nodes.java.StoreFieldNode;
import jdk.graal.compiler.nodes.java.StoreFlatIndexedNode;
import jdk.graal.compiler.nodes.java.StoreIndexedNode;
import jdk.vm.ci.code.BytecodeFrame;
import jdk.vm.ci.hotspot.HotSpotResolvedObjectType;
import jdk.vm.ci.meta.DeoptimizationReason;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaField;

public class InlineTypePlugin implements NodePlugin {

    @Override
    public boolean handleLoadField(GraphBuilderContext b, ValueNode object, ResolvedJavaField field) {

        if (field.isFlat()) {
            if (!field.isNullFreeInlineType()) {
                // field is flat and nullable

                BeginNode trueBegin = b.getGraph().add(new BeginNode());
                BeginNode falseBegin = b.getGraph().add(new BeginNode());

                genFlatFieldNullCheck(b, object, field, trueBegin, falseBegin);

                // true branch - flat field is null
                EndNode trueEnd = b.add(new EndNode());
                trueBegin.setNext(trueEnd);

                // false branch - flat field is non-null
                NewInstanceNode instance = genGetLoadFlatField(b, object, field);
                EndNode falseEnd = b.add(new EndNode());
                falseBegin.setNext(instance);

                ConstantNode nullPointer = ConstantNode.forConstant(JavaConstant.NULL_POINTER, b.getMetaAccess(), b.getGraph());

                // return a null pointer if the flat field was null or the read instance otherwise
                ValuePhiNode phiNode = b.add(new ValuePhiNode(StampFactory.forDeclaredType(b.getAssumptions(), field.getType(), false).getTrustedStamp(), null,
                                nullPointer, instance));
                b.push(JavaKind.Object, phiNode);

                // merge
                MergeNode merge = b.add(new MergeNode());
                phiNode.setMerge(merge);

                merge.addForwardEnd(trueEnd);
                merge.addForwardEnd(falseEnd);



            } else {
                // field is flat and null-restricted
                b.push(JavaKind.Object, genGetLoadFlatField(b, object, field));
            }
            return true;
        }
        return false;
    }

    private NewInstanceNode genGetLoadFlatField(GraphBuilderContext b, ValueNode object, ResolvedJavaField field) {

        HotSpotResolvedObjectType fieldType = (HotSpotResolvedObjectType) field.getType();

        NewInstanceNode newInstance = b.add(new NewInstanceNode(fieldType, false));
        b.push(JavaKind.Object, newInstance);


        ResolvedJavaField[] innerFields = fieldType.getInstanceFields(true);

        int srcOff = field.getOffset();

        for (int i = 0; i < innerFields.length; i++) {
            ResolvedJavaField innerField = innerFields[i];
            assert !innerField.isFlat() : "the iteration over nested fields is handled by the loop itself";

            // returned fields include a header offset of their holder
            int off = innerField.getOffset() - fieldType.firstFieldOffset();

            // holder has no header so remove the header offset
            LoadFieldNode load = b.add(LoadFieldNode.create(b.getAssumptions(), object, innerField.changeOffset(srcOff + off)));

            // new holder has a header
            StoreFieldNode storeFieldNode = new StoreFieldNode(newInstance, innerField, b.maskSubWordValue(load, innerField.getJavaKind()));
            storeFieldNode.noSideEffect();
            b.add(storeFieldNode);

        }
        b.add(new FinalFieldBarrierNode(newInstance));
        b.pop(JavaKind.Object);
        return newInstance;
    }

    @Override
    public boolean handleStoreField(GraphBuilderContext b, ValueNode object, ResolvedJavaField field, ValueNode value) {
        if (field.isFlat()) {
            // field is flat
            if (!field.isNullFreeInlineType()) {
                // field is flat and nullable

                BeginNode trueBegin = b.getGraph().add(new BeginNode());
                BeginNode falseBegin = b.getGraph().add(new BeginNode());

                // generate if node with condition
                genFlatFieldNullCheck(b, object, field, trueBegin, falseBegin);

                // true branch - flat field is null
                StoreFieldNode storeField = b.add(new StoreFieldNode(object, field.getNullMarkerField(),
                                b.maskSubWordValue(ConstantNode.forConstant(JavaConstant.INT_0, b.getMetaAccess(), b.getGraph()), field.getNullMarkerField().getJavaKind())));
                trueBegin.setNext(storeField);
                EndNode trueEnd = b.add(new EndNode());

                // false branch - flat field is non-null
                b.add(falseBegin);
                storeField = b.add(new StoreFieldNode(object, field.getNullMarkerField(),
                                b.maskSubWordValue(ConstantNode.forConstant(JavaConstant.INT_1, b.getMetaAccess(), b.getGraph()), field.getNullMarkerField().getJavaKind())));
                falseBegin.setNext(storeField);
                genPutFlatField(b, object, field, value);
                EndNode falseEnd = b.add(new EndNode());

                // merge
                MergeNode merge = b.add(new MergeNode());
                merge.addForwardEnd(trueEnd);
                merge.addForwardEnd(falseEnd);

            } else {
                // field is null restricted
                genPutFlatField(b, object, field, value);
            }
            return true;
        }
        return false;
    }

    private void genPutFlatField(GraphBuilderContext b, ValueNode object, ResolvedJavaField field, ValueNode value) {

        HotSpotResolvedObjectType fieldType = (HotSpotResolvedObjectType) field.getType();

        int destOff = field.getOffset();

        ResolvedJavaField[] innerFields = fieldType.getInstanceFields(true);

        for (int i = 0; i < innerFields.length; i++) {
            ResolvedJavaField innerField = innerFields[i];
            assert !innerField.isFlat() : "the iteration over nested fields is handled by the loop itself";

            // returned fields include a header offset of their holder
            int off = innerField.getOffset() - fieldType.firstFieldOffset();

            // holder has a header
            ValueNode load = b.add(LoadFieldNode.create(b.getAssumptions(), value, innerField));

            // holder has no header so remove the header offset
            StoreFieldNode storeFieldNode = b.add(new StoreFieldNode(object, innerField.changeOffset(destOff + off), b.maskSubWordValue(load, innerField.getJavaKind())));
            if (i != innerFields.length - 1) {
                // only last store should have a valid framestate
                storeFieldNode.setStateAfter(b.add(new FrameState(BytecodeFrame.INVALID_FRAMESTATE_BCI)));
            }

        }
    }

    private void genFlatFieldNullCheck(GraphBuilderContext b, ValueNode object, ResolvedJavaField field, BeginNode trueBegin, BeginNode falseBegin) {
        LoadFieldNode y = b.add(LoadFieldNode.create(b.getAssumptions(), object, field.getNullMarkerField()));
        ConstantNode x = ConstantNode.forConstant(JavaConstant.INT_0, b.getMetaAccess(), b.getGraph());

        LogicNode condition = IntegerEqualsNode.create(b.getConstantReflection(), b.getMetaAccess(), b.getOptions(), null, x, y, NodeView.DEFAULT);
        b.add(condition);

        b.add(new IfNode(condition, trueBegin, falseBegin, ProfileData.BranchProbabilityData.unknown()));
    }

    @Override
    public boolean handleLoadIndexed(GraphBuilderContext b, ValueNode array, ValueNode index, GuardingNode boundsCheck, JavaKind elementKind) {
        if (array.stamp(NodeView.DEFAULT).isInlineTypeArray()) {
            // array is known to consist of inline type objects

            HotSpotResolvedObjectType resolvedType = (HotSpotResolvedObjectType) array.stamp(NodeView.DEFAULT).javaType(b.getMetaAccess());
            if (resolvedType.isFlatArray()) {
                // array not known to be flat

                int shift = resolvedType.getLog2ComponentSize();
                b.push(elementKind,
                                genArrayLoadFlatField(b, array, index, boundsCheck, resolvedType, shift));

            } else if (resolvedType.convertToFlatArray().isFlatArray()) {
                // runtime check necessary

                BeginNode trueBegin = b.getGraph().add(new BeginNode());
                BeginNode falseBegin = b.getGraph().add(new BeginNode());
                genFlatArrayCheck(b, array, trueBegin, falseBegin);

                int shift = resolvedType.convertToFlatArray().getLog2ComponentSize();
                // true branch - flat array
                NewInstanceNode instanceFlatArray = genArrayLoadFlatField(b, array, index, boundsCheck, resolvedType,
                                shift);
                trueBegin.setNext(instanceFlatArray);
                EndNode trueEnd = b.add(new EndNode());

                // false branch - no flat array
                ValueNode instanceNonFlatArray = b.add(LoadIndexedNode.create(b.getAssumptions(), array, index, boundsCheck, elementKind, b.getMetaAccess(), b.getConstantReflection()));
                EndNode falseEnd = b.add(new EndNode());
                if (instanceNonFlatArray instanceof FixedNode fixedNode) {
                    falseBegin.setNext(fixedNode);
                } else {
                    falseBegin.setNext(falseEnd);
                }

                ValuePhiNode phiNode = b.add(new ValuePhiNode(StampFactory.forDeclaredType(b.getAssumptions(), resolvedType.getComponentType(), false).getTrustedStamp(), null,
                                instanceFlatArray, instanceNonFlatArray));
                b.push(elementKind, phiNode);
                // merge, wait for frame state creation until phi is on stack
                MergeNode merge = b.add(new MergeNode());
                phiNode.setMerge(merge);
                merge.addForwardEnd(trueEnd);
                merge.addForwardEnd(falseEnd);

            } else {
                b.push(elementKind, b.add(LoadIndexedNode.create(b.getAssumptions(), array, index, boundsCheck, elementKind, b.getMetaAccess(), b.getConstantReflection())));
            }
            return true;

        } else if (array.stamp(NodeView.DEFAULT).canBeInlineTypeArray()) {
            /*
             * TODO: array is maybe flat, go back to interpreter for the moment, will be replaced
             * with a runtime call
             */
            b.add(new DeoptimizeNode(None, DeoptimizationReason.TransferToInterpreter));
            return true;
        }
        return false;
    }

    private NewInstanceNode genArrayLoadFlatField(GraphBuilderContext b, ValueNode array, ValueNode index, GuardingNode boundsCheck, HotSpotResolvedObjectType resolvedType, int shift) {
        HotSpotResolvedObjectType componentType = (HotSpotResolvedObjectType) resolvedType.getComponentType();
        NewInstanceNode newInstance = b.add(new NewInstanceNode(componentType, false));
        b.push(JavaKind.Object, newInstance);

        ResolvedJavaField[] innerFields = componentType.getInstanceFields(true);

        for (int i = 0; i < innerFields.length; i++) {
            ResolvedJavaField innerField = innerFields[i];
            assert !innerField.isFlat() : "the iteration over nested fields is handled by the loop itself";

            // returned fields include a header offset of their holder
            int off = innerField.getOffset() - componentType.firstFieldOffset();

            ValueNode load = b.add(LoadIndexedNode.create(b.getAssumptions(), array, index, boundsCheck, innerField.getJavaKind(), b.getMetaAccess(), b.getConstantReflection()));
            if (load instanceof LoadIndexedNode loadIndexed) {
                // holder has no header so remove the header offset
                loadIndexed.setAdditionalOffset(off);
                loadIndexed.setShift(shift);
            }

            // new holder has a header
            StoreFieldNode storeFieldNode = new StoreFieldNode(newInstance, innerField, b.maskSubWordValue(load, innerField.getJavaKind()));
            storeFieldNode.noSideEffect();
            b.add(storeFieldNode);

        }
        b.add(new FinalFieldBarrierNode(newInstance));
        b.pop(JavaKind.Object);
        return newInstance;
    }

    public boolean handleStoreIndexed(GraphBuilderContext b, ValueNode array, ValueNode index, GuardingNode boundsCheck, GuardingNode storeCheck, JavaKind elementKind, ValueNode value) {
        if (array.stamp(NodeView.DEFAULT).isInlineTypeArray()) {
            // array is known to consist of inline type objects

            HotSpotResolvedObjectType resolvedType = (HotSpotResolvedObjectType) array.stamp(NodeView.DEFAULT).javaType(b.getMetaAccess());
            if (resolvedType.isFlatArray()) {
                // array known to be flat

                int shift = resolvedType.getLog2ComponentSize();
                genArrayStoreFlatField(b, array, index, boundsCheck, storeCheck, resolvedType,
                                value, shift);

            } else if (resolvedType.convertToFlatArray().isFlatArray()) {
                // runtime check necessary

                BeginNode trueBegin = b.getGraph().add(new BeginNode());
                BeginNode falseBegin = b.getGraph().add(new BeginNode());
                genFlatArrayCheck(b, array, trueBegin, falseBegin);

                int shift = resolvedType.convertToFlatArray().getLog2ComponentSize();

                // true branch - flat array
                ValueNode firstFixedNode = genArrayStoreFlatField(b, array, index, boundsCheck, storeCheck, resolvedType,
                                value, shift);
                EndNode trueEnd = b.add(new EndNode());
                if (firstFixedNode instanceof FixedNode fixedNode) {
                    trueBegin.setNext(fixedNode);
                } else {
                    trueBegin.setNext(trueEnd);
                }

                // false branch - no flat array
                StoreIndexedNode storeIndexed = b.add(new StoreIndexedNode(array, index, boundsCheck, storeCheck, elementKind, b.maskSubWordValue(value, elementKind)));
                falseBegin.setNext(storeIndexed);
                EndNode falseEnd = b.add(new EndNode());

                // merge
                MergeNode merge = b.add(new MergeNode());
                merge.addForwardEnd(trueEnd);
                merge.addForwardEnd(falseEnd);

            } else {
                b.push(elementKind, b.add(LoadIndexedNode.create(b.getAssumptions(), array, index, boundsCheck, elementKind, b.getMetaAccess(), b.getConstantReflection())));
            }
            return true;

        } else if (array.stamp(NodeView.DEFAULT).canBeInlineTypeArray()) {
            /*
             * TODO: array is maybe flat, go back to interpreter for the moment, will be replaced
             * with a runtime call
             */
            b.add(new DeoptimizeNode(None, DeoptimizationReason.TransferToInterpreter));
            return true;
        }
        return false;
    }

    private ValueNode genArrayStoreFlatField(GraphBuilderContext b, ValueNode array, ValueNode index, GuardingNode boundsCheck, GuardingNode storeCheck, HotSpotResolvedObjectType resolvedType,
                    ValueNode value, int shift) {
        HotSpotResolvedObjectType elementType = (HotSpotResolvedObjectType) resolvedType.getComponentType();
        ResolvedJavaField[] innerFields = elementType.getInstanceFields(true);

        // ValueNode returnValue = null;

        List<ValueNode> readOperations = new ArrayList<>();
        List<StoreFlatIndexedNode.StoreIndexedWrapper> writeOperations = new ArrayList<>();

        // empty inline type will have no fields
        ValueNode returnValue = null;

        for (int i = 0; i < innerFields.length; i++) {
            ResolvedJavaField innerField = innerFields[i];
            assert !innerField.isFlat() : "the iteration over nested fields is handled by the loop itself";

            // returned fields include a header offset of their holder
            int off = innerField.getOffset() - elementType.firstFieldOffset();

            ValueNode load = b.add(LoadFieldNode.create(b.getAssumptions(), value, innerField));
            readOperations.add(b.maskSubWordValue(load, innerField.getJavaKind()));

            // flatIndexedNode.addValue(load);
            if (i == 0)
                returnValue = load;

            // new holder has a header
            // StoreIndexedNode node = b.add(new StoreIndexedNode(array, index, boundsCheck,
            // storeCheck, innerField.getJavaKind(), load));
            StoreIndexedNode node = new StoreIndexedNode(array, index, boundsCheck, storeCheck, innerField.getJavaKind(), load);
            node.setAdditionalOffset(off);
            node.setShift(shift);
            // flatIndexedNode.
            writeOperations.add(new StoreFlatIndexedNode.StoreIndexedWrapper(i, innerField.getJavaKind(), off, shift));
            // flatIndexedNode.addStoreIndexed(i, innerField.getJavaKind(), off, shift);
            if (i == 0 && returnValue == null) {
                returnValue = node;
            }
            if (i != innerFields.length - 1) {
                // only last store should have a valid frame state
                // node.setStateAfter(b.add(new FrameState(BytecodeFrame.INVALID_FRAMESTATE_BCI)));
            }
            // writeOperations[i] = node;

        }
        StoreFlatIndexedNode flatIndexedNode = new StoreFlatIndexedNode(array, index, boundsCheck, storeCheck, elementType.getJavaKind(), writeOperations);
        flatIndexedNode = b.add(flatIndexedNode);
        flatIndexedNode.addValues(readOperations);

        if (returnValue == null)
            returnValue = flatIndexedNode;
        return returnValue;
    }

    private void genFlatArrayCheck(GraphBuilderContext b, ValueNode array, BeginNode trueBegin, BeginNode falseBegin) {
        IsFlatArrayNode isFlatArrayNode = b.add(new IsFlatArrayNode(array));
        LogicNode condition = b.add(new IntegerEqualsNode(isFlatArrayNode, ConstantNode.forConstant(JavaConstant.INT_1, b.getMetaAccess(), b.getGraph())));

        // TODO: insert profiling data
        b.add(new IfNode(condition, trueBegin, falseBegin, ProfileData.BranchProbabilityData.unknown()));
    }
}
