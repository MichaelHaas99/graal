package jdk.graal.compiler.replacements;

import static jdk.graal.compiler.core.common.spi.ForeignCallDescriptor.CallSideEffect.HAS_SIDE_EFFECT;
import static jdk.graal.compiler.core.common.spi.ForeignCallDescriptor.CallSideEffect.NO_SIDE_EFFECT;
import static jdk.graal.compiler.hotspot.meta.HotSpotForeignCallDescriptor.Transition.LEAF;
import static jdk.graal.compiler.hotspot.meta.HotSpotForeignCallDescriptor.Transition.SAFEPOINT;
import static jdk.graal.compiler.nodes.NamedLocationIdentity.OBJECT_ARRAY_LOCATION;
import static jdk.graal.compiler.replacements.DefaultJavaLoweringProvider.POSITIVE_ARRAY_INDEX_STAMP;
import static jdk.vm.ci.meta.DeoptimizationAction.InvalidateReprofile;

import java.util.ArrayList;
import java.util.List;

import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.core.common.type.TypeReference;
import jdk.graal.compiler.hotspot.meta.HotSpotForeignCallDescriptor;
import jdk.graal.compiler.nodes.BeginNode;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.EndNode;
import jdk.graal.compiler.nodes.FixedGuardNode;
import jdk.graal.compiler.nodes.FixedNode;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.IfNode;
import jdk.graal.compiler.nodes.LogicNode;
import jdk.graal.compiler.nodes.MergeNode;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.PiNode;
import jdk.graal.compiler.nodes.ProfileData;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.ValuePhiNode;
import jdk.graal.compiler.nodes.calc.IntegerBelowNode;
import jdk.graal.compiler.nodes.calc.IntegerEqualsNode;
import jdk.graal.compiler.nodes.calc.IsNullNode;
import jdk.graal.compiler.nodes.extended.ForeignCallNode;
import jdk.graal.compiler.nodes.extended.GuardingNode;
import jdk.graal.compiler.nodes.extended.IsFlatArrayNode;
import jdk.graal.compiler.nodes.extended.LoadArrayComponentHubNode;
import jdk.graal.compiler.nodes.extended.LoadHubNode;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import jdk.graal.compiler.nodes.graphbuilderconf.NodePlugin;
import jdk.graal.compiler.nodes.java.ArrayLengthNode;
import jdk.graal.compiler.nodes.java.FinalFieldBarrierNode;
import jdk.graal.compiler.nodes.java.InstanceOfDynamicNode;
import jdk.graal.compiler.nodes.java.InstanceOfNode;
import jdk.graal.compiler.nodes.java.LoadFieldNode;
import jdk.graal.compiler.nodes.java.LoadIndexedNode;
import jdk.graal.compiler.nodes.java.NewInstanceNode;
import jdk.graal.compiler.nodes.java.StoreFieldNode;
import jdk.graal.compiler.nodes.java.StoreFlatFieldNode;
import jdk.graal.compiler.nodes.java.StoreFlatIndexedNode;
import jdk.graal.compiler.nodes.java.StoreIndexedNode;
import jdk.graal.compiler.nodes.type.StampTool;
import jdk.vm.ci.hotspot.HotSpotResolvedObjectType;
import jdk.vm.ci.meta.DeoptimizationReason;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaType;

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

        // make a null check for all load operations
        object = genNullCheck(b, object);

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

        // make a null check for all load operations
        object = genNullCheck(b, object);

        HotSpotResolvedObjectType fieldType = (HotSpotResolvedObjectType) field.getType();

        int destOff = field.getOffset();

        ResolvedJavaField[] innerFields = fieldType.getInstanceFields(true);

        List<ValueNode> readOperations = new ArrayList<>();
        List<StoreFlatFieldNode.StoreFieldWrapper> writeOperations = new ArrayList<>();

        for (int i = 0; i < innerFields.length; i++) {
            ResolvedJavaField innerField = innerFields[i];
            assert !innerField.isFlat() : "the iteration over nested fields is handled by the loop itself";

            // returned fields include a header offset of their holder
            int off = innerField.getOffset() - fieldType.firstFieldOffset();

            // holder has a header
            ValueNode load = b.add(LoadFieldNode.create(b.getAssumptions(), value, innerField));
            readOperations.add(b.maskSubWordValue(load, innerField.getJavaKind()));

            // holder has no header so remove the header offset
            writeOperations.add(new StoreFlatFieldNode.StoreFieldWrapper(i, innerField.changeOffset(destOff + off)));
        }
        StoreFlatFieldNode storeFlatFieldNode = b.add(new StoreFlatFieldNode(object, field, writeOperations));
        storeFlatFieldNode.addValues(readOperations);
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
        if (!elementKind.isObject())
            return false;

        boolean isInlineTypeArray = array.stamp(NodeView.DEFAULT).isInlineTypeArray();
        boolean canBeInlineTypeArray = array.stamp(NodeView.DEFAULT).canBeInlineTypeArray();

        if (canBeInlineTypeArray) {
            // array can consist of inline objects
            HotSpotResolvedObjectType resolvedType = (HotSpotResolvedObjectType) array.stamp(NodeView.DEFAULT).javaType(b.getMetaAccess());

            array = genNullCheck(b, array);
            boundsCheck = genBoundsCheck(b, boundsCheck, array, index);
            index = createPositiveIndex(b.getGraph(), index, boundsCheck);

            if (isInlineTypeArray && resolvedType.isFlatArray()) {
                // array is known to consist of flat inline objects
                int shift = resolvedType.getLog2ComponentSize();
                b.push(elementKind,
                                genArrayLoadFlatField(b, array, index, boundsCheck, resolvedType, shift));
                return true;
            }

            // check at runtime if we have a flat array

            BeginNode trueBegin = b.getGraph().add(new BeginNode());
            BeginNode falseBegin = b.getGraph().add(new BeginNode());
            genFlatArrayCheck(b, array, trueBegin, falseBegin);

            Stamp resultStamp = null;

            // true branch - flat array
            FixedWithNextNode instanceFlatArray;
            if (isInlineTypeArray) {
                // produce code that loads the flat inline type
                int shift = resolvedType.convertToFlatArray().getLog2ComponentSize();

                instanceFlatArray = genArrayLoadFlatField(b, array, index, boundsCheck, resolvedType,
                                shift);
                resultStamp = instanceFlatArray.stamp(NodeView.DEFAULT);
                if (hasNoNext(trueBegin)) {
                    trueBegin.setNext(instanceFlatArray);
                }
            } else {
                // we don't know the type at compile time, produce a runtime call
                ForeignCallNode load = b.add(new ForeignCallNode(LOADUNKNOWNINLINE, array, index));
                resultStamp = load.stamp(NodeView.DEFAULT);
                instanceFlatArray = load;
                trueBegin.setNext(load);
            }

            EndNode trueEnd = b.add(new EndNode());

            // false branch - no flat array
            ValueNode instanceNonFlatArray = b.add(LoadIndexedNode.create(b.getAssumptions(), array, index, boundsCheck, elementKind, b.getMetaAccess(), b.getConstantReflection()));
            resultStamp = resultStamp.meet(instanceNonFlatArray.stamp(NodeView.DEFAULT));
            EndNode falseEnd = b.add(new EndNode());
            if (instanceNonFlatArray instanceof FixedNode fixedNode) {
                falseBegin.setNext(fixedNode);
            } else {
                falseBegin.setNext(falseEnd);
            }

            // Stamp stamp = resolvedType.getComponentType() == null ? StampFactory.object() :
            // StampFactory.forDeclaredType(b.getAssumptions(), resolvedType.getComponentType(),
            // false).getTrustedStamp();
            ValuePhiNode phiNode = b.add(new ValuePhiNode(resultStamp, null,
                            instanceFlatArray, instanceNonFlatArray));
            b.push(elementKind, phiNode);
            // merge, wait for frame state creation until phi is on stack
            MergeNode merge = b.add(new MergeNode());
            phiNode.setMerge(merge);
            merge.addForwardEnd(trueEnd);
            merge.addForwardEnd(falseEnd);
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

            ValueNode load = LoadIndexedNode.create(b.getAssumptions(), array, index, boundsCheck, innerField.getJavaKind(), b.getMetaAccess(), b.getConstantReflection());
            if (load instanceof LoadIndexedNode loadIndexed) {
                // holder has no header so remove the header offset
                loadIndexed.setAdditionalOffset(off);
                loadIndexed.setShift(shift);
                loadIndexed.setLocation(innerField);
            }
            b.add(load);

            // new holder has a header
            StoreFieldNode storeFieldNode = new StoreFieldNode(newInstance, innerField, b.maskSubWordValue(load, innerField.getJavaKind()));

            // store shouldn't get a framestate, the new instance does not exist in code
            storeFieldNode.noSideEffect();
            b.add(storeFieldNode);

        }
        b.add(new FinalFieldBarrierNode(newInstance));
        b.pop(JavaKind.Object);
        return newInstance;
    }

    @Override
    public boolean handleStoreIndexed(GraphBuilderContext b, ValueNode array, ValueNode index, GuardingNode boundsCheck, GuardingNode storeCheck, JavaKind elementKind, ValueNode value) {
        if (!elementKind.isObject())
            return false;

        boolean isInlineTypeArray = array.stamp(NodeView.DEFAULT).isInlineTypeArray();
        boolean canBeInlineTypeArray = array.stamp(NodeView.DEFAULT).canBeInlineTypeArray();

        if (canBeInlineTypeArray) {
            // array can consist of inline objects
            HotSpotResolvedObjectType resolvedType = (HotSpotResolvedObjectType) array.stamp(NodeView.DEFAULT).javaType(b.getMetaAccess());

            // produce checks for all store indexed nodes
            array = genNullCheck(b, array);
            boundsCheck = genBoundsCheck(b, boundsCheck, array, index);
            storeCheck = genStoreCheck(b, storeCheck, array, value);
            index = createPositiveIndex(b.getGraph(), index, boundsCheck);

            if (isInlineTypeArray && resolvedType.isFlatArray()) {
                // array is known to consist of flat inline objects
                int shift = resolvedType.getLog2ComponentSize();
                // we store the value in a flat array we need to do a null check before loading the
                // fields
                ValueNode nullCheckedValue = genNullCheck(b, value);
                genArrayStoreFlatField(b, array, index, boundsCheck, storeCheck, resolvedType,
                                nullCheckedValue, shift);
                return true;
            }

            // runtime check necessary

            BeginNode trueBegin = b.getGraph().add(new BeginNode());
            BeginNode falseBegin = b.getGraph().add(new BeginNode());
            genFlatArrayCheck(b, array, trueBegin, falseBegin);

            int shift = resolvedType.convertToFlatArray().getLog2ComponentSize();


            // true branch - flat array
            EndNode trueEnd;
            // we store the value in a flat array we need to do a null check before loading the
            // fields
            ValueNode nullCheckedValue = genNullCheck(b, value, trueBegin);
            if (isInlineTypeArray) {

                // produce code that stores the flat inline type
                ValueNode firstFixedNode = genArrayStoreFlatField(b, array, index, boundsCheck, storeCheck, resolvedType,
                                nullCheckedValue, shift);
                trueEnd = b.add(new EndNode());
                if (hasNoNext(trueBegin)) {
                    if (firstFixedNode instanceof FixedNode fixedNode) {
                        trueBegin.setNext(fixedNode);
                    } else {
                        trueBegin.setNext(trueEnd);
                    }
                }

            } else {
                // we don't know the type at compile time, produce a runtime call
                ForeignCallNode store = b.add(new ForeignCallNode(STOREUNKNOWNINLINE, array, index, value));
                if (hasNoNext(trueBegin))
                    trueBegin.setNext(store);
                trueEnd = b.add(new EndNode());
            }

            // false branch - no flat array
            StoreIndexedNode storeIndexed = b.add(new StoreIndexedNode(array, index, boundsCheck, storeCheck, elementKind, b.maskSubWordValue(value, elementKind)));
            falseBegin.setNext(storeIndexed);
            EndNode falseEnd = b.add(new EndNode());

            // merge
            MergeNode merge = b.add(new MergeNode());
            merge.addForwardEnd(trueEnd);
            merge.addForwardEnd(falseEnd);
            return true;
        }
        return false;
    }

    private ValueNode genArrayStoreFlatField(GraphBuilderContext b, ValueNode array, ValueNode index, GuardingNode boundsCheck, GuardingNode storeCheck, HotSpotResolvedObjectType resolvedType,
                    ValueNode value, int shift) {
        HotSpotResolvedObjectType elementType = (HotSpotResolvedObjectType) resolvedType.getComponentType();
        ResolvedJavaField[] innerFields = elementType.getInstanceFields(true);

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

            if (i == 0)
                returnValue = load;

            // new holder has a header
            writeOperations.add(new StoreFlatIndexedNode.StoreIndexedWrapper(i, innerField.getJavaKind(), off, shift));

        }

        // create wrapper for the store operations
        StoreFlatIndexedNode storeFlatIndexedNode = b.add(new StoreFlatIndexedNode(array, index, boundsCheck, storeCheck, elementType.getJavaKind(), writeOperations));
        storeFlatIndexedNode.addValues(readOperations);

        return returnValue;
    }

    private void genFlatArrayCheck(GraphBuilderContext b, ValueNode array, BeginNode trueBegin, BeginNode falseBegin) {
        IsFlatArrayNode isFlatArrayNode = b.add(new IsFlatArrayNode(array));
        LogicNode condition = b.add(new IntegerEqualsNode(isFlatArrayNode, ConstantNode.forConstant(JavaConstant.INT_1, b.getMetaAccess(), b.getGraph())));

        // TODO: insert profiling data
        b.add(new IfNode(condition, trueBegin, falseBegin, ProfileData.BranchProbabilityData.unknown()));
    }

    private ValueNode genNullCheck(GraphBuilderContext b, ValueNode value) {
        return genNullCheck(b, value, null);
    }

    private ValueNode genNullCheck(GraphBuilderContext b, ValueNode value, BeginNode begin) {
        if (!StampTool.isPointerNonNull(value)) {
            LogicNode condition = b.add(IsNullNode.create(value));
            FixedNode guardingNode = b.add(new FixedGuardNode(condition, DeoptimizationReason.NullCheckException, InvalidateReprofile, true));
            if (hasNoNext(begin))
                begin.setNext(guardingNode);
            return b.add(PiNode.create(value, StampFactory.objectNonNull(), guardingNode));
        }
        return value;
    }

    private GuardingNode genStoreCheck(GraphBuilderContext b, GuardingNode storeCheck, ValueNode array, ValueNode value) {
        return genStoreCheck(b, storeCheck, array, value, null);
    }

    private GuardingNode genStoreCheck(GraphBuilderContext b, GuardingNode storeCheck, ValueNode array, ValueNode value, BeginNode begin) {
        if (storeCheck != null)
            return storeCheck;

        LogicNode condition;
        TypeReference arrayType = StampTool.typeReferenceOrNull(array);
        if (arrayType != null && arrayType.isExact()) {
            ResolvedJavaType elementType = arrayType.getType().getComponentType();
            TypeReference typeReference = TypeReference.createTrusted(b.getGraph().getAssumptions(),
                            elementType);
            condition = b.getGraph().addOrUniqueWithInputs(InstanceOfNode.createAllowNull(typeReference,
                            value, null, null));
        } else {
            ValueNode arrayClass = b.add(LoadHubNode.create(array, b.getStampProvider(), b.getMetaAccess(), b.getConstantReflection()));
            ValueNode componentHub = b.add(LoadArrayComponentHubNode.create(arrayClass, b.getStampProvider(), b.getMetaAccess(), b.getConstantReflection()));
            condition = b.add(InstanceOfDynamicNode.create(b.getAssumptions(), b.getConstantReflection(), componentHub, value, true));
        }
        if (condition.isTautology()) {
            // Skip unnecessary guards
            return null;
        }
        FixedGuardNode guard = b.add(new FixedGuardNode(condition, DeoptimizationReason.ArrayStoreException, InvalidateReprofile));
        if (hasNoNext(begin)) {
            begin.setNext(guard);
        }

        return guard;
    }

    private GuardingNode genBoundsCheck(GraphBuilderContext b, GuardingNode boundsCheck, ValueNode array, ValueNode index) {
        return genBoundsCheck(b, boundsCheck, array, index, null);
    }

    private GuardingNode genBoundsCheck(GraphBuilderContext b, GuardingNode boundsCheck, ValueNode array, ValueNode index, BeginNode begin) {
        if (boundsCheck != null)
            return boundsCheck;
        ValueNode length = b.add(ArrayLengthNode.create(array, b.getConstantReflection()));
        if (length instanceof FixedNode fixed && hasNoNext(begin)) {
            begin.setNext(fixed);
        }
        LogicNode condition = b.add(IntegerBelowNode.create(b.getConstantReflection(), b.getMetaAccess(), b.getOptions(), null, index, length, NodeView.DEFAULT));
        if (condition.isTautology()) {
            // Skip unnecessary guards
            return null;
        }
        FixedGuardNode guard = b.add(new FixedGuardNode(condition, DeoptimizationReason.BoundsCheckException, InvalidateReprofile));
        if (hasNoNext(begin)) {
            begin.setNext(guard);
        }
        return guard;
    }

    private ValueNode createPositiveIndex(StructuredGraph graph, ValueNode index, GuardingNode boundsCheck) {
        return graph.addOrUnique(PiNode.create(index, POSITIVE_ARRAY_INDEX_STAMP, boundsCheck != null ? boundsCheck.asNode() : null));
    }

    public static boolean hasNoNext(BeginNode begin) {
        return begin != null && begin.next() == null;
    }

    public static final HotSpotForeignCallDescriptor LOADUNKNOWNINLINE = new HotSpotForeignCallDescriptor(SAFEPOINT, NO_SIDE_EFFECT, OBJECT_ARRAY_LOCATION, "loadUnknownInline", Object.class,
                    Object.class,
                    int.class);

    public static final HotSpotForeignCallDescriptor STOREUNKNOWNINLINE = new HotSpotForeignCallDescriptor(LEAF, HAS_SIDE_EFFECT, OBJECT_ARRAY_LOCATION, "storeUnknownInline", void.class,
                    Object.class,
                    int.class, Object.class);
}
