/*
 * Copyright (c) 2013, 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.r.nodes.builtin.base;

import static com.oracle.truffle.r.runtime.RBuiltinKind.*;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.dsl.*;
import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.r.nodes.*;
import com.oracle.truffle.r.nodes.access.*;
import com.oracle.truffle.r.nodes.builtin.*;
import com.oracle.truffle.r.nodes.function.*;
import com.oracle.truffle.r.runtime.*;
import com.oracle.truffle.r.runtime.data.*;
import com.oracle.truffle.r.runtime.data.RPromise.PromiseProfile;

@RBuiltin(name = "missing", kind = PRIMITIVE, parameterNames = {"x"}, nonEvalArgs = {0})
public abstract class Missing extends RBuiltinNode {

    private final PromiseProfile promiseProfile = new PromiseProfile();

    @Child private GetMissingValueNode getMissingValue;

    @Specialization
    protected byte missing(VirtualFrame frame, RPromise promise) {
        controlVisibility();
        // Unwrap current promise, as it's irrelevant for 'missing'
        RNode argExpr = (RNode) promise.getRep();
        Symbol symbol = RMissingHelper.unwrapSymbol(argExpr);
        if (symbol == null) {
            return RRuntime.asLogical(false);
        }

        // Read symbols value directly
        if (getMissingValue == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            getMissingValue = insert(GetMissingValueNode.create(symbol));
        }
        Object obj = getMissingValue.execute(frame);
        if (obj == null) {
            // In case we are not able to read the symbol in current frame: This is not an argument
            // and thus return false
            return RRuntime.asLogical(false);
        }

        return RRuntime.asLogical(RMissingHelper.isMissing(obj, promiseProfile));
    }

    @Specialization(guards = "!isPromise")
    protected byte missing(Object obj) {
        controlVisibility();
        return RRuntime.asLogical(RMissingHelper.isMissing(obj, promiseProfile));
    }

    public boolean isPromise(Object obj) {
        return obj instanceof RPromise;
    }
}
