/*
 * Copyright (c) 2012, 2021, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.lir.asm;

/**
 * Code for managing a method's native frame.
 */
public interface FrameContext {

    /**
     * Emits code common to all entry points of a method. This may include:
     * <ul>
     * <li>setting up the stack frame</li>
     * <li>saving callee-saved registers</li>
     * <li>stack overflow checking</li>
     * <li>adding marks to identify the frame push</li>
     * </ul>
     */
    void enter(CompilationResultBuilder crb);

    /**
     * Same as {@link FrameContext#enter(CompilationResultBuilder)} but additionally allows to set a
     * {@code stackIncrement} which will be stored directly under the rbp and will be used on method
     * leave to repair the stack. Entry points other than the verified entry point need to emit a
     * dummy frame because the nmethod entry barrier is expected to be at the same offset for all
     * entry points.
     *
     * @param stackIncrement the stack increment that should be stored under the rbp
     * @param emitEntryBarrier true if the nmethod entry barrier should be emitted
     */
    default void enter(CompilationResultBuilder crb, int stackIncrement, boolean emitEntryBarrier) {
        throw new UnsupportedOperationException("method enter with stack increment not implemented yet");
    }

    /**
     * Emits code to be executed just prior to returning from a method. This may include:
     * <ul>
     * <li>restoring callee-saved registers</li>
     * <li>performing a safepoint</li>
     * <li>destroying the stack frame</li>
     * <li>adding marks to identify the frame pop</li>
     * </ul>
     */
    void leave(CompilationResultBuilder crb);

    /**
     * Same as {@link FrameContext#leave(CompilationResultBuilder)} but with an additional parameter
     * to control if code for stack repair should be emitted. E.g. For the dummy frames needed for
     * the nmethod entry barrier the stack increment is known to be zero so no stack repair code
     * needs to be emitted.
     *
     * @param allowStackRepair indicates if stack repair is allowed when leaving a method
     */
    default void leave(CompilationResultBuilder crb, boolean allowStackRepair) {
        throw new UnsupportedOperationException("method leave with stack repair control not implemented yet");
    }

    /**
     * Allows the frame context to track the point at which a return has been generated. This
     * callback is not intended to actually generate the return instruction itself. A legitimate
     * action in response to this call may include:
     * <ul>
     * <li>adding a mark to identify the end of an epilogue</li>
     * </ul>
     */
    void returned(CompilationResultBuilder crb);
}
