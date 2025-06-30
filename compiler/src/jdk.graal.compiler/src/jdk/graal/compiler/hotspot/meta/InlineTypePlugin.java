package jdk.graal.compiler.hotspot.meta;

import static jdk.graal.compiler.core.common.spi.ForeignCallDescriptor.CallSideEffect.HAS_SIDE_EFFECT;
import static jdk.graal.compiler.core.common.spi.ForeignCallDescriptor.CallSideEffect.NO_SIDE_EFFECT;
import static jdk.graal.compiler.hotspot.meta.HotSpotForeignCallDescriptor.Transition.SAFEPOINT;
import static jdk.graal.compiler.nodes.memory.MemoryKill.NO_LOCATION;
import static jdk.graal.compiler.replacements.DefaultJavaLoweringProvider.POSITIVE_ARRAY_INDEX_STAMP;
import static jdk.vm.ci.meta.DeoptimizationAction.InvalidateReprofile;
import static jdk.vm.ci.meta.DeoptimizationReason.RuntimeConstraint;
import static org.graalvm.word.LocationIdentity.any;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import jdk.graal.compiler.core.common.GraalOptions;
import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.core.common.type.TypeReference;
import jdk.graal.compiler.hotspot.HotspotGraalValhallaServices;
import jdk.graal.compiler.nodes.BeginNode;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.DeoptimizeNode;
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
import jdk.graal.compiler.nodes.calc.IsNullNode;
import jdk.graal.compiler.nodes.extended.ForeignCallNode;
import jdk.graal.compiler.nodes.extended.GuardingNode;
import jdk.graal.compiler.nodes.extended.InlineTypeNode;
import jdk.graal.compiler.nodes.extended.IsFlatArrayNode;
import jdk.graal.compiler.nodes.extended.LoadArrayComponentHubNode;
import jdk.graal.compiler.nodes.extended.LoadHubNode;
import jdk.graal.compiler.nodes.extended.MembarNode;
import jdk.graal.compiler.nodes.extended.ValueAnchorNode;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import jdk.graal.compiler.nodes.graphbuilderconf.NodePlugin;
import jdk.graal.compiler.nodes.java.ArrayLengthNode;
import jdk.graal.compiler.nodes.java.InstanceOfDynamicNode;
import jdk.graal.compiler.nodes.java.InstanceOfNode;
import jdk.graal.compiler.nodes.java.LoadFieldNode;
import jdk.graal.compiler.nodes.java.LoadIndexedNode;
import jdk.graal.compiler.nodes.java.StoreFieldNode;
import jdk.graal.compiler.nodes.java.StoreFlatElementNode;
import jdk.graal.compiler.nodes.java.StoreFlatFieldNode;
import jdk.graal.compiler.nodes.java.StoreIndexedNode;
import jdk.graal.compiler.nodes.type.StampTool;
import jdk.graal.compiler.nodes.util.InlineTypeUtil;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.serviceprovider.GraalValhallaServices;
import jdk.vm.ci.hotspot.HotSpotResolvedObjectType;
import jdk.vm.ci.meta.DeoptimizationAction;
import jdk.vm.ci.meta.DeoptimizationReason;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaType;

public class InlineTypePlugin implements NodePlugin {

    boolean virtualizeFromInlineObject;

    public InlineTypePlugin(OptionValues options) {
        virtualizeFromInlineObject = GraalOptions.PartialEscapeAnalysis.getValue(options) && GraalOptions.VirtualizeFromInlineObject.getValue(options);
    }

    @Override
    public boolean handleLoadField(GraphBuilderContext b, ValueNode object, ResolvedJavaField field) {

        if (GraalValhallaServices.isFlat(field)) {
            if (!GraalValhallaServices.isNullFreeInlineType(field)) {
                // field is flat and nullable

                // TODO: when JDK-8341767 is done
                b.append(new DeoptimizeNode(DeoptimizationAction.None, RuntimeConstraint));
                b.push(field.getJavaKind(), ConstantNode.defaultForKind(field.getJavaKind(), b.getGraph()));
                return true;

            } else {
                // field is flat and null-restricted
                b.push(JavaKind.Object, genLoadFlatField(b, object, field));
            }
            return true;

        } else if (GraalValhallaServices.isNullFreeInlineType(field)) {
            // field is null-free but not flat

            // for null free inline type fields it is the responsibility of the reader to return the
            // default instance if the field is null
            ValueNode nonNullObject = genNullCheck(b, object);
            LoadFieldNode fieldValue = b.add(LoadFieldNode.create(b.getAssumptions(), nonNullObject, field));
            genHandleNullFreeInlineTypeField(b, fieldValue, field);
            return true;

        }

        // do null-check here to avoid it in PEA, if the holder has no identity
        Stamp stamp = StampFactory.forDeclaredType(b.getAssumptions(), field.getType().resolve(field.getDeclaringClass()), false).getTrustedStamp();
        if (!GraalValhallaServices.isIdentity(field.getDeclaringClass()) || StampTool.isNullableInlineType(stamp, b.getValhallaOptionsProvider())) {
            ValueNode nonNullObject = genNullCheck(b, object);
            ValueNode load = b.add(LoadFieldNode.create(b.getAssumptions(), nonNullObject, field));
            if (virtualizeFromInlineObject && StampTool.isNullableInlineType(load, b.getValhallaOptionsProvider())) {
                FixedNode addBefore = b.add(new ValueAnchorNode());
                load = virtualizeFromInlineObject(b, load, stamp.javaType(b.getMetaAccess()), addBefore);
            }
            b.push(field.getJavaKind(), load);
            return true;
        }
        return false;
    }

    @Override
    public boolean handleLoadStaticField(GraphBuilderContext b, ResolvedJavaField field) {
        if (GraalValhallaServices.isNullFreeInlineType(field) && !GraalValhallaServices.isInitialized(field)) {
            // field is a static null-free inline type and not already initialized, do a null-check
            // at runtime
            LoadFieldNode fieldValue = b.add(LoadFieldNode.create(b.getAssumptions(), null, field));
            genHandleNullFreeInlineTypeField(b, fieldValue, field);
            return true;
        }
        return false;
    }

    /**
     * Responsible for loading the inline object stored in a flat representation as a field value in
     * another object.
     * 
     * @param b the context
     * @param object the receiver object for the field access
     * @param field the accessed field
     * @return an {@link InlineTypeNode} representing the loaded flat field
     */
    private static InlineTypeNode genLoadFlatField(GraphBuilderContext b, ValueNode object, ResolvedJavaField field) {

        // make a null check for all load operations
        ValueNode nonNullObject = genNullCheck(b, object);

        // only support null-restricted flat fields for now
        // field type is already resolved because value classes have the loadableDescriptor (also
        // known as preload) attribute automatically set in hotspot.
        HotSpotResolvedObjectType fieldType = (HotSpotResolvedObjectType) field.getType();
        ResolvedJavaField[] innerFields = fieldType.getInstanceFields(true);
        LoadFieldNode[] loads = new LoadFieldNode[innerFields.length];

        int srcOff = field.getOffset();

        for (int i = 0; i < innerFields.length; i++) {
            ResolvedJavaField innerField = innerFields[i];
            assert !GraalValhallaServices.isFlat(innerField) : "the iteration over nested fields is handled by the loop itself";

            // returned fields include a header offset of their holder, calculate the offset without
            // the header
            int off = innerField.getOffset() - HotspotGraalValhallaServices.payloadOffset(fieldType);

            // holder is directly embedded in other object, use the offset without the header
            loads[i] = b.add(
                            LoadFieldNode.create(b.getAssumptions(), nonNullObject,
                                            GraalValhallaServices.setContainerClass(GraalValhallaServices.changeOffset(innerField, srcOff + off), field.getDeclaringClass())));
        }

        // create InlineTypeNode
        return b.append(InlineTypeNode.createNonNullWithoutOop(fieldType, loads));
    }

    /**
     * Responsible for checking an already loaded value from a null-restricted field at runtime. If
     * the value is null, it has to be replaced by the default instance.
     *
     * @param b the context
     * @param fieldValue the already loaded field value
     * @param field the accessed field
     */
    private void genHandleNullFreeInlineTypeField(GraphBuilderContext b, ValueNode fieldValue, ResolvedJavaField field) {
        HotSpotResolvedObjectType fieldType = (HotSpotResolvedObjectType) field.getType();
        BeginNode trueBegin = null;
        BeginNode falseBegin = b.getGraph().add(new BeginNode());

        IfNode ifNode = genFieldNullCheck(b, fieldValue, trueBegin, falseBegin);

        // true branch - field is null use the default instance
        trueBegin = b.add(new BeginNode());
        ifNode.setTrueSuccessor(trueBegin);
        EndNode trueEnd = b.add(new EndNode());
        ValueNode defaultValue = b.add(ConstantNode.forConstant(GraalValhallaServices.getDefaultInlineTypeInstance(fieldType), b.getMetaAccess(), b.getGraph()));
        if (virtualizeFromInlineObject) {
            defaultValue = virtualizeFromInlineObject(b, defaultValue, fieldType, trueEnd);
        }

        // false branch - field is non-null
        EndNode falseEnd = b.add(new EndNode());
        falseBegin.setNext(falseEnd);
        ValueNode virtualizedFieldValue = fieldValue;
        if (virtualizeFromInlineObject) {
            virtualizedFieldValue = virtualizeFromInlineObject(b, fieldValue, fieldType, falseEnd);
        }

        // return the default instance if the field was null otherwise the value
        ValuePhiNode phiNode = b.add(new ValuePhiNode(StampFactory.forDeclaredType(b.getAssumptions(), field.getType(), true).getTrustedStamp(), null,
                        defaultValue, virtualizedFieldValue));
        b.push(JavaKind.Object, phiNode);

        // merge
        MergeNode merge = b.add(new MergeNode());
        phiNode.setMerge(merge);

        merge.addForwardEnd(trueEnd);
        merge.addForwardEnd(falseEnd);
    }

    @Override
    public boolean handleStoreField(GraphBuilderContext b, ValueNode object, ResolvedJavaField field, ValueNode value) {
        if (GraalValhallaServices.isFlat(field)) {
            // field is flat
            if (!GraalValhallaServices.isNullFreeInlineType(field)) {
                // field is flat and nullable

                // TODO: when JDK-8341767 is done
                b.append(new DeoptimizeNode(DeoptimizationAction.None, RuntimeConstraint));
                return true;

            } else {
                // field is null restricted
                genStoreFlatField(b, object, field, value);
            }
            return true;
        }
        if (GraalValhallaServices.isNullFreeInlineType(field)) {
            ValueNode nonNullValue = genNullCheck(b, value);
            StoreFieldNode storeFieldNode = new StoreFieldNode(object, field, b.maskSubWordValue(nonNullValue, field.getJavaKind()));
            b.append(storeFieldNode);
            b.setStateAfter(storeFieldNode);
            return true;
        }
        return false;
    }

    /**
     * Responsible for storing the {@code value} in a flat representation as a field value of
     * another {@code object}. It therefore loads each field value and stores it at a specific
     * offset into the {@code object}.
     *
     * @param b the context
     * @param object the receiver object for the field access
     * @param field the accessed field
     * @param value the value to be stored into the field
     */
    private static void genStoreFlatField(GraphBuilderContext b, ValueNode object, ResolvedJavaField field, ValueNode value) {

        // make a null check for all load operations
        ValueNode nonNullValue = genNullCheck(b, value);
        // make a null check for all store operations
        ValueNode nonNullObject = genNullCheck(b, object);

        HotSpotResolvedObjectType fieldType = (HotSpotResolvedObjectType) field.getType();

        int destOff = field.getOffset();

        ResolvedJavaField[] innerFields = fieldType.getInstanceFields(true);

        List<ValueNode> readOperations = new ArrayList<>();
        List<StoreFlatFieldNode.SingleWriteOperation> writeOperations = new ArrayList<>();

        for (int i = 0; i < innerFields.length; i++) {
            ResolvedJavaField innerField = innerFields[i];
            assert !GraalValhallaServices.isFlat(innerField) : "the iteration over nested fields is handled by the loop itself";

            // returned fields include a header offset of their holder, calculate the offset without
            // the header
            int off = innerField.getOffset() - HotspotGraalValhallaServices.payloadOffset(fieldType);

            // holder has a header, use the offset with the header
            ValueNode load = b.add(LoadFieldNode.create(b.getAssumptions(), nonNullValue, innerField));
            readOperations.add(b.maskSubWordValue(load, innerField.getJavaKind()));

            // holder is directly embedded in other object, use the offset without the header
            writeOperations.add(new StoreFlatFieldNode.SingleWriteOperation(
                            GraalValhallaServices.setContainerClass(GraalValhallaServices.changeOffset(innerField, destOff + off), field.getDeclaringClass())));
        }
        StoreFlatFieldNode storeFlatFieldNode = b.add(new StoreFlatFieldNode(nonNullObject, field, writeOperations));
        storeFlatFieldNode.addValues(readOperations);
    }

    private static IfNode genFieldNullCheck(GraphBuilderContext b, ValueNode fieldValue, BeginNode trueBegin, BeginNode falseBegin) {
        LogicNode condition = b.add(IsNullNode.create(fieldValue));
        b.add(condition);

        return b.add(new IfNode(condition, trueBegin, falseBegin, ProfileData.BranchProbabilityData.unknown()));
    }

    @Override
    public boolean handleStoreStaticField(GraphBuilderContext b, ResolvedJavaField field, ValueNode value) {
        if (GraalValhallaServices.isNullFreeInlineType(field)) {
            ValueNode nonNullValue = genNullCheck(b, value);
            StoreFieldNode storeFieldNode = new StoreFieldNode(null, field, b.maskSubWordValue(nonNullValue, field.getJavaKind()));
            b.append(storeFieldNode);
            b.setStateAfter(storeFieldNode);
            return true;
        }
        return false;
    }

    @Override
    public boolean handleLoadIndexed(GraphBuilderContext b, ValueNode array, ValueNode index, GuardingNode boundsCheck, JavaKind elementKind) {
        if (!elementKind.isObject() || !b.getValhallaOptionsProvider().useArrayFlattening())
            return false;

        boolean canBeInlineTypeArray = StampTool.canBeInlineTypeArray(array, b.getValhallaOptionsProvider());

        if (canBeInlineTypeArray) {
            // array can consist of inline objects
            HotSpotResolvedObjectType resolvedType = (HotSpotResolvedObjectType) array.stamp(NodeView.DEFAULT).javaType(b.getMetaAccess());

            ValueNode nonNullArray = genNullCheck(b, array);
            GuardingNode newBoundsCheck = genBoundsCheck(b, boundsCheck, nonNullArray, index);
            ValueNode positiveIndex = createPositiveIndex(b.getGraph(), index, newBoundsCheck);

            boolean isInlineTypeArray = StampTool.isInlineTypeArray(nonNullArray, b.getValhallaOptionsProvider());
            if (isInlineTypeArray && GraalValhallaServices.isFlatArray(resolvedType)) {
                // array is known to consist of flat inline objects
                int shift = HotspotGraalValhallaServices.getLog2ComponentSize(resolvedType);
                b.push(elementKind,
                                genLoadFlatElement(b, nonNullArray, positiveIndex, resolvedType, shift, null));
                return true;
            }

            // check at runtime if we have a flat array

            BeginNode trueBegin = b.getGraph().add(new BeginNode());
            BeginNode falseBegin = b.getGraph().add(new BeginNode());
            genFlatArrayCheck(b, nonNullArray, trueBegin, falseBegin);

            Stamp resultStamp = null;

            // true branch - flat array
            FixedWithNextNode instanceFlatArray;
            if (isInlineTypeArray) {
                // produce code that loads the flat inline type
                int shift = HotspotGraalValhallaServices.getLog2ComponentSize(HotspotGraalValhallaServices.convertToFlatArray(resolvedType));

                instanceFlatArray = genLoadFlatElement(b, nonNullArray, positiveIndex, resolvedType,
                                shift, trueBegin);
                resultStamp = instanceFlatArray.stamp(NodeView.DEFAULT);
                if (hasNoNext(trueBegin)) {
                    trueBegin.setNext(instanceFlatArray);
                }
            } else {
                // we don't know the type at compile time, produce a runtime call
                ForeignCallNode load = b.add(new ForeignCallNode(LOAD_UNKNOWN_INLINE, nonNullArray, positiveIndex));
                resultStamp = load.stamp(NodeView.DEFAULT);
                instanceFlatArray = load;
                trueBegin.setNext(load);
                // add a membar for newly created inline objects
                b.append(MembarNode.forInitialization());
            }

            EndNode trueEnd = b.add(new EndNode());

            // false branch - no flat array
            ValueNode instanceNonFlatArray = b.add(LoadIndexedNode.create(b.getAssumptions(), nonNullArray, positiveIndex, falseBegin, elementKind, b.getMetaAccess(), b.getConstantReflection()));
            resultStamp = resultStamp.meet(instanceNonFlatArray.stamp(NodeView.DEFAULT));
            EndNode falseEnd = b.add(new EndNode());
            if (instanceNonFlatArray instanceof FixedNode fixedNode) {
                falseBegin.setNext(fixedNode);
            } else {
                falseBegin.setNext(falseEnd);
            }

            if (isInlineTypeArray && virtualizeFromInlineObject) {
                // avoid allocation due to merge

                ResolvedJavaType type = resultStamp.javaType(b.getMetaAccess());
                instanceNonFlatArray = virtualizeFromInlineObject(b, instanceNonFlatArray, type, falseEnd);
            }

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

    /**
     * Similar to {@link #genLoadFlatField(GraphBuilderContext, ValueNode, ResolvedJavaField)}, but
     * loads a flat element from an array.
     */
    private static FixedWithNextNode genLoadFlatElement(GraphBuilderContext b, ValueNode array, ValueNode index, HotSpotResolvedObjectType resolvedType, int shift,
                    BeginNode begin) {
        HotSpotResolvedObjectType componentType = (HotSpotResolvedObjectType) resolvedType.getComponentType();

        ResolvedJavaField[] fields = componentType.getInstanceFields(true);
        LoadIndexedNode[] loads = new LoadIndexedNode[fields.length];

        for (int i = 0; i < fields.length; i++) {
            ResolvedJavaField field = fields[i];
            assert !GraalValhallaServices.isFlat(field) : "the iteration over nested fields is handled by the loop itself";

            LoadIndexedNode load;
            if (field.getJavaKind() == JavaKind.Object) {
                load = new LoadIndexedNode(LoadIndexedNode.TYPE, StampFactory.forDeclaredType(b.getAssumptions(), field.getType(), false).getTrustedStamp(), array, index, begin,
                                field.getJavaKind());
            } else {
                load = new LoadIndexedNode(LoadIndexedNode.TYPE, StampFactory.forKind(field.getJavaKind()), array, index, begin, field.getJavaKind());
            }

            // returned fields include a header offset of their holder, calculate the offset without
            // the header
            int off = field.getOffset() - HotspotGraalValhallaServices.payloadOffset(componentType);
            load.setAdditionalOffset(off);
            load.setShift(shift);
            load.setLocation(GraalValhallaServices.setContainerClass(GraalValhallaServices.changeOffset(field, off), componentType));
            loads[i] = b.add(load);

        }
        InlineTypeNode inlineTypeNode = b.append(InlineTypeNode.createNonNullWithoutOop(componentType, loads));

        // return first node in control flow
        if (hasNoNext(begin)) {
            begin.setNext(loads.length > 0 ? loads[0] : inlineTypeNode);
        }
        return inlineTypeNode;
    }

    @Override
    public boolean handleStoreIndexed(GraphBuilderContext b, ValueNode array, ValueNode index, GuardingNode boundsCheck, GuardingNode storeCheck, JavaKind elementKind, ValueNode value) {
        if (!elementKind.isObject() || !b.getValhallaOptionsProvider().useArrayFlattening())
            return false;

        boolean canBeInlineTypeArray = StampTool.canBeInlineTypeArray(array, b.getValhallaOptionsProvider());

        if (canBeInlineTypeArray) {
            // array can consist of inline objects
            HotSpotResolvedObjectType resolvedType = (HotSpotResolvedObjectType) array.stamp(NodeView.DEFAULT).javaType(b.getMetaAccess());

            // produce checks for all store indexed nodes
            ValueNode nonNullArray = genNullCheck(b, array);
            GuardingNode newBoundsCheck = genBoundsCheck(b, boundsCheck, nonNullArray, index);
            ValueNode positiveIndex = createPositiveIndex(b.getGraph(), index, newBoundsCheck);
            GuardingNode newStoreCheck = genStoreCheck(b, storeCheck, nonNullArray, value);

            boolean isInlineTypeArray = StampTool.isInlineTypeArray(nonNullArray, b.getValhallaOptionsProvider());
            if (isInlineTypeArray && GraalValhallaServices.isFlatArray(resolvedType)) {
                // array is known to consist of flat inline objects
                int shift = HotspotGraalValhallaServices.getLog2ComponentSize(resolvedType);
                // we store the value in a flat array we need to do a null check before loading the
                // fields
                ValueNode nonNullValue = genNullCheck(b, value);
                genStoreFlatElement(b, nonNullArray, positiveIndex, newBoundsCheck, newStoreCheck, resolvedType,
                                nonNullValue, shift);
                return true;
            }

            // runtime check necessary

            BeginNode trueBegin = null;
            BeginNode falseBegin = b.getGraph().add(new BeginNode());
            IfNode ifNode = genFlatArrayCheck(b, nonNullArray, trueBegin, falseBegin);

            // true branch - flat array
            EndNode trueEnd;
            // we store the value in a flat array we need to do a null check before loading the
            // fields
            trueBegin = b.append(new BeginNode());
            ifNode.setTrueSuccessor(trueBegin);

            ValueNode nonNullValue = genNullCheck(b, value);
            if (isInlineTypeArray) {

                // produce code that stores the flat element
                int shift = HotspotGraalValhallaServices.getLog2ComponentSize(HotspotGraalValhallaServices.convertToFlatArray(resolvedType));
                ValueNode firstFixedNode = genStoreFlatElement(b, nonNullArray, positiveIndex, newBoundsCheck, newStoreCheck, resolvedType,
                                nonNullValue, shift);
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
                ForeignCallNode store = b.add(new ForeignCallNode(STORE_UNKNOWN_INLINE, nonNullArray, positiveIndex, nonNullValue));
                if (hasNoNext(trueBegin)) {
                    trueBegin.setNext(store);
                }
                trueEnd = b.add(new EndNode());
            }

            // false branch - no flat array
            StoreIndexedNode storeIndexed = b.add(new StoreIndexedNode(nonNullArray, positiveIndex, newBoundsCheck, newStoreCheck, elementKind, b.maskSubWordValue(value, elementKind)));
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

    /**
     *
     * Similar to
     * {@link #genStoreFlatField(GraphBuilderContext, ValueNode, ResolvedJavaField, ValueNode)}, but
     * stores the object as a flat element into an array.
     */
    private static ValueNode genStoreFlatElement(GraphBuilderContext b, ValueNode array, ValueNode index, GuardingNode boundsCheck, GuardingNode storeCheck, HotSpotResolvedObjectType resolvedType,
                    ValueNode value, int shift) {
        HotSpotResolvedObjectType elementType = (HotSpotResolvedObjectType) resolvedType.getComponentType();
        ResolvedJavaField[] fields = elementType.getInstanceFields(true);

        List<ValueNode> readOperations = new ArrayList<>();
        List<StoreFlatElementNode.SingleWriteOperation> writeOperations = new ArrayList<>();

        // empty inline type will have no fields
        ValueNode returnValue = null;

        for (int i = 0; i < fields.length; i++) {
            ResolvedJavaField field = fields[i];
            assert !GraalValhallaServices.isFlat(field) : "the iteration over nested fields is handled by the loop itself";

            ValueNode load = b.add(LoadFieldNode.create(b.getAssumptions(), value, field));
            readOperations.add(b.maskSubWordValue(load, field.getJavaKind()));

            if (i == 0) {
                returnValue = load;
            }

            // returned fields include a header offset of their holder, calculate the offset without
            // the header
            int off = field.getOffset() - HotspotGraalValhallaServices.payloadOffset(elementType);
            writeOperations.add(new StoreFlatElementNode.SingleWriteOperation(GraalValhallaServices.changeOffset(field, off), shift));

        }

        StoreFlatElementNode storeFlatElementNode = b.add(new StoreFlatElementNode(array, index, boundsCheck, storeCheck, writeOperations));
        storeFlatElementNode.addValues(readOperations);

        return returnValue;
    }

    private static IfNode genFlatArrayCheck(GraphBuilderContext b, ValueNode array, BeginNode trueBegin, BeginNode falseBegin) {
        IsFlatArrayNode isFlatArrayNode = b.add(new IsFlatArrayNode(array));

        // TODO: insert profiling data
        return b.add(new IfNode(isFlatArrayNode, trueBegin, falseBegin, ProfileData.BranchProbabilityData.unknown()));
    }

    private static ValueNode genNullCheck(GraphBuilderContext b, ValueNode value) {
        return b.nullCheckedValue(value);
    }

    private static GuardingNode genStoreCheck(GraphBuilderContext b, GuardingNode storeCheck, ValueNode array, ValueNode value) {
        return genStoreCheck(b, storeCheck, array, value, null);
    }

    private static GuardingNode genStoreCheck(GraphBuilderContext b, GuardingNode storeCheck, ValueNode array, ValueNode value, BeginNode begin) {
        if (storeCheck != null) {
            return storeCheck;
        }

        LogicNode condition;
        TypeReference arrayType = StampTool.typeReferenceOrNull(array);
        if (arrayType != null && arrayType.isExact()) {
            ResolvedJavaType elementType = arrayType.getType().getComponentType();
            TypeReference typeReference = TypeReference.createTrusted(b.getGraph().getAssumptions(),
                            elementType);
            condition = b.getGraph().addOrUniqueWithInputs(InstanceOfNode.createAllowNull(typeReference,
                            value, null, null));
        } else {
            ValueNode arrayClass = b.add(LoadHubNode.create(array, b.getStampProvider(), b.getMetaAccess(), b.getConstantReflection(), b.getValhallaOptionsProvider()));
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

    private static GuardingNode genBoundsCheck(GraphBuilderContext b, GuardingNode boundsCheck, ValueNode array, ValueNode index) {
        return genBoundsCheck(b, boundsCheck, array, index, null);
    }

    private static GuardingNode genBoundsCheck(GraphBuilderContext b, GuardingNode boundsCheck, ValueNode array, ValueNode index, BeginNode begin) {
        if (boundsCheck != null) {
            return boundsCheck;
        }
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

    private static ValueNode createPositiveIndex(StructuredGraph graph, ValueNode index, GuardingNode boundsCheck) {
        return graph.addOrUnique(PiNode.create(index, POSITIVE_ARRAY_INDEX_STAMP, boundsCheck != null ? boundsCheck.asNode() : null));
    }

    public static boolean hasNoNext(BeginNode begin) {
        return begin != null && begin.next() == null;
    }

    public ValueNode virtualizeFromInlineObject(GraphBuilderContext b, ValueNode object, ResolvedJavaType type, FixedNode addBefore) {
        StructuredGraph graph = b.getGraph();
        ValueNode[] phis = InlineTypeUtil.createScalarizationCFG(addBefore, object, List.of(type.getInstanceFields(true)), false, true);
        InlineTypeNode inlineTypeNode = graph.add(new InlineTypeNode(type, object, Arrays.copyOfRange(phis, 1, phis.length), phis[0], true));
        graph.addBeforeFixed(addBefore, inlineTypeNode);
        return inlineTypeNode;
    }

    public static final HotSpotForeignCallDescriptor LOAD_UNKNOWN_INLINE = new HotSpotForeignCallDescriptor(SAFEPOINT, NO_SIDE_EFFECT, NO_LOCATION, "loadUnknownInline", Object.class,
                    Object.class,
                    int.class);

    public static final HotSpotForeignCallDescriptor STORE_UNKNOWN_INLINE = new HotSpotForeignCallDescriptor(SAFEPOINT, HAS_SIDE_EFFECT, any(), "storeUnknownInline",
                    void.class,
                    Object.class,
                    int.class, Object.class);
}
