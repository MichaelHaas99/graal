/*
 * Copyright (c) 2021, 2025, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.jfr;

import java.util.List;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.jdk.JDK21OrEarlier;
import com.oracle.svm.core.util.VMError;

@TargetClass(value = jdk.jfr.internal.SecuritySupport.class, onlyWith = {HasJfrSupport.class, JDK21OrEarlier.class})
@SuppressWarnings("unused")
public final class Target_jdk_jfr_internal_SecuritySupport_JDK21 {

    @Substitute
    public static List<Target_jdk_jfr_internal_SecuritySupport_SafePath_JDK21> getPredefinedJFCFiles() {
        throw VMError.shouldNotReachHere("Paths from the image build must not be embedded into the Native Image.");
    }

    @Alias
    static native Target_jdk_jfr_internal_SecuritySupport_SafePath_JDK21 getPathInProperty(String prop, String subPath);
}

@TargetClass(className = "jdk.jfr.internal.SecuritySupport$SafePath", onlyWith = {HasJfrSupport.class, JDK21OrEarlier.class})
@SuppressWarnings("unused")
final class Target_jdk_jfr_internal_SecuritySupport_SafePath_JDK21 {
    @Alias
    Target_jdk_jfr_internal_SecuritySupport_SafePath_JDK21(String path) {
    }
}
