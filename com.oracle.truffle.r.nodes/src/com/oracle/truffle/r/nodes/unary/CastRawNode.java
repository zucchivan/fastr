/*
 * Copyright (c) 2013, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
package com.oracle.truffle.r.nodes.unary;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.r.runtime.RError;
import com.oracle.truffle.r.runtime.RRuntime;
import com.oracle.truffle.r.runtime.RType;
import com.oracle.truffle.r.runtime.data.RComplex;
import com.oracle.truffle.r.runtime.data.RComplexVector;
import com.oracle.truffle.r.runtime.data.RDataFactory;
import com.oracle.truffle.r.runtime.data.RLogicalVector;
import com.oracle.truffle.r.runtime.data.RMissing;
import com.oracle.truffle.r.runtime.data.RNull;
import com.oracle.truffle.r.runtime.data.RRaw;
import com.oracle.truffle.r.runtime.data.RRawVector;
import com.oracle.truffle.r.runtime.data.RStringVector;
import com.oracle.truffle.r.runtime.data.model.RAbstractDoubleVector;
import com.oracle.truffle.r.runtime.data.model.RAbstractIntVector;
import com.oracle.truffle.r.runtime.data.model.RAbstractListVector;
import com.oracle.truffle.r.runtime.data.model.RAbstractVector;
import com.oracle.truffle.r.runtime.interop.ForeignArray2R;
import com.oracle.truffle.r.runtime.interop.ForeignArray2RNodeGen;
import com.oracle.truffle.r.runtime.ops.na.NACheck;
import com.oracle.truffle.r.runtime.ops.na.NAProfile;

@ImportStatic(RRuntime.class)
public abstract class CastRawNode extends CastBaseNode {

    private final NACheck naCheck = NACheck.create();
    private final BranchProfile warningBranch = BranchProfile.create();

    protected CastRawNode(boolean preserveNames, boolean preserveDimensions, boolean preserveAttributes) {
        this(preserveNames, preserveDimensions, preserveAttributes, false);
    }

    protected CastRawNode(boolean preserveNames, boolean preserveDimensions, boolean preserveAttributes, boolean forRFFI) {
        super(preserveNames, preserveDimensions, preserveAttributes, forRFFI);
    }

    @Child private CastRawNode recursiveCastRaw;

    @Override
    protected final RType getTargetType() {
        return RType.Raw;
    }

    protected Object castRawRecursive(Object o) {
        if (recursiveCastRaw == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            recursiveCastRaw = insert(CastRawNodeGen.create(preserveNames(), preserveDimensions(), preserveAttributes()));
        }
        return recursiveCastRaw.executeRaw(o);
    }

    public abstract Object executeRaw(int o);

    public abstract Object executeRaw(double o);

    public abstract Object executeRaw(byte o);

    public abstract Object executeRaw(Object o);

    @Specialization
    protected RNull doNull(@SuppressWarnings("unused") RNull operand) {
        return RNull.instance;
    }

    @Specialization
    protected RMissing doMissing(@SuppressWarnings("unused") RMissing operand) {
        return RMissing.instance;
    }

    private RRaw checkOutOfRange(int operand, int intResult) {
        if (intResult != operand) {
            warning(RError.Message.OUT_OF_RANGE);
            return RDataFactory.createRaw((byte) 0);
        }
        return RDataFactory.createRaw((byte) intResult);
    }

    @Specialization
    protected RRaw doInt(int operand) {
        int intResult = RRuntime.int2rawIntValue(operand);
        return checkOutOfRange(operand, intResult);
    }

    @Specialization
    protected RRaw doDouble(double operand) {
        int intResult = RRuntime.double2rawIntValue(operand);
        return checkOutOfRange((int) operand, intResult);
    }

    @Specialization
    protected RRaw doComplex(RComplex operand) {
        int intResult = RRuntime.complex2rawIntValue(operand);
        if (operand.getImaginaryPart() != 0) {
            warning(RError.Message.IMAGINARY_PARTS_DISCARDED_IN_COERCION);
        }
        return checkOutOfRange((int) operand.getRealPart(), intResult);
    }

    @Specialization
    protected RRaw doRaw(RRaw operand) {
        return operand;
    }

    @Specialization
    protected RRaw doLogical(byte operand) {
        // need to convert to int so that NA-related warning is caught
        int intVal = RRuntime.logical2int(operand);
        return doInt(intVal);
    }

    @Specialization
    protected RRaw doString(String operand,
                    @Cached("create()") NAProfile naProfile,
                    @Cached("createBinaryProfile()") ConditionProfile emptyStringProfile) {
        int intValue;
        if (naProfile.isNA(operand) || emptyStringProfile.profile(operand.isEmpty())) {
            intValue = 0;
        } else {
            intValue = RRuntime.string2intNoCheck(operand);
            if (RRuntime.isNA(intValue)) {
                warning(RError.Message.NA_INTRODUCED_COERCION);
            }
        }
        int intRawValue = RRuntime.int2rawIntValue(intValue);
        if (intRawValue != intValue) {
            warning(RError.Message.OUT_OF_RANGE);
            return RRaw.valueOf((byte) 0);
        }
        return RRaw.valueOf((byte) intRawValue);
    }

    private RRawVector vectorCopy(RAbstractVector operand, byte[] bdata) {
        RRawVector ret = RDataFactory.createRawVector(bdata, getPreservedDimensions(operand), getPreservedNames(operand));
        preserveDimensionNames(operand, ret);
        if (preserveAttributes()) {
            ret.copyRegAttributesFrom(operand);
        }
        return ret;
    }

    @Specialization
    protected RRawVector doIntVector(RAbstractIntVector operand) {
        int length = operand.getLength();
        byte[] bdata = new byte[length];
        boolean warning = false;
        for (int i = 0; i < length; i++) {
            int intValue = operand.getDataAt(i);
            int intRawValue = RRuntime.int2rawIntValue(intValue);
            if (intRawValue != intValue) {
                warningBranch.enter();
                warning = true;
                intRawValue = 0;
            }
            bdata[i] = (byte) intRawValue;
        }
        if (warning) {
            warning(RError.Message.OUT_OF_RANGE);
        }
        return vectorCopy(operand, bdata);
    }

    @Specialization
    protected RRawVector doLogicalVector(RLogicalVector operand) {
        byte[] bdata = new byte[operand.getLength()];
        boolean warning = false;
        for (int i = 0; i < operand.getLength(); i++) {
            int intVal = RRuntime.logical2int(operand.getDataAt(i));
            int intRawValue = RRuntime.int2rawIntValue(intVal);
            if (intVal != intRawValue) {
                warningBranch.enter();
                warning = true;
                intRawValue = 0;
            }
            bdata[i] = (byte) intRawValue;
        }
        if (warning) {
            warning(RError.Message.OUT_OF_RANGE);
        }
        return vectorCopy(operand, bdata);
    }

    @Specialization
    protected RRawVector doStringVector(RStringVector operand,
                    @Cached("createBinaryProfile()") ConditionProfile emptyStringProfile,
                    @Cached("create()") NAProfile naProfile) {
        naCheck.enable(operand);
        byte[] bdata = new byte[operand.getLength()];

        boolean naCoercionWarning = false;
        boolean outOfRangeWarning = false;
        for (int i = 0; i < operand.getLength(); i++) {
            String value = operand.getDataAt(i);
            int intValue;
            if (naCheck.check(value) || emptyStringProfile.profile(value.isEmpty())) {
                intValue = RRuntime.INT_NA;
            } else {
                intValue = RRuntime.string2intNoCheck(value);
                if (naProfile.isNA(intValue)) {
                    if (!value.isEmpty()) {
                        warningBranch.enter();
                        naCoercionWarning = true;
                    }
                }
                int intRawValue = RRuntime.int2rawIntValue(intValue);
                if (intValue != intRawValue) {
                    warningBranch.enter();
                    outOfRangeWarning = true;
                    intValue = 0;
                }
            }
            bdata[i] = (byte) intValue;
        }
        if (naCoercionWarning) {
            warning(RError.Message.NA_INTRODUCED_COERCION);
        }
        if (outOfRangeWarning) {
            warning(RError.Message.OUT_OF_RANGE);
        }
        return vectorCopy(operand, bdata);
    }

    @Specialization
    protected RRawVector doComplexVector(RComplexVector operand) {
        byte[] bdata = new byte[operand.getLength()];
        boolean imaginaryDiscardedWarning = false;
        boolean outOfRangeWarning = false;
        for (int i = 0; i < operand.getLength(); i++) {
            RComplex complexVal = operand.getDataAt(i);
            int intRawValue = RRuntime.complex2rawIntValue(complexVal);
            if (complexVal.getImaginaryPart() != 0.0) {
                warningBranch.enter();
                imaginaryDiscardedWarning = true;
            }
            if ((int) complexVal.getRealPart() != intRawValue) {
                warningBranch.enter();
                outOfRangeWarning = true;
                intRawValue = 0;
            }
            bdata[i] = (byte) intRawValue;
        }
        if (imaginaryDiscardedWarning) {
            warning(RError.Message.IMAGINARY_PARTS_DISCARDED_IN_COERCION);
        }
        if (outOfRangeWarning) {
            warning(RError.Message.OUT_OF_RANGE);
        }
        return vectorCopy(operand, bdata);
    }

    @Specialization
    protected RRawVector doDoubleVector(RAbstractDoubleVector operand) {
        int length = operand.getLength();
        byte[] bdata = new byte[length];
        boolean warning = false;
        for (int i = 0; i < length; i++) {
            double doubleValue = operand.getDataAt(i);
            int intRawValue = RRuntime.double2rawIntValue(doubleValue);
            if (intRawValue != (int) doubleValue) {
                warningBranch.enter();
                warning = true;
                intRawValue = 0;
            }
            bdata[i] = (byte) intRawValue;
        }
        if (warning) {
            warning(RError.Message.OUT_OF_RANGE);
        }
        return vectorCopy(operand, bdata);
    }

    @Specialization
    protected RRawVector doRawVector(RRawVector operand) {
        return operand;
    }

    @Specialization
    protected RRawVector doList(RAbstractListVector value) {
        int length = value.getLength();
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = ((RRaw) castRawRecursive(value.getDataAt(i))).getValue();
        }
        RRawVector result = RDataFactory.createRawVector(data, getPreservedDimensions(value), getPreservedNames(value));
        if (preserveAttributes()) {
            result.copyRegAttributesFrom(value);
        }
        return result;
    }

    @Specialization(guards = "isForeignObject(obj)")
    protected RRawVector doForeignObject(TruffleObject obj,
                    @Cached("createForeignArray2RNode()") ForeignArray2R foreignArray2R) {
        Object o = foreignArray2R.execute(obj, true);
        if (!RRuntime.isForeignObject(o)) {
            if (o instanceof RRawVector) {
                return (RRawVector) o;
            }
            o = castRawRecursive(o);
            if (o instanceof RRawVector) {
                return (RRawVector) o;
            }
        }
        throw error(RError.Message.CANNOT_COERCE_EXTERNAL_OBJECT_TO_VECTOR, "vector");
    }

    public static CastRawNode createForRFFI(boolean preserveNames, boolean preserveDimensions, boolean preserveAttributes) {
        return CastRawNodeGen.create(preserveNames, preserveDimensions, preserveAttributes, true);
    }

    public static CastRawNode createNonPreserving() {
        return CastRawNodeGen.create(false, false, false);
    }

    protected ForeignArray2R createForeignArray2RNode() {
        return ForeignArray2RNodeGen.create();
    }
}
