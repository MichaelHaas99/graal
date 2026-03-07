/*
 * Copyright (c) 2019, 2021, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.serviceprovider;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import jdk.vm.ci.code.CallingConvention;
import jdk.vm.ci.code.CodeCacheProvider;
import jdk.vm.ci.code.CodeUtil;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.RegisterConfig;
import jdk.vm.ci.code.ValueKindFactory;
import jdk.vm.ci.code.VirtualObject;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.JavaValue;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ProfilingInfo;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.Value;

/**
 * Interface to HotSpot specific functionality that abstracts over if Graal is running on the
 * Valhalla JDK.
 */
public class GraalValhallaServices {
    private static final Method methodIsScalarizedParameter;

    static {
        Method isScalarizedParameter = null;

        try {
            isScalarizedParameter = ResolvedJavaMethod.class.getDeclaredMethod("isScalarizedParameter", int.class, boolean.class);
        } catch (NoSuchMethodException e) {
        }

        methodIsScalarizedParameter = isScalarizedParameter;
    }

    /**
     * Calls {@code ResolvedJavaMethod.isScalarizedParameter(int, boolean)}.
     */
    public static boolean isScalarizedParameter(ResolvedJavaMethod method, int index, boolean indexIncludesReceiverIfExists) {
        if (methodIsScalarizedParameter != null) {
            try {
                try {
                    return (boolean) methodIsScalarizedParameter.invoke(method, index, indexIncludesReceiverIfExists);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }
        return false;
    }

    private static final Method methodIsParameterNullFree;

    static {
        Method isParameterNullFree = null;
        try {
            isParameterNullFree = ResolvedJavaMethod.class.getDeclaredMethod("isParameterNullFree", int.class, boolean.class);
        } catch (NoSuchMethodException e) {
        }
        methodIsParameterNullFree = isParameterNullFree;
    }

    /**
     * Calls {@code ResolvedJavaMethod.isParameterNullFree(int, boolean)}.
     */
    public static boolean isParameterNullFree(ResolvedJavaMethod method, int index, boolean indexIncludesReceiverIfExists) {
        if (methodIsParameterNullFree != null) {
            try {
                try {
                    return (boolean) methodIsParameterNullFree.invoke(method, index, indexIncludesReceiverIfExists);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }
        return false;
    }

    private static final Method methodGetScalarizedParametersCount;

    static {
        Method getScalarizedParametersCount = null;
        try {
            getScalarizedParametersCount = ResolvedJavaMethod.class.getDeclaredMethod("getScalarizedParametersCount");
        } catch (NoSuchMethodException e) {
        }
        methodGetScalarizedParametersCount = getScalarizedParametersCount;
    }

    /**
     * Calls {@code ResolvedJavaMethod.getScalarizedParametersCount()}.
     */
    public static int getScalarizedParametersCount(ResolvedJavaMethod method) {
        if (methodGetScalarizedParametersCount != null) {
            try {
                try {
                    return (int) methodGetScalarizedParametersCount.invoke(method);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }
        return 0;
    }

    private static final Method methodHasScalarizedParameters;

    static {
        Method hasScalarizedParameters = null;
        try {
            hasScalarizedParameters = ResolvedJavaMethod.class.getDeclaredMethod("hasScalarizedParameters");
        } catch (NoSuchMethodException e) {
        }
        methodHasScalarizedParameters = hasScalarizedParameters;
    }

    /**
     * Calls {@code ResolvedJavaMethod.hasScalarizedParameters()}.
     */
    public static boolean hasScalarizedParameters(ResolvedJavaMethod method) {
        if (methodHasScalarizedParameters != null) {
            try {
                try {
                    return (boolean) methodHasScalarizedParameters.invoke(method);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }
        return false;
    }

    private static final Method methodHasScalarizedReturn;

    static {
        Method hasScalarizedReturn = null;
        try {
            hasScalarizedReturn = ResolvedJavaMethod.class.getDeclaredMethod("hasScalarizedReturn");
        } catch (NoSuchMethodException e) {
        }
        methodHasScalarizedReturn = hasScalarizedReturn;
    }

    /**
     * Calls {@code ResolvedJavaMethod.hasScalarizedReturn()}.
     */
    public static boolean hasScalarizedReturn(ResolvedJavaMethod method) {
        if (methodHasScalarizedReturn != null) {
            try {
                try {
                    return (boolean) methodHasScalarizedReturn.invoke(method);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }
        return false;
    }

    private static final Method methodHasScalarizedReceiver;

    static {
        Method hasScalarizedReceiver = null;
        try {
            hasScalarizedReceiver = ResolvedJavaMethod.class.getDeclaredMethod("hasScalarizedReceiver");
        } catch (NoSuchMethodException e) {
        }
        methodHasScalarizedReceiver = hasScalarizedReceiver;
    }

    /**
     * Calls {@code ResolvedJavaMethod.hasScalarizedReceiver()}.
     */
    public static boolean hasScalarizedReceiver(ResolvedJavaMethod method) {
        if (methodHasScalarizedReceiver != null) {
            try {
                try {
                    return (boolean) methodHasScalarizedReceiver.invoke(method);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }
        return false;
    }

    private static final Method methodHasCallingConventionMismatch;

    static {
        Method hasCallingConventionMismatch = null;
        try {
            hasCallingConventionMismatch = ResolvedJavaMethod.class.getDeclaredMethod("hasCallingConventionMismatch");
        } catch (NoSuchMethodException e) {
        }
        methodHasCallingConventionMismatch = hasCallingConventionMismatch;
    }

    /**
     * Calls {@code ResolvedJavaMethod.hasCallingConventionMismatch()}.
     */
    public static boolean hasCallingConventionMismatch(ResolvedJavaMethod method) {
        if (methodHasCallingConventionMismatch != null) {
            try {
                try {
                    return (boolean) methodHasCallingConventionMismatch.invoke(method);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }
        return false;
    }

    private static final Method methodGetScalarizedReturn;

    static {
        Method getScalarizedReturn = null;
        try {
            getScalarizedReturn = ResolvedJavaMethod.class.getDeclaredMethod("getScalarizedReturn");
        } catch (NoSuchMethodException e) {
        }
        methodGetScalarizedReturn = getScalarizedReturn;
    }

    /**
     * Calls {@code ResolvedJavaMethod.getScalarizedReturn()}.
     */
    @SuppressWarnings("unchecked")
    public static List<JavaType> getScalarizedReturn(ResolvedJavaMethod method) {
        if (methodGetScalarizedReturn != null) {
            try {
                try {
                    return (List<JavaType>) methodGetScalarizedReturn.invoke(method);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }
        return null;
    }

    private static final Method methodGetScalarizedReceiver;

    static {
        Method getScalarizedReceiver = null;
        try {
            getScalarizedReceiver = ResolvedJavaMethod.class.getDeclaredMethod("getScalarizedReceiver");
        } catch (NoSuchMethodException e) {
        }
        methodGetScalarizedReceiver = getScalarizedReceiver;
    }

    /**
     * Calls {@code ResolvedJavaMethod.getScalarizedReceiver()}.
     */
    @SuppressWarnings("unchecked")
    public static List<JavaType> getScalarizedReceiver(ResolvedJavaMethod method) {
        if (methodGetScalarizedReceiver != null) {
            try {
                try {
                    return (List<JavaType>) methodGetScalarizedReceiver.invoke(method);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }
        return null;
    }

    private static final Method methodGetScalarizedParameters;

    static {
        Method getScalarizedParameters = null;
        try {
            getScalarizedParameters = ResolvedJavaMethod.class.getDeclaredMethod("getScalarizedParameters", boolean.class);
        } catch (NoSuchMethodException e) {
        }
        methodGetScalarizedParameters = getScalarizedParameters;
    }

    /**
     * Calls {@code ResolvedJavaMethod.getScalarizedParameters(boolean)}.
     */
    @SuppressWarnings("unchecked")
    public static List<JavaType> getScalarizedParameters(ResolvedJavaMethod method, boolean scalarizeReceiver) {
        if (methodGetScalarizedParameters != null) {
            try {
                try {
                    return (List<JavaType>) methodGetScalarizedParameters.invoke(method, scalarizeReceiver);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }
        return null;
    }

    private static final Method methodGetScalarizedParameter;

    static {
        Method getScalarizedParameter = null;
        try {
            getScalarizedParameter = ResolvedJavaMethod.class.getDeclaredMethod("getScalarizedParameter", int.class, boolean.class);
        } catch (NoSuchMethodException e) {
        }
        methodGetScalarizedParameter = getScalarizedParameter;
    }

    /**
     * Calls {@code ResolvedJavaMethod.getScalarizedParameter(int, boolean)}.
     */
    @SuppressWarnings("unchecked")
    public static List<JavaType> getScalarizedParameter(ResolvedJavaMethod method, int index, boolean indexIncludesReceiverIfExists) {
        if (methodGetScalarizedParameter != null) {
            try {
                try {
                    return (List<JavaType>) methodGetScalarizedParameter.invoke(method, index, indexIncludesReceiverIfExists);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }
        return null;
    }

    private static final Method methodGetScalarizedParameterNullFree;

    static {
        Method getScalarizedParameterNullFree = null;
        try {
            getScalarizedParameterNullFree = ResolvedJavaMethod.class.getDeclaredMethod("getScalarizedParameterNullFree", int.class, boolean.class);
        } catch (NoSuchMethodException e) {
        }
        methodGetScalarizedParameterNullFree = getScalarizedParameterNullFree;
    }

    /**
     * Calls {@code ResolvedJavaMethod.getScalarizedParameterNullFree(int, boolean)}.
     */
    @SuppressWarnings("unchecked")
    public static List<JavaType> getScalarizedParameterNullFree(ResolvedJavaMethod method, int index, boolean indexIncludesReceiverIfExists) {
        if (methodGetScalarizedParameterNullFree != null) {
            try {
                try {
                    return (List<JavaType>) methodGetScalarizedParameterNullFree.invoke(method, index, indexIncludesReceiverIfExists);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }
        return null;
    }

    private static final Method methodGetScalarizedParameterFields;

    static {
        Method getScalarizedParameterFields = null;
        try {
            getScalarizedParameterFields = ResolvedJavaMethod.class.getDeclaredMethod("getScalarizedParameterFields", int.class, boolean.class);
        } catch (NoSuchMethodException e) {
        }
        methodGetScalarizedParameterFields = getScalarizedParameterFields;
    }

    /**
     * Calls {@code ResolvedJavaMethod.getScalarizedParameterFields(int, boolean)}.
     */
    @SuppressWarnings("unchecked")
    public static List<ResolvedJavaField> getScalarizedParameterFields(ResolvedJavaMethod method, int index, boolean indexIncludesReceiverIfExists) {
        if (methodGetScalarizedParameterFields != null) {
            try {
                try {
                    return (List<ResolvedJavaField>) methodGetScalarizedParameterFields.invoke(method, index, indexIncludesReceiverIfExists);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }
        return null;
    }

    private static final Method methodGetScalarizedParameterNonNullType;

    static {
        Method getScalarizedParameterNonNullType = null;
        try {
            getScalarizedParameterNonNullType = ResolvedJavaMethod.class.getDeclaredMethod("getScalarizedParameterNonNullType", int.class, boolean.class);
        } catch (NoSuchMethodException e) {
        }
        methodGetScalarizedParameterNonNullType = getScalarizedParameterNonNullType;
    }

    /**
     * Calls {@code ResolvedJavaMethod.getScalarizedParameterNonNullType(int, boolean)}.
     */
    public static JavaType getScalarizedParameterNonNullType(ResolvedJavaMethod method, int index, boolean indexIncludesReceiverIfExists) {
        if (methodGetScalarizedParameterNonNullType != null) {
            try {
                try {
                    return (JavaType) methodGetScalarizedParameterNonNullType.invoke(method, index, indexIncludesReceiverIfExists);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }
        return null;
    }

    private static final Method methodGetFlatArrayClass;

    static {
        Method getFlatArrayClass = null;
        try {
            getFlatArrayClass = ResolvedJavaType.class.getDeclaredMethod("getFlatArrayClass");
        } catch (NoSuchMethodException e) {
        }
        methodGetFlatArrayClass = getFlatArrayClass;
    }

    /**
     * Calls {@code ResolvedJavaMethod.getFlatArrayClass()}.
     */
    public static ResolvedJavaType getFlatArrayClass(ResolvedJavaType type) {
        if (methodGetFlatArrayClass != null) {
            try {
                try {
                    return (ResolvedJavaType) methodGetFlatArrayClass.invoke(type);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }
        return null;
    }

    private static final Method methodIsIdentity;

    static {
        Method isIdentity = null;
        try {
            isIdentity = ResolvedJavaType.class.getDeclaredMethod("isIdentity");
        } catch (NoSuchMethodException e) {
        }
        methodIsIdentity = isIdentity;
    }

    /**
     * Calls {@code ResolvedJavaType.isIdentity()}.
     */
    public static boolean isIdentity(ResolvedJavaType type) {
        if (methodIsIdentity != null) {
            try {
                try {
                    return (boolean) methodIsIdentity.invoke(type);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }
        return true;
    }

    private static final Method methodGetNullMarkerField;

    static {
        Method getNullMarkerField = null;
        try {
            getNullMarkerField = ResolvedJavaField.class.getDeclaredMethod("getNullMarkerField");
        } catch (NoSuchMethodException e) {
        }
        methodGetNullMarkerField = getNullMarkerField;
    }

    /**
     * Calls {@code ResolvedJavaField.getNullMarkerField()}.
     */
    public static ResolvedJavaField getNullMarkerField(ResolvedJavaField field) {
        if (methodGetNullMarkerField != null) {
            try {
                try {
                    return (ResolvedJavaField) methodGetNullMarkerField.invoke(field);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }
        return null;
    }

    private static final Method methodIsFlat;

    static {
        Method isFlat = null;
        try {
            isFlat = ResolvedJavaField.class.getDeclaredMethod("isFlat");
        } catch (NoSuchMethodException e) {
        }
        methodIsFlat = isFlat;
    }

    /**
     * Calls {@code ResolvedJavaField.isFlat()}.
     */
    public static boolean isFlat(ResolvedJavaField field) {
        if (methodIsFlat != null) {
            try {
                try {
                    return (boolean) methodIsFlat.invoke(field);
                } catch (Throwable u) {
                    return false;
                }
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }
        return false;
    }

    private static final Method methodGetACmpData;

    static {
        Method getACmpData = null;
        try {
            getACmpData = ProfilingInfo.class.getDeclaredMethod("getACmpData", int.class);
        } catch (NoSuchMethodException e) {
        }
        methodGetACmpData = getACmpData;
    }

    /**
     * Calls {@code ProfilingInfo.getACmpData(int)}.
     */
    public static Object getACmpData(ProfilingInfo info, int bci) {
        if (methodGetACmpData != null) {
            try {
                try {
                    return methodGetACmpData.invoke(info, bci);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }
        return null;
    }

    private static final Method methodGetLeft;

    static {
        Method getLeft = null;
        try {
            getLeft = Class.forName("ACmpDataAccessor").getDeclaredMethod("getLeft");
        } catch (Exception e) {
        }
        methodGetLeft = getLeft;
    }

    /**
     * Calls {@code ACmpDataAccessor.getLeft()}.
     */
    public static Object getLeft(Object accessor) {
        if (methodGetLeft != null) {
            try {
                try {
                    return methodGetLeft.invoke(accessor);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }
        return null;
    }

    private static final Method methodGetRight;

    static {
        Method getRight = null;
        try {
            getRight = Class.forName("ACmpDataAccessor").getDeclaredMethod("getRight");
        } catch (Exception e) {
        }
        methodGetRight = getRight;
    }

    /**
     * Calls {@code ACmpDataAccessor.getRight()}.
     */
    public static Object getRight(Object accessor) {
        if (methodGetRight != null) {
            try {
                try {
                    return methodGetRight.invoke(accessor);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }
        return null;
    }

    private static final Method methodAlwaysNull;

    static {
        Method alwaysNull = null;
        try {
            alwaysNull = Class.forName("SingleTypeEntry").getDeclaredMethod("alwaysNull");
        } catch (Exception e) {
        }
        methodAlwaysNull = alwaysNull;
    }

    /**
     * Calls {@code SingleTypeEntry.alwaysNull()}.
     */
    public static boolean getAlwaysNull(Object entry) {
        if (methodAlwaysNull != null) {
            try {
                try {
                    return (boolean) methodAlwaysNull.invoke(entry);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }
        return false;
    }

    private static final Method methodInlineType;

    static {
        Method inlineType = null;
        try {
            inlineType = Class.forName("SingleTypeEntry").getDeclaredMethod("inlineType");
        } catch (Exception e) {
        }
        methodInlineType = inlineType;
    }

    /**
     * Calls {@code SingleTypeEntry.inlineType()}.
     */
    public static boolean getInlineType(Object accessor) {
        if (methodInlineType != null) {
            try {
                try {
                    return (boolean) methodInlineType.invoke(accessor);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }
        return false;
    }

    private static final Method methodGetDefaultInlineTypeInstance;

    static {
        Method getDefaultInlineTypeInstance = null;
        try {
            getDefaultInlineTypeInstance = ResolvedJavaType.class.getDeclaredMethod("getDefaultInlineTypeInstance");
        } catch (NoSuchMethodException e) {
        }
        methodGetDefaultInlineTypeInstance = getDefaultInlineTypeInstance;
    }

    /**
     * Calls {@code ResolvedJavaMethod.getDefaultInlineTypeInstance()}.
     */
    public static JavaConstant getDefaultInlineTypeInstance(ResolvedJavaType type) {
        if (methodGetDefaultInlineTypeInstance != null) {
            try {
                try {
                    return (JavaConstant) methodGetDefaultInlineTypeInstance.invoke(type);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }
        return null;
    }

    private static final Method methodIsNullFreeInlineType;

    static {
        Method isNullFreeInlineType = null;
        try {
            isNullFreeInlineType = ResolvedJavaField.class.getDeclaredMethod("isNullFreeInlineType");
        } catch (NoSuchMethodException e) {
        }
        methodIsNullFreeInlineType = isNullFreeInlineType;
    }

    /**
     * Calls {@code ResolvedJavaField.isNullFreeInlineType()}.
     */
    public static boolean isNullFreeInlineType(ResolvedJavaField field) {
        if (methodIsNullFreeInlineType != null) {
            try {
                try {
                    return (boolean) methodIsNullFreeInlineType.invoke(field);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }
        return false;
    }

    private static final Method methodIsInitialized;

    static {
        Method isInitialized = null;
        try {
            isInitialized = ResolvedJavaField.class.getDeclaredMethod("isInitialized");
        } catch (NoSuchMethodException e) {
        }
        methodIsInitialized = isInitialized;
    }

    /**
     * Calls {@code ResolvedJavaField.isInitialized()}.
     */
    public static boolean isInitialized(ResolvedJavaField field) {
        if (methodIsInitialized != null) {
            try {
                try {
                    return (boolean) methodIsInitialized.invoke(field);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }
        return false;
    }

    private static final Method methodIsFlatArray;

    static {
        Method isFlatArray = null;
        try {
            isFlatArray = ResolvedJavaType.class.getDeclaredMethod("isFlatArray");
        } catch (NoSuchMethodException e) {
        }
        methodIsFlatArray = isFlatArray;
    }

    /**
     * Calls {@code HotSpotResolvedObjectType.isFlatArray()}.
     */
    public static boolean isFlatArray(ResolvedJavaType type) {
        if (methodIsFlatArray != null) {
            try {
                try {
                    return (boolean) methodIsFlatArray.invoke(type);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }
        return false;
    }

    private static final Method methodChangeOffset;

    static {
        Method changeOffset = null;
        try {
            changeOffset = ResolvedJavaField.class.getDeclaredMethod("changeOffset", int.class);
        } catch (NoSuchMethodException e) {
        }
        methodChangeOffset = changeOffset;
    }

    /**
     * Calls {@code ResolvedJavaMethod.changeOffset(int)}.
     */
    public static ResolvedJavaField changeOffset(ResolvedJavaField field, int newOffset) {
        if (methodChangeOffset != null) {
            try {
                try {
                    return (ResolvedJavaField) methodChangeOffset.invoke(field, newOffset);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }
        return null;
    }

    private static final Method methodSetContainerClass;

    static {
        Method setContainerClass = null;
        try {
            setContainerClass = ResolvedJavaField.class.getDeclaredMethod("setContainerClass", ResolvedJavaType.class);
        } catch (NoSuchMethodException e) {
        }
        methodSetContainerClass = setContainerClass;
    }

    /**
     * Calls {@code ResolvedJavaField.setContainerClass(ResolvedJavaType)}.
     */
    public static ResolvedJavaField setContainerClass(ResolvedJavaField field, ResolvedJavaType containerClass) {
        if (methodSetContainerClass != null) {
            try {
                try {
                    return (ResolvedJavaField) methodSetContainerClass.invoke(field, containerClass);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }
        return null;
    }

    private static final Method methodConvertToFlatArray;

    static {
        Method convertToFlatArray = null;
        try {
            convertToFlatArray = ResolvedJavaType.class.getDeclaredMethod("convertToFlatArray");
        } catch (NoSuchMethodException e) {
        }
        methodConvertToFlatArray = convertToFlatArray;
    }

    /**
     * Calls {@code ResolvedJavaType.convertToFlatArray()}.
     */
    public static ResolvedJavaType convertToFlatArray(ResolvedJavaType type) {
        if (methodConvertToFlatArray != null) {
            try {
                try {
                    return (ResolvedJavaType) methodConvertToFlatArray.invoke(type);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }
        return null;
    }

    private static final Method methodGetValhallaCallingConvention;

    static {
        Method getValhallaCallingConvention = null;
        try {
            getValhallaCallingConvention = CodeUtil.class.getDeclaredMethod(
                            "getValhallaCallingConvention",
                            CodeCacheProvider.class,
                            CallingConvention.Type.class,
                            ResolvedJavaMethod.class,
                            ValueKindFactory.class,
                            boolean.class);
        } catch (NoSuchMethodException e) {
        }
        methodGetValhallaCallingConvention = getValhallaCallingConvention;
    }

    /**
     * Calls
     * {@code CodeUtil.getValhallaCallingConvention(CodeCacheProvider, CallingConvention.Type, ResolvedJavaMethod, ValueKindFactory, boolean)}.
     */
    @SuppressWarnings("unchecked")
    public static CallingConvention getValhallaCallingConvention(
                    CodeCacheProvider codeCache,
                    CallingConvention.Type type,
                    ResolvedJavaMethod targetMethod,
                    ValueKindFactory<?> valueKindFactory,
                    boolean scalarizeReceiver) {

        if (methodGetValhallaCallingConvention != null) {
            try {
                try {
                    return (CallingConvention) methodGetValhallaCallingConvention.invoke(null,
                                    codeCache,
                                    type,
                                    targetMethod,
                                    valueKindFactory,
                                    scalarizeReceiver);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }

        return null;
    }

    private static final Method methodGetNonNull;

    static {
        Method getNonNull = null;
        try {
            getNonNull = VirtualObject.class.getDeclaredMethod("getNonNull");
        } catch (NoSuchMethodException e) {
        }
        methodGetNonNull = getNonNull;
    }

    /**
     * Calls {@code VirtualObject.getNonNull()}.
     */
    public static JavaValue[] getNonNull(VirtualObject obj) {
        if (methodGetNonNull != null) {
            try {
                try {
                    return (JavaValue[]) methodGetNonNull.invoke(obj);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }
        return null;
    }

    private static final Method methodSetNonNull;

    static {
        Method setNonNull = null;
        try {
            setNonNull = VirtualObject.class.getDeclaredMethod("setNonNull", JavaValue[].class);
        } catch (NoSuchMethodException e) {
        }
        methodSetNonNull = setNonNull;
    }

    /**
     * Calls {@code VirtualObject.setNonNull(JavaValue[])}.
     */
    public static void setNonNull(VirtualObject obj, JavaValue[] nonNull) {
        if (methodSetNonNull != null) {
            try {
                try {
                    methodSetNonNull.invoke(obj, (Object) nonNull);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }
    }

    private static final Method methodGetReturnRegisters;

    static {
        Method getReturnRegisters = null;
        try {
            getReturnRegisters = RegisterConfig.class.getDeclaredMethod(
                            "getReturnRegisters",
                            JavaKind[].class,
                            boolean.class);
        } catch (NoSuchMethodException e) {
        }
        methodGetReturnRegisters = getReturnRegisters;
    }

    /**
     * Calls {@code RegisterConfig.getReturnRegisters(JavaKind[], boolean)}.
     */
    public static Register[] getReturnRegisters(RegisterConfig config, JavaKind[] kinds, boolean includeFirstGeneralRegister) {
        if (methodGetReturnRegisters != null) {
            try {
                try {
                    return (Register[]) methodGetReturnRegisters.invoke(config, kinds, includeFirstGeneralRegister);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }
        return null;
    }

    private static final Method methodGetReturnConvention;

    static {
        Method getReturnConvention = null;
        try {
            getReturnConvention = RegisterConfig.class.getDeclaredMethod(
                            "getReturnConvention",
                            List.class,
                            ValueKindFactory.class,
                            boolean.class);
        } catch (NoSuchMethodException e) {
        }
        methodGetReturnConvention = getReturnConvention;
    }

    /**
     * Calls {@code RegisterConfig.getReturnConvention(List<JavaType>, ValueKindFactory, boolean)}.
     */
    @SuppressWarnings("unchecked")
    public static List<Value> getReturnConvention(RegisterConfig config, List<JavaType> returnTypes, ValueKindFactory<?> valueKindFactory, boolean includeFirstGeneralRegister) {
        if (methodGetReturnConvention != null) {
            try {
                try {
                    return (List<Value>) methodGetReturnConvention.invoke(config, returnTypes, valueKindFactory, includeFirstGeneralRegister);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }
        return null;
    }

    private static final Method methodGetIsSubstitutabilityMethod;

    static {
        Method m = null;
        try {
            m = MetaAccessProvider.class.getDeclaredMethod("getIsSubstitutableMethod");
        } catch (NoSuchMethodException e) {
        }
        methodGetIsSubstitutabilityMethod = m;
    }

    /**
     * Calls {@code MetaAccessProvider.getSubstitutabilityMethod()}.
     */
    public static ResolvedJavaMethod getIsSubstitutableMethod(MetaAccessProvider metaAccess) {
        if (methodGetIsSubstitutabilityMethod != null) {
            try {
                try {
                    return (ResolvedJavaMethod) methodGetIsSubstitutabilityMethod.invoke(metaAccess);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }
        return null;
    }

    private static final Method methodGetValueObjectHashCodeMethod;

    static {
        Method m = null;
        try {
            m = MetaAccessProvider.class.getDeclaredMethod("getValueObjectHashCodeMethod");
        } catch (NoSuchMethodException e) {
        }
        methodGetValueObjectHashCodeMethod = m;
    }

    /**
     * Calls {@code MetaAccessProvider.getValueObjectHashCodeMethod()}.
     */
    public static ResolvedJavaMethod getValueObjectHashCodeMethod(MetaAccessProvider metaAccess) {
        if (methodGetValueObjectHashCodeMethod != null) {
            try {
                try {
                    return (ResolvedJavaMethod) methodGetValueObjectHashCodeMethod.invoke(metaAccess);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }
        return null;
    }

}
