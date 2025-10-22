/*
 * Copyright (c) 2013, 2020, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.hotspot;

import static jdk.graal.compiler.core.common.spi.ForeignCallDescriptor.CallSideEffect.NO_SIDE_EFFECT;
import static jdk.graal.compiler.hotspot.GraalHotSpotVMConfigAccess.VALHALLA_JDK;
import static jdk.graal.compiler.hotspot.meta.HotSpotForeignCallDescriptor.Transition.LEAF_NO_VZERO;
import static jdk.graal.compiler.hotspot.meta.HotSpotForeignCallsProviderImpl.NO_LOCATIONS;
import static jdk.vm.ci.code.CodeUtil.K;
import static jdk.vm.ci.code.CodeUtil.getCallingConvention;
import static jdk.vm.ci.common.InitTimer.timer;

import java.util.Collections;

import jdk.graal.compiler.asm.Assembler;
import jdk.graal.compiler.asm.Label;
import jdk.graal.compiler.core.common.CompilationIdentifier;
import jdk.graal.compiler.core.common.NumUtil;
import jdk.graal.compiler.core.common.alloc.RegisterAllocationConfig;
import jdk.graal.compiler.core.gen.LIRGenerationProvider;
import jdk.graal.compiler.debug.DebugHandlersFactory;
import jdk.graal.compiler.hotspot.meta.HotSpotForeignCallDescriptor;
import jdk.graal.compiler.hotspot.meta.HotSpotHostForeignCallsProvider;
import jdk.graal.compiler.hotspot.meta.HotSpotLoweringProvider;
import jdk.graal.compiler.hotspot.meta.HotSpotProviders;
import jdk.graal.compiler.hotspot.replacements.ValhallaEntryPointCreator;
import jdk.graal.compiler.hotspot.stubs.Stub;
import jdk.graal.compiler.lir.LIR;
import jdk.graal.compiler.lir.asm.CompilationResultBuilder;
import jdk.graal.compiler.lir.asm.EntryPointDecorator;
import jdk.graal.compiler.lir.framemap.FrameMapBuilder;
import jdk.graal.compiler.lir.framemap.ReferenceMapBuilder;
import jdk.graal.compiler.lir.gen.LIRGenerationResult;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.printer.GraalDebugHandlersFactory;
import jdk.graal.compiler.serviceprovider.GraalValhallaServices;
import jdk.graal.compiler.word.Word;
import jdk.vm.ci.code.CallingConvention;
import jdk.vm.ci.code.RegisterConfig;
import jdk.vm.ci.code.StackSlot;
import jdk.vm.ci.code.ValueUtil;
import jdk.vm.ci.common.InitTimer;
import jdk.vm.ci.hotspot.HotSpotCallingConventionType;
import jdk.vm.ci.hotspot.HotSpotJVMCIRuntime;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.Signature;
import jdk.vm.ci.meta.Value;
import jdk.vm.ci.runtime.JVMCICompiler;

/**
 * Common functionality of HotSpot host backends.
 */
public abstract class HotSpotHostBackend extends HotSpotBackend implements LIRGenerationProvider {

    /**
     * Descriptor for {@code SharedRuntime::deopt_blob()->unpack()}.
     */
    public static final HotSpotForeignCallDescriptor DEOPT_BLOB_UNPACK = new HotSpotForeignCallDescriptor(LEAF_NO_VZERO, NO_SIDE_EFFECT, NO_LOCATIONS, "deopt_blob()->unpack()", void.class);

    /**
     * Descriptor for {@code SharedRuntime::deopt_blob()->unpack_with_exception_in_tls()}.
     */
    public static final HotSpotForeignCallDescriptor DEOPT_BLOB_UNPACK_WITH_EXCEPTION_IN_TLS = new HotSpotForeignCallDescriptor(LEAF_NO_VZERO, NO_SIDE_EFFECT, NO_LOCATIONS,
                    "deopt_blob()->unpack_with_exception_in_tls()", void.class);

    /**
     * Descriptor for {@code SharedRuntime::deopt_blob()->uncommon_trap()}.
     */
    public static final HotSpotForeignCallDescriptor DEOPT_BLOB_UNCOMMON_TRAP = new HotSpotForeignCallDescriptor(LEAF_NO_VZERO, NO_SIDE_EFFECT, NO_LOCATIONS, "deopt_blob()->uncommon_trap()",
                    void.class);

    public static final HotSpotForeignCallDescriptor ENABLE_STACK_RESERVED_ZONE = new HotSpotForeignCallDescriptor(LEAF_NO_VZERO, NO_SIDE_EFFECT, NO_LOCATIONS, "enableStackReservedZoneEntry",
                    void.class, Word.class);

    public static final HotSpotForeignCallDescriptor THROW_DELAYED_STACKOVERFLOW_ERROR = new HotSpotForeignCallDescriptor(LEAF_NO_VZERO, NO_SIDE_EFFECT, NO_LOCATIONS, "throwDelayedStackoverflowError",
                    void.class);

    /**
     * Descriptor for {@code SharedRuntime::polling_page_return_handler_blob()->entry_point()}.
     */
    public static final HotSpotForeignCallDescriptor POLLING_PAGE_RETURN_HANDLER = new HotSpotForeignCallDescriptor(LEAF_NO_VZERO, NO_SIDE_EFFECT, NO_LOCATIONS,
                    "polling_page_return_handler_blob()", void.class);

    protected final GraalHotSpotVMConfig config;

    public HotSpotHostBackend(GraalHotSpotVMConfig config, HotSpotGraalRuntimeProvider runtime, HotSpotProviders providers) {
        super(runtime, providers);
        this.config = config;
    }

    @Override
    @SuppressWarnings("try")
    public void completeInitialization(HotSpotJVMCIRuntime jvmciRuntime, OptionValues options) {
        final HotSpotProviders providers = getProviders();
        HotSpotHostForeignCallsProvider foreignCalls = providers.getForeignCalls();
        final HotSpotLoweringProvider lowerer = (HotSpotLoweringProvider) providers.getLowerer();

        try (InitTimer st = timer("foreignCalls.initialize")) {
            foreignCalls.initialize(providers, options);
        }
        try (InitTimer st = timer("lowerer.initialize")) {
            Iterable<DebugHandlersFactory> factories = Collections.singletonList(new GraalDebugHandlersFactory(providers.getSnippetReflection()));
            lowerer.initialize(options, factories, providers, config);
        }
        providers.getReplacements().closeSnippetRegistration();
        providers.getReplacements().getGraphBuilderPlugins().getInvocationPlugins().maybePrintIntrinsics(options);
    }

    protected CallingConvention makeCallingConvention(StructuredGraph graph, Stub stub) {
        if (stub != null) {
            return stub.getLinkage().getIncomingCallingConvention();
        }

        // TODO: is there a better way for this?
        CallingConvention cc;
        if (graph.isEntryPointCFG()) {
            Signature sig = graph.method().getSignature();
            JavaType retType = sig.getReturnType(null);
            RegisterConfig registerConfig = getCodeCache().getRegisterConfig();
            cc = registerConfig.getCallingConvention(HotSpotCallingConventionType.JavaCallee, retType, graph.getEntryPointOriginalParameterTypes().toArray(new JavaType[0]), this);

            for (int i = 0; i < cc.getArguments().length; i++) {
                Value dst = cc.getArgument(i);
                if (ValueUtil.isStackSlot(dst)) {
                    StackSlot slot = ValueUtil.asStackSlot(dst);
                    slot.setOldArgument(true);
                }
            }
            return cc;
        }
        if (getProviders().getValhallaOptionsProvider().callingConventionEnabled()) {
            cc = GraalValhallaServices.getValhallaCallingConvention(getCodeCache(), HotSpotCallingConventionType.JavaCallee, graph.method(), this, true);
        } else {
            cc = getCallingConvention(getCodeCache(),
                            HotSpotCallingConventionType.JavaCallee, graph.method(), this);
        }

        if (graph.getEntryBCI() != JVMCICompiler.INVOCATION_ENTRY_BCI) {
            // for OSR, only a pointer is passed to the method.
            JavaType[] parameterTypes = new JavaType[]{getMetaAccess().lookupJavaType(long.class)};
            CallingConvention tmp = getCodeCache().getRegisterConfig().getCallingConvention(HotSpotCallingConventionType.JavaCallee, getMetaAccess().lookupJavaType(void.class), parameterTypes, this);
            cc = new CallingConvention(cc.getStackSize(), cc.getReturn(), tmp.getArgument(0));
        }
        return cc;
    }

    public void emitStackOverflowCheck(CompilationResultBuilder crb) {
        /*
         * Each code entry causes one stack bang n pages down the stack where n is configurable by
         * StackShadowPages. The setting depends on the maximum depth of VM call stack or native
         * before going back into java code, since only java code can raise a stack overflow
         * exception using the stack banging mechanism. The VM and native code does not detect stack
         * overflow. The code in JavaCalls::call() checks that there is at least n pages available,
         * so all entry code needs to do is bang once for the end of this shadow zone. The entry
         * code may need to bang additional pages if the framesize is greater than a page.
         */

        int pageSize = config.vmPageSize;
        int bangEnd = NumUtil.roundUp(config.stackShadowPages * 4 * K, pageSize);

        // This is how far the previous frame's stack banging extended.
        int bangEndSafe = bangEnd;

        int frameSize = Math.max(crb.frameMap.frameSize(), crb.compilationResult.getMaxInterpreterFrameSize());
        if (frameSize > pageSize) {
            bangEnd += frameSize;
        }

        int bangOffset = bangEndSafe;
        if (bangOffset <= bangEnd) {
            crb.blockComment("[stack overflow check]");
        }
        while (bangOffset <= bangEnd) {
            // Need at least one stack bang at end of shadow zone.
            bangStackWithOffset(crb, bangOffset);
            bangOffset += pageSize;
        }
    }

    protected abstract void bangStackWithOffset(CompilationResultBuilder crb, int bangOffset);

    @Override
    public ReferenceMapBuilder newReferenceMapBuilder(int totalFrameSize) {
        int uncompressedReferenceSize = getTarget().arch.getPlatformKind(JavaKind.Object).getSizeInBytes();
        return new HotSpotReferenceMapBuilder(totalFrameSize, config.maxOopMapStackOffset, uncompressedReferenceSize);
    }


    @Override
    public LIRGenerationResult newLIRGenerationResult(CompilationIdentifier compilationId, LIR lir, RegisterAllocationConfig registerAllocationConfig, StructuredGraph graph, Object stub) {
        FrameMapBuilder builder;
        boolean isEntryPoint = graph.isEntryPointCFG();
        if (graph.isEntryPointCFG()) {
            builder = newEntryPointFrameMapBuilder(registerAllocationConfig.getRegisterConfig(), graph.method());
        } else {
            builder = newFrameMapBuilderWithStackRepair(registerAllocationConfig.getRegisterConfig(), (Stub) stub, graph.method());
        }
        return new HotSpotLIRGenerationResult(compilationId, lir, builder,
                        registerAllocationConfig,
                        makeCallingConvention(graph, (Stub) stub), (Stub) stub, config.requiresReservedStackCheck(graph.getMethods()), isEntryPoint);
    }

    protected abstract FrameMapBuilder newFrameMapBuilder(RegisterConfig registerConfig, Stub stub);

    /**
     * Extends the stack if necessary and scalarizes all value class args. See
     * {@code MacroAssembler::unpack_inline_args}
     */
    public boolean scalarizeValueObjects(ResolvedJavaMethod rootMethod, CompilationResultBuilder crb, RegisterConfig regConfig, boolean receiverOnly) {

        Assembler<?> asm = crb.asm;
        // VIEP: nothing scalarized yet
        // VIEP_RO: everything except receiver already scalarized
        JavaType[] currentParameterTypes = receiverOnly ? GraalValhallaServices.getScalarizedParameters(rootMethod, false).toArray(new JavaType[0])
                        : rootMethod.getSignature().toParameterTypes(rootMethod.isStatic() ? null : rootMethod.getDeclaringClass());
        CallingConvention currentCC = regConfig.getCallingConvention(HotSpotCallingConventionType.JavaCallee, null, currentParameterTypes, this);

        // VEP: the parameters that are expected
        JavaType[] expectedParameterTypes = GraalValhallaServices.getScalarizedParameters(rootMethod, true).toArray(new JavaType[0]);
        CallingConvention expectedCC = regConfig.getCallingConvention(HotSpotCallingConventionType.JavaCallee, null, expectedParameterTypes, this);

        int currentStackSizeArguments = currentCC.getStackSize(); /* sig args on stack */
        int expectedStackSizeArguments = expectedCC.getStackSize(); /* sig_cc args on stack */

        boolean performedStackExtension = false;
        if (expectedStackSizeArguments > currentStackSizeArguments) {
            entryPointStackExtension(crb);
            performedStackExtension = true;
        }
        byte[] installedCode = ValhallaEntryPointCreator.create(getRuntime().getOptions(), getProviders(), rootMethod).getCode(getRuntime().getHostBackend(),
                        receiverOnly);
        for (int i = 0; i < installedCode.length; i++) {
            asm.emitByte(installedCode[i]);
        }
        return performedStackExtension;
    }

    /**
     * For extending the stack in case the VEP has a larger stack size than the VIEP or VIEP_RO.
     */
    public void entryPointStackExtension(CompilationResultBuilder crb) {
        throw new UnsupportedOperationException("stack extension must be implemented");
    }

    /**
     *
     * Helper function to emit an unverified or verified entry.
     */
    private void emitEntryPoint(ResolvedJavaMethod rootMethod, CompilationResultBuilder crb, RegisterConfig regConfig, HotSpotMarkId markId, Label verifiedEntry, HotSpotMarkId additionalMarkId) {
        Assembler<?> asm = crb.asm;
        crb.recordMark(markId);
        if (additionalMarkId != null) {
            crb.recordMark(additionalMarkId);
        }
        if (!markId.isVerifiedEntryPoint()) {
            icCheck(rootMethod, crb, regConfig, markId, additionalMarkId);
        } else {
            // create dummy frame
            crb.frameContext.enter(crb, 0, true);
            crb.frameContext.leave(crb, false);
            boolean performedStackExtension = scalarizeValueObjects(rootMethod, crb, regConfig, markId == HotSpotMarkId.VERIFIED_INLINE_ENTRY_RO);

            // create real entry point frame
            HotSpotFrameMap frameMap = (HotSpotFrameMap) crb.frameMap;
            crb.frameContext.enter(crb, performedStackExtension ? frameMap.getStackIncrement() : 0, false);
            asm.jmp(verifiedEntry);
        }
        asm.align(config.codeEntryAlignment);
    }

    private void emitEntryPoint(ResolvedJavaMethod rootMethod, CompilationResultBuilder crb, RegisterConfig refConfig, HotSpotMarkId markId, Label verifiedEntry) {
        emitEntryPoint(rootMethod, crb, refConfig, markId, verifiedEntry, null);
    }

    protected void emitCodeHelper(CompilationResultBuilder crb, ResolvedJavaMethod installedCodeOwner, EntryPointDecorator entryPointDecorator) {
        // TODO: update in subclasses
        throw new UnsupportedOperationException("emit code helper is not implemented");
    }

    protected void icCheck(ResolvedJavaMethod rootMethod, CompilationResultBuilder crb, RegisterConfig regConfig, HotSpotMarkId markId, HotSpotMarkId additionalMarkId) {
        // TODO: just make the implementation in the subclasses common in here
        throw new UnsupportedOperationException("ic check is not implemented");
    }

    /**
     * Emits the code prior to the verified entry point.
     *
     * @param installedCodeOwner see {@link LIRGenerationProvider#emitCode}
     */

    // @formatter:off
    // The entry points of JVMCI-compiled methods can have the following types:
    //
    // (1) Methods with no inline type arguments
    // (2) Methods with an inline type receiver but no inline type arguments
    //     VIEP_RO is the same as VIEP
    // (3) Methods with a non-inline type receiver and some inline type arguments
    //     VIEP_RO is the same as VEP
    // (4) Methods with an inline type receiver and other inline type arguments
    //     Separate VEP, VIEP, and VIEP_RO

    //
    // (1)               (2)                 (3)                    (4)
    // UEP/UIEP:         UEP/UIEP:           UIEP:                  UEP:
    //   check_icache      check_icache       check_icache           check_icache
    // VEP/VIEP/VIEP_RO  VIEP/VIEP_RO:       VIEP:                  VIEP_RO:
    //   body              unpack receiver    unpack inline args     unpack receiver
    //                     jump to VEP        jump to VEP            jump to VEP
    //                   VEP                 UEP:                   UIEP:
    //                     body                check_icache           check_icache
    //                                       VEP/VIEP_RO:           VIEP:
    //                                         body                   unpack all inline args
    //                                                                jump to VEP
    //                                                              VEP:
    //                                                                body
    // @formatter:on
    public Label emitCodePrefix(ResolvedJavaMethod installedCodeOwner, CompilationResultBuilder crb, RegisterConfig regConfig) {

        boolean verifiedInlineSet = false;
        boolean verifiedInlineROSet = false;
        boolean unverifiedSet = false;
        boolean unverifiedInlineSet = false;

        Label verifiedEntry = new Label();
        if (installedCodeOwner != null) {
            if (crb.compilationResult.getEntryBCI() == -1 && GraalValhallaServices.hasScalarizedParameters(installedCodeOwner)) {
                // we have parameters that need to be scalarized
                if (!installedCodeOwner.isStatic()) {

                    if (GraalValhallaServices.hasScalarizedReceiver(installedCodeOwner)) {
                        // additional entry points for receiver

                        if (GraalValhallaServices.getScalarizedParametersCount(installedCodeOwner) == 1) {
                            // case (2)

                            // only receiver needs to be scalarized share entries

                            /*
                             * unverified and no parameter scalarized, falls through to
                             * VERIFIED_INLINE_ENTRY
                             */
                            emitEntryPoint(installedCodeOwner, crb, regConfig,
                                            HotSpotMarkId.INLINE_ENTRY, null, HotSpotMarkId.UNVERIFIED_ENTRY);

                            /*
                             * verified but no parameter scalarized yet, produce code that
                             * scalarizes all of them and jump to verified entry after its frame
                             * enter
                             */
                            emitEntryPoint(installedCodeOwner, crb, regConfig,
                                            HotSpotMarkId.VERIFIED_INLINE_ENTRY, verifiedEntry, HotSpotMarkId.VERIFIED_INLINE_ENTRY_RO);
                        } else {
                            // case (4)

                            // receiver specific entry needed

                            /*
                             * unverified and no parameter scalarized, falls through to
                             * VERIFIED_INLINE_ENTRY
                             */
                            emitEntryPoint(installedCodeOwner, crb, regConfig,
                                            HotSpotMarkId.INLINE_ENTRY, null);

                            /*
                             * verified but no parameter scalarized yet, produce code that
                             * scalarizes all of them and jump to verified entry after its frame
                             * enter
                             */
                            emitEntryPoint(installedCodeOwner, crb, regConfig,
                                            HotSpotMarkId.VERIFIED_INLINE_ENTRY, verifiedEntry);

                            /*
                             * unverified and all parameters except receiver scalarized, falls
                             * through to VERIFIED_INLINE_ENTRY_RO
                             */
                            emitEntryPoint(installedCodeOwner, crb, regConfig,
                                            HotSpotMarkId.UNVERIFIED_ENTRY, null);

                            /*
                             * verified and all parameters except receiver scalarized, produce code
                             * that scalarizes the receiver and jump to verified entry after its
                             * frame enter
                             */
                            emitEntryPoint(installedCodeOwner, crb, regConfig,
                                            HotSpotMarkId.VERIFIED_INLINE_ENTRY_RO, verifiedEntry);
                        }

                        verifiedInlineSet = true;
                        verifiedInlineROSet = true;
                        unverifiedSet = true;
                        unverifiedInlineSet = true;
                    } else {
                        // case (3)

                        // no entry points specific for receiver needed

                        /*
                         * unverified and no parameter scalarized, falls through to
                         * VERIFIED_INLINE_ENTRY
                         */
                        emitEntryPoint(installedCodeOwner, crb, regConfig,
                                        HotSpotMarkId.INLINE_ENTRY, null);

                        /*
                         * verified but no parameter scalarized yet, produce code that scalarizes
                         * all of them and jump to verified entry after its frame enter
                         */
                        emitEntryPoint(installedCodeOwner, crb, regConfig,
                                        HotSpotMarkId.VERIFIED_INLINE_ENTRY, verifiedEntry);

                        /*
                         * unverified and all parameters scalarized, falls through to the verified
                         * entry
                         */
                        emitEntryPoint(installedCodeOwner, crb, regConfig,
                                        HotSpotMarkId.UNVERIFIED_ENTRY, null);
                        verifiedInlineSet = true;
                        verifiedInlineROSet = false;
                        unverifiedSet = true;
                        unverifiedInlineSet = true;
                    }

                } else {
                    // static method, no receiver specific entry points needed

                    // verified but no parameter scalarized yet, produce code that scalarizes
                    // all of them and jump to verified entry after its frame enter
                    emitEntryPoint(installedCodeOwner, crb, regConfig,
                                    HotSpotMarkId.VERIFIED_INLINE_ENTRY, verifiedEntry);
                    verifiedInlineSet = true;
                    // new ValhallaEntryPointCreator(getRuntime().getOptions(), getProviders(),
                    // installedCodeOwner).getCode(getRuntime().getHostBackend(), null);
                }
            } else if (!installedCodeOwner.isStatic()) {
                // case (1)

                // no additional entry points needed
                if (VALHALLA_JDK) {
                    emitEntryPoint(installedCodeOwner, crb, regConfig,
                                    HotSpotMarkId.UNVERIFIED_ENTRY, null, HotSpotMarkId.INLINE_ENTRY);
                    unverifiedInlineSet = true;
                } else {
                    emitEntryPoint(installedCodeOwner, crb, regConfig,
                                    HotSpotMarkId.UNVERIFIED_ENTRY, null);
                }
                unverifiedSet = true;
            }
        }

        if (crb.compilationResult.getEntryBCI() != -1) {
            crb.recordMark(HotSpotMarkId.OSR_ENTRY);
            return null;

        } else {
            // set entry points (if not set yet) to verified entry point
            if (!unverifiedSet) {
                crb.recordMark(HotSpotMarkId.UNVERIFIED_ENTRY);
            }
            if (VALHALLA_JDK) {
                if (!unverifiedInlineSet) {
                    crb.recordMark(HotSpotMarkId.INLINE_ENTRY);
                }
                if (!verifiedInlineSet) {
                    crb.recordMark(HotSpotMarkId.VERIFIED_INLINE_ENTRY);
                }
                if (!verifiedInlineROSet) {
                    crb.recordMark(HotSpotMarkId.VERIFIED_INLINE_ENTRY_RO);
                }
            }

            // record the normal entry point
            crb.recordMark(HotSpotMarkId.VERIFIED_ENTRY);
            return verifiedEntry;
        }
    }

    protected FrameMapBuilder newEntryPointFrameMapBuilder(RegisterConfig registerConfig, ResolvedJavaMethod targetMethod) {
        throw new UnsupportedOperationException("entry point frame map builder implemented");
    }

    protected FrameMapBuilder newFrameMapBuilderWithStackRepair(RegisterConfig registerConfig, Stub stub, ResolvedJavaMethod rootMethod) {
        throw new UnsupportedOperationException("entry point frame map builder implemented");
    }
}
