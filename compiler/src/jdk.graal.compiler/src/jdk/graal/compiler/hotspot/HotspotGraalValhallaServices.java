package jdk.graal.compiler.hotspot;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import jdk.vm.ci.hotspot.HotSpotObjectConstant;
import jdk.vm.ci.hotspot.HotSpotResolvedObjectType;

public class HotspotGraalValhallaServices {
    private static final Method methodPayloadOffset;

    static {
        Method payloadOffset = null;
        try {
            payloadOffset = HotSpotResolvedObjectType.class.getDeclaredMethod("payloadOffset");
        } catch (NoSuchMethodException e) {
        }
        methodPayloadOffset = payloadOffset;
    }

    /**
     * Calls {@code HotSpotResolvedObjectType.payloadOffset()}.
     */
    public static int payloadOffset(HotSpotResolvedObjectType type) {
        if (methodPayloadOffset != null) {
            try {
                try {
                    return (int) methodPayloadOffset.invoke(type);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }
        return -1;
    }

    private static final Method methodConvertToFlatArray;

    static {
        Method convertToFlatArray = null;
        try {
            convertToFlatArray = HotSpotResolvedObjectType.class.getDeclaredMethod("convertToFlatArray");
        } catch (NoSuchMethodException e) {
        }
        methodConvertToFlatArray = convertToFlatArray;
    }

    /**
     * Calls {@code HotSpotResolvedObjectType.convertToFlatArray()}.
     */
    public static HotSpotResolvedObjectType convertToFlatArray(HotSpotResolvedObjectType type) {
        if (methodConvertToFlatArray != null) {
            try {
                try {
                    return (HotSpotResolvedObjectType) methodConvertToFlatArray.invoke(type);
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

    private static final Method methodGetLog2ComponentSize;

    static {
        Method getLog2ComponentSize = null;
        try {
            getLog2ComponentSize = HotSpotResolvedObjectType.class.getDeclaredMethod("getLog2ComponentSize");
        } catch (NoSuchMethodException e) {
        }
        methodGetLog2ComponentSize = getLog2ComponentSize;
    }

    /**
     * Calls {@code HotSpotResolvedObjectType.getLog2ComponentSize()}.
     */
    public static int getLog2ComponentSize(HotSpotResolvedObjectType type) {
        if (methodGetLog2ComponentSize != null) {
            try {
                try {
                    return (int) methodGetLog2ComponentSize.invoke(type);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new InternalError(throwable);
            }
        }
        return -1;
    }

    private static final Method methodObjectIsInlineType;

    static {
        Method objectIsInlineType = null;
        try {
            objectIsInlineType = HotSpotObjectConstant.class.getDeclaredMethod("objectIsInlineType");
        } catch (NoSuchMethodException e) {
        }
        methodObjectIsInlineType = objectIsInlineType;
    }

    /**
     * Calls {@code HotSpotObjectConstant.objectIsInlineType()}.
     */
    public static boolean objectIsInlineType(HotSpotObjectConstant method) {
        if (methodObjectIsInlineType != null) {
            try {
                try {
                    return (boolean) methodObjectIsInlineType.invoke(method);
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

}
