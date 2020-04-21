/*
 * Copyright (c) 2013, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 3 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 3 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 3 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.r.nodes.builtin.base;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.api.profiles.LoopConditionProfile;
import com.oracle.truffle.r.runtime.DSLConfig;
import com.oracle.truffle.r.runtime.data.VectorDataLibrary;
import com.oracle.truffle.r.runtime.data.VectorDataLibrary.RandomAccessIterator;
import com.oracle.truffle.r.runtime.data.nodes.attributes.SpecialAttributesFunctions.GetDimAttributeNode;
import com.oracle.truffle.r.runtime.data.nodes.attributes.SpecialAttributesFunctions.GetDimNamesAttributeNode;
import com.oracle.truffle.r.runtime.data.nodes.attributes.SpecialAttributesFunctions.SetDimAttributeNode;
import com.oracle.truffle.r.runtime.data.nodes.attributes.SpecialAttributesFunctions.SetDimNamesAttributeNode;
import com.oracle.truffle.r.nodes.binary.BinaryMapArithmeticFunctionNode;
import com.oracle.truffle.r.nodes.builtin.RBuiltinNode;
import com.oracle.truffle.r.nodes.control.RLengthNode;
import com.oracle.truffle.r.runtime.RError;
import com.oracle.truffle.r.runtime.RRuntime;
import com.oracle.truffle.r.runtime.RType;
import com.oracle.truffle.r.runtime.builtins.RBuiltin;
import com.oracle.truffle.r.runtime.data.RComplex;
import com.oracle.truffle.r.runtime.data.RComplexVector;
import com.oracle.truffle.r.runtime.data.RDataFactory;
import com.oracle.truffle.r.runtime.data.RDoubleVector;
import com.oracle.truffle.r.runtime.data.RList;
import com.oracle.truffle.r.runtime.data.RNull;
import com.oracle.truffle.r.runtime.data.model.RAbstractAtomicVector;
import com.oracle.truffle.r.runtime.data.RIntVector;
import com.oracle.truffle.r.runtime.data.model.RAbstractVector;
import com.oracle.truffle.r.runtime.ops.BinaryArithmetic;
import com.oracle.truffle.r.runtime.ops.na.NACheck;

import java.util.Arrays;

import static com.oracle.truffle.r.runtime.data.nodes.attributes.SpecialAttributesFunctions.GetDimAttributeNode.isMatrix;
import static com.oracle.truffle.r.runtime.RDispatch.OPS_GROUP_GENERIC;
import static com.oracle.truffle.r.runtime.builtins.RBehavior.PURE;
import static com.oracle.truffle.r.runtime.builtins.RBuiltinKind.PRIMITIVE;
import com.oracle.truffle.r.runtime.data.RLogicalVector;
import com.oracle.truffle.r.runtime.data.RSharingAttributeStorage;

@RBuiltin(name = "%*%", kind = PRIMITIVE, parameterNames = {"", ""}, behavior = PURE, dispatch = OPS_GROUP_GENERIC)
public abstract class MatMult extends RBuiltinNode.Arg2 {

    private static final int BLOCK_SIZE = 64;

    @Child private BinaryMapArithmeticFunctionNode mult = new BinaryMapArithmeticFunctionNode(BinaryArithmetic.MULTIPLY.createOperation());
    @Child private BinaryMapArithmeticFunctionNode add = new BinaryMapArithmeticFunctionNode(BinaryArithmetic.ADD.createOperation());
    private final boolean promoteDimNames;

    private final LoopConditionProfile mainLoopProfile = LoopConditionProfile.createCountingProfile();
    private final LoopConditionProfile remainingLoopProfile = LoopConditionProfile.createCountingProfile();

    private final ConditionProfile notOneRow = ConditionProfile.createBinaryProfile();
    private final ConditionProfile notOneColumn = ConditionProfile.createBinaryProfile();

    private final ConditionProfile noDimAttributes = ConditionProfile.createBinaryProfile();

    @Child protected GetDimAttributeNode getADimsNode = GetDimAttributeNode.create();
    @Child protected GetDimAttributeNode getBDimsNode = GetDimAttributeNode.create();
    @Child protected SetDimAttributeNode setDimsNode = SetDimAttributeNode.create();

    @Child private SetDimNamesAttributeNode setDimNamesNode = SetDimNamesAttributeNode.create();
    @Child private GetDimNamesAttributeNode getADimNamesNode = GetDimNamesAttributeNode.create();
    @Child private GetDimNamesAttributeNode getBDimNamesNode = GetDimNamesAttributeNode.create();

    @Child private VectorDataLibrary coercionResultDataLib;

    protected abstract Object executeObject(Object a, Object b);

    private final NACheck na;

    static {
        Casts.noCasts(MatMult.class);
    }

    public MatMult(boolean promoteDimNames) {
        this.promoteDimNames = promoteDimNames;
        this.na = NACheck.create();
    }

    public static MatMult create() {
        return MatMultNodeGen.create(true);
    }

    @Specialization(guards = "bothZeroDim(a, b)")
    protected RDoubleVector both0Dim(RDoubleVector a, RDoubleVector b) {
        int r = getBDimsNode.getDimensions(b)[1];
        int c = getADimsNode.getDimensions(a)[0];
        RDoubleVector result = RDataFactory.createDoubleVector(r * c);
        setDimsNode.setDimensions(result, new int[]{r, c});
        return result;
    }

    protected static boolean isEmpty(RAbstractVector vec, RLengthNode lenNode) {
        return lenNode.executeInteger(vec) == 0;
    }

    @Specialization(guards = {"hasZeroDim(a, getADimsNode)", "!isEmpty(b, getLength)"}, limit = "1")
    protected RAbstractVector left0Dim(RAbstractVector a, RAbstractVector b,
                    @Cached RLengthNode getLength,
                    @Cached() SetDimAttributeNode setDims) {
        int[] aDim = getADimsNode.getDimensions(a);
        assert aDim != null; // implied by hasZeroDim returning true
        int[] bDim = getBDimsNode.getDimensions(b);
        if (!isMatrix(bDim)) {
            int bLen = getLength.executeInteger(b);
            if (bLen == aDim[1]) {
                bDim = new int[]{bLen, 1};
            } else {
                bDim = new int[]{1, bLen};
            }
        }
        int[] dim = aDim[0] == 0 ? new int[]{0, bDim[1]} : new int[]{bDim[0], 0};
        RAbstractVector result = a.copyDropAttributes();
        setDims.setDimensions(result, dim);
        return result;
    }

    @Specialization(guards = {"hasZeroDim(b, getBDimsNode)", "!isEmpty(a, getLength)"}, limit = "1")
    protected RAbstractVector right0Dim(RAbstractVector a, RAbstractVector b,
                    @Cached RLengthNode getLength,
                    @Cached() SetDimAttributeNode setDims) {
        int[] bDim = getBDimsNode.getDimensions(b);
        assert bDim != null; // implied by hasZeroDim returning true
        int[] aDim = getADimsNode.getDimensions(a);
        if (!isMatrix(aDim)) {
            int aLen = getLength.executeInteger(a);
            if (aLen == bDim[0]) {
                aDim = new int[]{1, aLen};
            } else {
                aDim = new int[]{aLen, 1};
            }
        }
        int[] dim = bDim[0] == 0 ? new int[]{0, aDim[1]} : new int[]{aDim[0], 0};
        RAbstractVector result = b.copyDropAttributes();
        setDims.setDimensions(result, dim);
        return result;
    }

    // double-double

    private static void multiplyBlock(double[] a, double[] b, int aRows, double[] result, int row, int col, int k, int aRowStride, int aColStride, int bRowStride, int bColStride, int remainingCols,
                    int remainingRows, int remainingK, LoopConditionProfile loopProfile) {
        for (int innerCol = 0; innerCol < remainingCols; innerCol++) {
            for (int innerRow = 0; innerRow < remainingRows; innerRow++) {
                int bIndex = (col + innerCol) * bColStride + k * bRowStride;
                int aIndex = k * aColStride + (row + innerRow) * aRowStride;
                loopProfile.profileCounted(remainingK);
                double x = 0.0;
                for (int innerK = 0; loopProfile.inject(innerK < remainingK); innerK++) {
                    x += a[aIndex] * b[bIndex];
                    aIndex += aColStride;
                    bIndex += bRowStride;
                }
                result[(col + innerCol) * aRows + row + innerRow] += x;
            }
        }
    }

    private final ConditionProfile bigProfile = ConditionProfile.createBinaryProfile();
    private final BranchProfile incompleteProfile = BranchProfile.create();
    @CompilationFinal private boolean seenLargeMatrix;

    private RDoubleVector doubleMatrixMultiply(VectorDataLibrary aDataLib, Object aData, RAbstractVector a, VectorDataLibrary bDataLib, Object bData, RAbstractVector b, int aRows, int aCols,
                    int bRows, int bCols) {
        return doubleMatrixMultiply(aDataLib, aData, a, bDataLib, bData, b, aRows, aCols, bRows, bCols, 1, aRows, 1, bRows, false);
    }

    /**
     * Performs matrix multiplication, generating the appropriate error if the input matrices are
     * not of compatible size.
     *
     * @param aDataLib vector data library for the first input matrix
     * @param aData data of the first input matrix (potentially coerced to double)
     * @param a the first input matrix
     * @param bDataLib vector data library for the second input matrix
     * @param bData data of the second input matrix (potentially coerced to double)
     * @param b the second input matrix
     * @param aRows the number of rows in the first input matrix
     * @param aCols the number of columns in the first input matrix
     * @param bRows the number of rows in the second input matrix
     * @param bCols the number of columns in the second input matrix
     * @param aRowStride distance between elements in row X and X+1
     * @param aColStride distance between elements in column X and X+1
     * @param bRowStride distance between elements in row X and X+1
     * @param bColStride distance between elements in column X and X+1
     * @param mirrored true if only the upper right triangle of the result needs to be calculated
     * @return the result vector
     */
    public RDoubleVector doubleMatrixMultiply(VectorDataLibrary aDataLib, Object aData, RAbstractVector a, VectorDataLibrary bDataLib, Object bData, RAbstractVector b, int aRows, int aCols, int bRows,
                    int bCols, int aRowStride, int aColStride, int bRowStride,
                    int bColStride, boolean mirrored) {
        if (aCols != bRows) {
            throw error(RError.Message.NON_CONFORMABLE_ARGS);
        }
        double[] dataA = aDataLib.getReadonlyDoubleData(aData);
        double[] dataB = bDataLib.getReadonlyDoubleData(bData);
        double[] result = new double[aRows * bCols];

        if (!seenLargeMatrix && (aRows > BLOCK_SIZE || aCols > BLOCK_SIZE || bRows > BLOCK_SIZE || bCols > BLOCK_SIZE)) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            seenLargeMatrix = true;
        }
        if (seenLargeMatrix) {
            for (int row = 0; row < aRows; row += BLOCK_SIZE) {
                for (int col = mirrored ? row : 0; col < bCols; col += BLOCK_SIZE) {
                    for (int k = 0; k < aCols; k += BLOCK_SIZE) {
                        int remainingCols = Math.min(BLOCK_SIZE, bCols - col);
                        int remainingRows = Math.min(BLOCK_SIZE, aRows - row);
                        int remainingK = BLOCK_SIZE;
                        if (k + BLOCK_SIZE > aCols) {
                            remainingK = aCols - k;
                        }
                        if (bigProfile.profile(remainingCols == BLOCK_SIZE && remainingRows == BLOCK_SIZE && remainingK == BLOCK_SIZE)) {
                            multiplyBlock(dataA, dataB, aRows, result, row, col, k, aRowStride, aColStride, bRowStride, bColStride, BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE, mainLoopProfile);
                        } else {
                            multiplyBlock(dataA, dataB, aRows, result, row, col, k, aRowStride, aColStride, bRowStride, bColStride, remainingCols, remainingRows, remainingK, remainingLoopProfile);
                        }
                    }
                }
            }
        } else {
            multiplyBlock(dataA, dataB, aRows, result, 0, 0, 0, aRowStride, aColStride, bRowStride, bColStride, bCols, aRows, aCols, remainingLoopProfile);
        }
        // NAs are checked in bulk here, because doing so during multiplication is too costly
        boolean complete = true;
        if (!bDataLib.isComplete(bData)) {
            incompleteProfile.enter();
            fixNAColumns(dataB, aRows, aCols, bCols, bRowStride, bColStride, result);
            complete = false;
        }
        if (!complete || !aDataLib.isComplete(aData)) {
            /*
             * In case b is not complete, NaN rows need to be restored because the NaN in a takes
             * precedence over the NA in b.
             */
            incompleteProfile.enter();
            fixNARows(dataA, aRows, bRows, bCols, aRowStride, aColStride, result);
            complete = false;
        }

        RDoubleVector resultVec = RDataFactory.createDoubleVector(result, complete, new int[]{aRows, bCols});
        RList aDimNames = getADimNamesNode.getDimNames(a);
        RList bDimNames = getBDimNamesNode.getDimNames(b);
        if (!promoteDimNames || noDimAttributes.profile(aDimNames == null && bDimNames == null)) {
            return resultVec;
        }

        Object dimName1 = RNull.instance;
        if (aDimNames != null && aDimNames.getLength() > 0) {
            dimName1 = aDimNames.getDataAt(0);
            if (dimName1 instanceof RSharingAttributeStorage && !((RSharingAttributeStorage) dimName1).isShared()) {
                ((RSharingAttributeStorage) dimName1).incRefCount();
            }
        }
        Object dimName2 = RNull.instance;
        if (bDimNames != null && bDimNames.getLength() > 1) {
            dimName2 = bDimNames.getDataAt(1);
            if (dimName2 instanceof RSharingAttributeStorage && !((RSharingAttributeStorage) dimName2).isShared()) {
                ((RSharingAttributeStorage) dimName2).incRefCount();
            }
        }
        setDimNamesNode.setDimNames(resultVec, RDataFactory.createList(new Object[]{dimName1, dimName2}));
        return resultVec;
    }

    private static void fixNARows(double[] dataA, int aRows, int aCols, int bCols, int aRowStride, int aColStride, double[] result) {
        // NA's in a cause the whole row to be NA in the result
        outer: for (int row = 0; row < aRows; row++) {
            boolean hasNaN = false;
            for (int col = 0; col < aCols; col++) {
                double value = dataA[col * aColStride + row * aRowStride];
                if (RRuntime.isNA(value)) {
                    for (int innerCol = 0; innerCol < bCols; innerCol++) {
                        result[innerCol * aRows + row] = RRuntime.DOUBLE_NA;
                    }
                    continue outer;
                } else if (Double.isNaN(value)) {
                    hasNaN = true;
                }
            }
            if (hasNaN) {
                for (int innerCol = 0; innerCol < bCols; innerCol++) {
                    result[innerCol * aRows + row] = Double.NaN;
                }
            }
        }
    }

    private static void fixNAColumns(double[] dataB, int aRows, int bRows, int bCols, int bRowStride, int bColStride, double[] result) {
        // NA's in b cause the whole column to be NA in the result
        outer: for (int col = 0; col < bCols; col++) {
            for (int row = 0; row < bRows; row++) {
                if (RRuntime.isNA(dataB[col * bColStride + row * bRowStride])) {
                    for (int innerRow = 0; innerRow < aRows; innerRow++) {
                        result[col * aRows + innerRow] = RRuntime.DOUBLE_NA;
                    }
                    continue outer;
                }
            }
        }
    }

    @Specialization(limit = "getTypedVectorDataLibraryCacheSize()")
    protected RDoubleVector multiplyDouble(RDoubleVector a, RDoubleVector b,
                    @CachedLibrary("a.getData()") VectorDataLibrary aDataLib,
                    @CachedLibrary("b.getData()") VectorDataLibrary bDataLib,
                    @Cached("createBinaryProfile()") ConditionProfile aIsMatrix,
                    @Cached("createBinaryProfile()") ConditionProfile bIsMatrix,
                    @Cached("createBinaryProfile()") ConditionProfile lengthEquals) {
        return doubleMultiply(aDataLib, a.getData(), a, bDataLib, b.getData(), b, aIsMatrix, bIsMatrix, lengthEquals);
    }

    private RDoubleVector doubleMultiply(VectorDataLibrary aDataLib, Object aData, RAbstractVector a, VectorDataLibrary bDataLib, Object bData, RAbstractVector b, ConditionProfile aIsMatrix,
                    ConditionProfile bIsMatrix, ConditionProfile lengthEquals) {
        // Note: aData/bData may be coerced data of a, i.e., not the original data of a
        int[] aDimensions = getADimsNode.getDimensions(a);
        int[] bDimensions = getBDimsNode.getDimensions(b);
        final int bLength = bDataLib.getLength(bData);
        if (aIsMatrix.profile(isMatrix(aDimensions))) {
            if (bIsMatrix.profile(isMatrix(bDimensions))) {
                return doubleMatrixMultiply(aDataLib, aData, a, bDataLib, bData, b, aDimensions[0], aDimensions[1], bDimensions[0], bDimensions[1]);
            } else {
                int aRows = aDimensions[0];
                int aCols = aDimensions[1];
                int bRows;
                int bCols;
                if (lengthEquals.profile(aCols == bLength)) {
                    bRows = bLength;
                    bCols = 1;
                } else {
                    bRows = 1;
                    bCols = bLength;
                }
                return doubleMatrixMultiply(aDataLib, aData, a, bDataLib, bData, b, aRows, aCols, bRows, bCols);
            }
        } else {
            final int aLength = aDataLib.getLength(aData);
            if (bIsMatrix.profile(isMatrix(bDimensions))) {
                int bRows = bDimensions[0];
                int bCols = bDimensions[1];
                int aRows;
                int aCols;
                if (lengthEquals.profile(bRows == aLength)) {
                    aRows = 1;
                    aCols = aLength;
                } else {
                    aRows = aLength;
                    aCols = 1;
                }
                return doubleMatrixMultiply(aDataLib, aData, a, bDataLib, bData, b, aRows, aCols, bRows, bCols);
            } else {
                if (aLength == 1) {
                    na.enable(aDataLib, aData);
                    na.enable(bDataLib, bData);
                    double aValue = aDataLib.getDoubleAt(aData, 0);
                    double[] result = new double[bLength];
                    if (na.checkNAorNaN(aValue)) {
                        if (na.check(aValue)) {
                            Arrays.fill(result, RRuntime.DOUBLE_NA);
                        } else {
                            Arrays.fill(result, Double.NaN);
                        }
                    } else {
                        for (int k = 0; k < bLength; k++) {
                            double bValue = bDataLib.getDoubleAt(bData, k);
                            if (na.check(bValue)) {
                                result[k] = RRuntime.DOUBLE_NA;
                            } else {
                                result[k] = mult.applyDouble(aValue, bValue);
                            }
                        }
                    }
                    return RDataFactory.createDoubleVector(result, na.neverSeenNA(), new int[]{1, bLength});
                }
                if (aLength != bLength) {
                    throw error(RError.Message.NON_CONFORMABLE_ARGS);
                }
                double result = 0.0;
                na.enable(aDataLib, aData);
                na.enable(bDataLib, bData);
                RandomAccessIterator aIt = aDataLib.randomAccessIterator(aData);
                RandomAccessIterator bIt = bDataLib.randomAccessIterator(bData);
                for (int k = 0; k < bLength; k++) {
                    double aValue = aDataLib.getDouble(aData, aIt, k);
                    double bValue = bDataLib.getDouble(bData, bIt, k);
                    /*
                     * The ordering matters: have to check aValue first, NA before NaN, then check
                     * for bValue, again NA before NaN
                     */
                    if (na.checkNAorNaN(aValue) || na.checkNAorNaN(bValue)) {
                        if (na.check(aValue)) {
                            return RDataFactory.createDoubleVector(new double[]{RRuntime.DOUBLE_NA}, false, new int[]{1, 1});
                        } else if (Double.isNaN(aValue)) {
                            return RDataFactory.createDoubleVector(new double[]{Double.NaN}, false, new int[]{1, 1});
                        } else if (na.check(bValue)) {
                            return RDataFactory.createDoubleVector(new double[]{RRuntime.DOUBLE_NA}, false, new int[]{1, 1});
                        } else if (Double.isNaN(bValue)) {
                            return RDataFactory.createDoubleVector(new double[]{Double.NaN}, false, new int[]{1, 1});
                        }
                    }
                    result = add.applyDouble(result, mult.applyDouble(aValue, bValue));
                }
                return RDataFactory.createDoubleVector(new double[]{result}, true, new int[]{1, 1});
            }
        }
    }

    // complex-complex
    @Specialization
    protected RComplexVector multiply(RComplexVector a, RComplexVector b,
                    @Cached("createBinaryProfile()") ConditionProfile aIsMatrix,
                    @Cached("createBinaryProfile()") ConditionProfile bIsMatrix) {
        return complexMultiply(a, b, aIsMatrix, bIsMatrix);
    }

    private RComplexVector complexMultiply(RComplexVector a, RComplexVector b, ConditionProfile aIsMatrix, ConditionProfile bIsMatrix) {
        int[] aDimensions = getADimsNode.getDimensions(a);
        int[] bDimensions = getBDimsNode.getDimensions(b);
        if (aIsMatrix.profile(isMatrix(aDimensions))) {
            if (bIsMatrix.profile(isMatrix(bDimensions))) {
                final int aCols = aDimensions[1];
                final int bRows = bDimensions[0];
                if (aCols != bRows) {
                    throw error(RError.Message.NON_CONFORMABLE_ARGS);
                }
                final int aRows = aDimensions[0];
                final int bCols = bDimensions[1];
                double[] result = new double[(aRows * bCols) << 1];
                na.enable(a);
                na.enable(b);
                mult.enable(a, b);
                for (int row = 0; row < aRows; row++) {
                    for (int col = 0; col < bCols; col++) {
                        RComplex x = RRuntime.COMPLEX_ZERO;
                        na.enable(x);
                        RComplex tmp;
                        for (int k = 0; k < aCols; k++) {
                            tmp = mult.applyComplex(a.getDataAt(k * aRows + row), b.getDataAt(col * bRows + k));
                            add.getLeftNACheck().enable(tmp);
                            add.getRightNACheck().enable(tmp);
                            x = add.applyComplex(x, tmp);
                            na.check(x);
                        }
                        final int index = 2 * (col * aRows + row);
                        result[index] = x.getRealPart();
                        result[index + 1] = x.getImaginaryPart();
                    }
                }
                return RDataFactory.createComplexVector(result, na.neverSeenNA(), new int[]{aRows, bCols});
            } else {
                final int aCols = aDimensions[1];
                final int aRows = aDimensions[0];
                if (aCols != 1 && aCols != b.getLength()) {
                    throw error(RError.Message.NON_CONFORMABLE_ARGS);
                }
                na.enable(a);
                na.enable(b);
                mult.enable(a, b);
                if (notOneColumn.profile(aCols != 1)) {
                    double[] result = new double[aRows << 1];
                    for (int row = 0; row < aRows; row++) {
                        RComplex x = RRuntime.COMPLEX_ZERO;
                        na.enable(x);
                        RComplex tmp;
                        for (int k = 0; k < b.getLength(); k++) {
                            tmp = mult.applyComplex(a.getDataAt(k * aRows + row), b.getDataAt(k));
                            add.getLeftNACheck().enable(tmp);
                            add.getRightNACheck().enable(tmp);
                            x = add.applyComplex(x, tmp);
                            na.check(x);
                        }
                        result[row << 1] = x.getRealPart();
                        result[(row << 1) + 1] = x.getImaginaryPart();
                    }
                    return RDataFactory.createComplexVector(result, na.neverSeenNA(), new int[]{aRows, 1});
                } else {
                    double[] result = new double[aRows * b.getLength() << 1];
                    for (int row = 0; row < aRows; row++) {
                        for (int k = 0; k < b.getLength(); k++) {
                            RComplex x = mult.applyComplex(a.getDataAt(row), b.getDataAt(k));
                            na.check(x);
                            result[(k * aRows + row) << 1] = x.getRealPart();
                            result[((k * aRows + row) << 1) + 1] = x.getImaginaryPart();
                        }
                    }
                    return RDataFactory.createComplexVector(result, na.neverSeenNA(), new int[]{aRows, b.getLength()});
                }
            }
        } else {
            if (bIsMatrix.profile(isMatrix(bDimensions))) {
                final int bRows = bDimensions[0];
                final int bCols = bDimensions[1];
                if (bRows != 1 && bRows != a.getLength()) {
                    throw error(RError.Message.NON_CONFORMABLE_ARGS);
                }
                na.enable(a);
                na.enable(b);
                mult.enable(a, b);
                if (notOneRow.profile(bRows != 1)) {
                    double[] result = new double[bCols << 1];
                    for (int k = 0; k < bCols; k++) {
                        RComplex x = RRuntime.COMPLEX_ZERO;
                        na.enable(x);
                        RComplex tmp;
                        for (int row = 0; row < a.getLength(); row++) {
                            tmp = mult.applyComplex(a.getDataAt(row), b.getDataAt(k * a.getLength() + row));
                            add.getLeftNACheck().enable(tmp);
                            add.getRightNACheck().enable(tmp);
                            x = add.applyComplex(x, tmp);
                            na.check(x);
                        }
                        result[k << 1] = x.getRealPart();
                        result[(k << 1) + 1] = x.getImaginaryPart();
                    }
                    return RDataFactory.createComplexVector(result, na.neverSeenNA(), new int[]{1, bCols});
                } else {
                    double[] result = new double[(bCols * a.getLength()) << 1];
                    for (int row = 0; row < a.getLength(); row++) {
                        for (int k = 0; k < bCols; k++) {
                            RComplex x = mult.applyComplex(a.getDataAt(row), b.getDataAt(k));
                            na.check(x);
                            result[(k * a.getLength() + row) << 1] = x.getRealPart();
                            result[((k * a.getLength() + row) << 1) + 1] = x.getImaginaryPart();
                        }
                    }
                    return RDataFactory.createComplexVector(result, na.neverSeenNA(), new int[]{a.getLength(), bCols});
                }
            } else {
                if (a.getLength() == 1) {
                    na.enable(a);
                    na.enable(b);
                    mult.enable(a, b);
                    RComplex aValue = a.getDataAt(0);
                    RComplex bValue = b.getDataAt(0);
                    double[] result = new double[2 * b.getLength()];

                    if (na.checkNAorNaN(aValue.getRealPart()) || na.checkNAorNaN(aValue.getImaginaryPart()) ||
                                    na.checkNAorNaN(bValue.getRealPart()) || na.checkNAorNaN(bValue.getImaginaryPart())) {
                        if (na.check(bValue)) {
                            Arrays.fill(result, RRuntime.DOUBLE_NA);
                        } else if (Double.isNaN(bValue.getRealPart()) || Double.isNaN(bValue.getImaginaryPart())) {
                            Arrays.fill(result, Double.NaN);
                        } else if (na.check(aValue)) {
                            Arrays.fill(result, RRuntime.DOUBLE_NA);
                        } else if (Double.isNaN(aValue.getRealPart()) || Double.isNaN(aValue.getImaginaryPart())) {
                            Arrays.fill(result, Double.NaN);
                        }
                    } else {
                        for (int k = 0; k < b.getLength(); k++) {
                            RComplex res = mult.applyComplex(aValue, b.getDataAt(k));
                            result[2 * k] = res.getRealPart();
                            result[2 * k + 1] = res.getImaginaryPart();
                        }
                    }
                    return RDataFactory.createComplexVector(result, na.neverSeenNA(), new int[]{1, b.getLength()});
                }

                if (a.getLength() != b.getLength()) {
                    throw error(RError.Message.NON_CONFORMABLE_ARGS);
                }
                RComplex result = RRuntime.COMPLEX_ZERO;
                RComplex tmp;
                na.enable(a);
                na.enable(b);
                na.enable(result);
                mult.enable(a, b);
                for (int k = 0; k < a.getLength(); k++) {
                    RComplex aValue = a.getDataAt(k);
                    RComplex bValue = b.getDataAt(k);
                    tmp = mult.applyComplex(aValue, bValue);
                    add.getLeftNACheck().enable(tmp);
                    add.getRightNACheck().enable(tmp);
                    result = add.applyComplex(result, tmp);
                    na.check(result);
                }
                return RDataFactory.createComplexVector(new double[]{result.getRealPart(), result.getImaginaryPart()}, na.neverSeenNA(), new int[]{1, 1});
            }
        }
    }

    // int-int
    @Specialization(limit = "getTypedVectorDataLibraryCacheSize()")
    protected RIntVector multiply(RIntVector a, RIntVector b,
                    @CachedLibrary("a.getData()") VectorDataLibrary aDataLib,
                    @CachedLibrary("b.getData()") VectorDataLibrary bDataLib,
                    @Cached("createBinaryProfile()") ConditionProfile aIsMatrix,
                    @Cached("createBinaryProfile()") ConditionProfile bIsMatrix) {
        return intMultiply(aDataLib, a.getData(), a, bDataLib, b.getData(), b, aIsMatrix, bIsMatrix);
    }

    private RIntVector intMultiply(VectorDataLibrary aDataLib, Object aData, RAbstractVector a, VectorDataLibrary bDataLib, Object bData, RAbstractVector b, ConditionProfile aIsMatrix,
                    ConditionProfile bIsMatrix) {
        int[] aDimensions = getADimsNode.getDimensions(a);
        int[] bDimensions = getBDimsNode.getDimensions(b);
        if (aIsMatrix.profile(isMatrix(aDimensions))) {
            if (bIsMatrix.profile(isMatrix(bDimensions))) {
                int aCols = aDimensions[1];
                int bRows = bDimensions[0];
                if (aCols != bRows) {
                    throw error(RError.Message.NON_CONFORMABLE_ARGS);
                }
                int aRows = aDimensions[0];
                int bCols = bDimensions[1];
                int[] result = new int[aRows * bCols];
                na.enable(a);
                na.enable(b);
                for (int row = 0; row < aRows; row++) {
                    for (int col = 0; col < bCols; col++) {
                        int x = 0;
                        for (int k = 0; k < aCols; k++) {
                            x = add.applyInteger(x, mult.applyInteger(aDataLib.getIntAt(aData, k * aRows + row), bDataLib.getIntAt(bData, col * bRows + k)));
                            na.check(x);
                        }
                        result[col * aRows + row] = x;
                    }
                }
                return RDataFactory.createIntVector(result, na.neverSeenNA(), new int[]{aRows, bCols});
            } else {
                final int aCols = aDimensions[1];
                final int aRows = aDimensions[0];
                if (aCols != 1 && aCols != bDataLib.getLength(bData)) {
                    throw error(RError.Message.NON_CONFORMABLE_ARGS);
                }
                na.enable(a);
                na.enable(b);
                if (notOneColumn.profile(aCols != 1)) {
                    int[] result = new int[aRows];
                    for (int row = 0; row < aRows; row++) {
                        int x = 0;
                        for (int k = 0; k < bDataLib.getLength(bData); k++) {
                            x = add.applyInteger(x, mult.applyInteger(aDataLib.getIntAt(aData, k * aRows + row), bDataLib.getIntAt(bData, k)));
                            na.check(x);
                        }
                        result[row] = x;
                    }
                    return RDataFactory.createIntVector(result, na.neverSeenNA(), new int[]{aRows, 1});
                } else {
                    int[] result = new int[aRows * bDataLib.getLength(bData)];
                    for (int row = 0; row < aRows; row++) {
                        for (int k = 0; k < bDataLib.getLength(bData); k++) {
                            int x = mult.applyInteger(aDataLib.getIntAt(aData, row), bDataLib.getIntAt(bData, k));
                            na.check(x);
                            result[k * aRows + row] = x;
                        }
                    }
                    return RDataFactory.createIntVector(result, na.neverSeenNA(), new int[]{aRows, bDataLib.getLength(bData)});
                }
            }
        } else {
            if (bIsMatrix.profile(isMatrix(bDimensions))) {
                final int bCols = bDimensions[1];
                final int bRows = bDimensions[0];
                if (bRows != 1 && bRows != aDataLib.getLength(aData)) {
                    throw error(RError.Message.NON_CONFORMABLE_ARGS);
                }
                na.enable(a);
                na.enable(b);
                if (notOneRow.profile(bRows != 1)) {
                    int[] result = new int[bCols];
                    for (int k = 0; k < bCols; k++) {
                        int x = 0;
                        for (int row = 0; row < aDataLib.getLength(aData); row++) {
                            x = add.applyInteger(x, mult.applyInteger(aDataLib.getIntAt(aData, row), bDataLib.getIntAt(bData, k * aDataLib.getLength(aData) + row)));
                            na.check(x);
                        }
                        result[k] = x;
                    }
                    return RDataFactory.createIntVector(result, na.neverSeenNA(), new int[]{1, bCols});
                } else {
                    int[] result = new int[bCols * aDataLib.getLength(aData)];
                    for (int row = 0; row < aDataLib.getLength(aData); row++) {
                        for (int k = 0; k < bCols; k++) {
                            int x = mult.applyInteger(aDataLib.getIntAt(aData, row), bDataLib.getIntAt(bData, k));
                            na.check(x);
                            result[k * aDataLib.getLength(aData) + row] = x;
                        }
                    }
                    return RDataFactory.createIntVector(result, na.neverSeenNA(), new int[]{aDataLib.getLength(aData), bCols});
                }
            } else {
                if (aDataLib.getLength(aData) == 1) {
                    na.enable(a);
                    na.enable(b);
                    int aValue = aDataLib.getIntAt(aData, 0);
                    int[] result = new int[bDataLib.getLength(bData)];
                    if (na.check(aValue)) {
                        Arrays.fill(result, RRuntime.INT_NA);
                    } else {
                        for (int k = 0; k < bDataLib.getLength(bData); k++) {
                            int bValue = bDataLib.getIntAt(bData, k);
                            if (na.check(bValue)) {
                                result[k] = RRuntime.INT_NA;
                            } else {
                                result[k] = mult.applyInteger(aValue, bValue);
                            }
                        }
                    }
                    return RDataFactory.createIntVector(result, na.neverSeenNA(), new int[]{1, bDataLib.getLength(bData)});
                }

                if (aDataLib.getLength(aData) != bDataLib.getLength(bData)) {
                    throw error(RError.Message.NON_CONFORMABLE_ARGS);
                }
                int result = 0;
                na.enable(result);
                for (int k = 0; k < aDataLib.getLength(aData); k++) {
                    result = add.applyInteger(result, mult.applyInteger(aDataLib.getIntAt(aData, k), bDataLib.getIntAt(bData, k)));
                    na.check(result);
                }
                return RDataFactory.createIntVector(new int[]{result}, na.neverSeenNA(), new int[]{1, 1});
            }
        }
    }

    // logical-logical
    @Specialization(limit = "getTypedVectorDataLibraryCacheSize()")
    protected RIntVector multiply(RLogicalVector a, RLogicalVector b,
                    @CachedLibrary("a.getData()") VectorDataLibrary aDataLib,
                    @CachedLibrary("b.getData()") VectorDataLibrary bDataLib,
                    @Cached("createBinaryProfile()") ConditionProfile aIsMatrix,
                    @Cached("createBinaryProfile()") ConditionProfile bIsMatrix) {
        return intMultiply(aDataLib, a.getData(), a, bDataLib, b.getData(), b, aIsMatrix, bIsMatrix);
    }

    // to int

    @Specialization(limit = "getTypedVectorDataLibraryCacheSize()")
    protected RIntVector multiply(RLogicalVector a, RIntVector b,
                    @CachedLibrary("a.getData()") VectorDataLibrary aDataLib,
                    @CachedLibrary("b.getData()") VectorDataLibrary bDataLib,
                    @Cached("createBinaryProfile()") ConditionProfile aIsMatrix,
                    @Cached("createBinaryProfile()") ConditionProfile bIsMatrix) {
        return intMultiply(aDataLib, a.getData(), a, bDataLib, b.getData(), b, aIsMatrix, bIsMatrix);
    }

    @Specialization(limit = "getTypedVectorDataLibraryCacheSize()")
    protected RIntVector multiply(RIntVector a, RLogicalVector b,
                    @CachedLibrary("a.getData()") VectorDataLibrary aDataLib,
                    @CachedLibrary("b.getData()") VectorDataLibrary bDataLib,
                    @Cached("createBinaryProfile()") ConditionProfile aIsMatrix,
                    @Cached("createBinaryProfile()") ConditionProfile bIsMatrix) {
        return intMultiply(aDataLib, a.getData(), a, bDataLib, b.getData(), b, aIsMatrix, bIsMatrix);
    }

    // to double

    @Specialization(guards = {"!isRAbstractComplexVector(a)"}, limit = "getTypedVectorDataLibraryCacheSize()")
    protected RDoubleVector multiply(RAbstractAtomicVector a, RDoubleVector b,
                    @CachedLibrary("a.getData()") VectorDataLibrary aDataLib,
                    @CachedLibrary("b.getData()") VectorDataLibrary bDataLib,
                    @Cached("createBinaryProfile()") ConditionProfile aIsMatrix,
                    @Cached("createBinaryProfile()") ConditionProfile bIsMatrix,
                    @Cached("createBinaryProfile()") ConditionProfile lengthEquals) {
        Object coercedAData = aDataLib.cast(a.getData(), RType.Double);
        return doubleMultiply(getCoercionResultDataLib(), coercedAData, a, bDataLib, b.getData(), b, aIsMatrix, bIsMatrix, lengthEquals);
    }

    @Specialization(guards = {"!isRAbstractComplexVector(b)"}, limit = "getTypedVectorDataLibraryCacheSize()")
    protected RDoubleVector multiply(RDoubleVector a, RAbstractAtomicVector b,
                    @CachedLibrary("a.getData()") VectorDataLibrary aDataLib,
                    @CachedLibrary("b.getData()") VectorDataLibrary bDataLib,
                    @Cached("createBinaryProfile()") ConditionProfile aIsMatrix,
                    @Cached("createBinaryProfile()") ConditionProfile bIsMatrix,
                    @Cached("createBinaryProfile()") ConditionProfile lengthEquals) {
        Object coercedBData = bDataLib.cast(b.getData(), RType.Double);
        return doubleMultiply(aDataLib, a.getData(), a, getCoercionResultDataLib(), coercedBData, b, aIsMatrix, bIsMatrix, lengthEquals);
    }

    // to complex

    @Specialization
    protected RComplexVector multiply(RAbstractAtomicVector a, RComplexVector b,
                    @Cached("createBinaryProfile()") ConditionProfile aIsMatrix,
                    @Cached("createBinaryProfile()") ConditionProfile bIsMatrix,
                    @Cached("createBinaryProfile()") ConditionProfile isNAProfile) {
        return complexMultiply((RComplexVector) a.castSafe(RType.Complex, isNAProfile), b, aIsMatrix, bIsMatrix);
    }

    @Specialization
    protected RComplexVector multiply(RComplexVector a, RAbstractAtomicVector b,
                    @Cached("createBinaryProfile()") ConditionProfile aIsMatrix,
                    @Cached("createBinaryProfile()") ConditionProfile bIsMatrix,
                    @Cached("createBinaryProfile()") ConditionProfile isNAProfile) {
        return complexMultiply(a, (RComplexVector) b.castSafe(RType.Complex, isNAProfile), aIsMatrix, bIsMatrix);
    }

    // errors

    @Fallback
    @TruffleBoundary
    protected RDoubleVector doRaw(@SuppressWarnings("unused") Object a, @SuppressWarnings("unused") Object b) {
        throw error(RError.Message.NUMERIC_COMPLEX_MATRIX_VECTOR);
    }

    // helpers

    public VectorDataLibrary getCoercionResultDataLib() {
        if (coercionResultDataLib == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            coercionResultDataLib = insert(VectorDataLibrary.getFactory().createDispatched(DSLConfig.getTypedVectorDataLibraryCacheSize()));
        }
        return coercionResultDataLib;
    }

    // guards

    protected boolean bothZeroDim(RAbstractVector a, RAbstractVector b) {
        return hasZeroDim(a, getADimsNode) && hasZeroDim(b, getBDimsNode);
    }

    protected boolean hasZeroDim(RAbstractVector v, GetDimAttributeNode getDimsNode) {
        int[] dims = getDimsNode.getDimensions(v);
        if (dims == null || dims.length == 0) {
            return false;
        }
        for (int d : dims) {
            if (d == 0) {
                return true;
            }
        }
        return false;
    }
}
