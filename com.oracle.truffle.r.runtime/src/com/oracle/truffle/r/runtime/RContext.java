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
package com.oracle.truffle.r.runtime;

import java.util.*;

import com.oracle.truffle.api.source.*;
import com.oracle.truffle.r.runtime.data.*;

/**
 * Helper class carrying state for an {@code REngine}. As per {@code REngine}, there is exactly one
 * instance of this class, which is tied to the {@code REngine}. It has a separate existence to
 * solve circularities in the current project structure.
 */
public final class RContext {

    public interface ConsoleHandler {
        /**
         * Normal output with a new line.
         */
        void println(String s);

        /**
         * Normal output without a newline.
         */
        void print(String s);

        /**
         * Error output with a newline.
         * 
         * @param s
         */
        void printErrorln(String s);

        /**
         * Error output without a newline.
         */
        void printError(String s);

        /**
         * Read a line of input, newline omitted in result. Returns null if {@link #isInteractive()
         * == false}.
         */
        String readLine();

        /**
         * Return {@code true} if and only if this console is interactive.
         */
        boolean isInteractive();

        /**
         * Redirect error output to the normal output.
         */
        void redirectError();

        /**
         * Set the R prompt.
         */
        void setPrompt(String prompt);
    }

    private final SourceManager sourceManager;
    private final RBuiltinLookup lookup;
    private final String[] commandArgs;
    private final HashMap<Object, RFunction> cachedFunctions = new HashMap<>();
    private final GlobalAssumptions globalAssumptions = new GlobalAssumptions();
    private final RRandomNumberGenerator randomNumberGenerator = new RRandomNumberGenerator();
    private final ConsoleHandler consoleHandler;
    private LinkedList<String> evalWarnings;

    private static RContext singleton;

    public GlobalAssumptions getAssumptions() {
        return globalAssumptions;
    }

    public static RContext getInstance() {
        assert singleton != null;
        return singleton;
    }

    public static RContext instantiate(RBuiltinLookup lookup, String[] commandArgs, ConsoleHandler consoleHandler) {
        if (singleton != null) {
            throw new IllegalStateException("RContext already instantiated.");
        }
        singleton = new RContext(lookup, commandArgs, consoleHandler);
        return singleton;
    }

    private RContext(RBuiltinLookup lookup, String[] commandArgs, ConsoleHandler consoleHandler) {
        this.sourceManager = new SourceManager();
        this.lookup = lookup;
        this.commandArgs = commandArgs;
        this.consoleHandler = consoleHandler;
        this.evalWarnings = null;
    }

    public RBuiltinLookup getLookup() {
        return lookup;
    }

    public ConsoleHandler getConsoleHandler() {
        return consoleHandler;
    }

    public SourceManager getSourceManager() {
        return sourceManager;
    }

    public RFunction putCachedFunction(Object key, RFunction function) {
        cachedFunctions.put(key, function);
        return function;
    }

    public RFunction getCachedFunction(Object key) {
        return cachedFunctions.get(key);
    }

    public String[] getCommandArgs() {
        return commandArgs;
    }

    public RRandomNumberGenerator getRandomNumberGenerator() {
        return randomNumberGenerator;
    }

    public List<String> extractEvalWarnings() {
        List<String> l = evalWarnings;
        evalWarnings = null;
        return l;
    }

    public void setEvalWarning(String s) {
        if (evalWarnings == null) {
            evalWarnings = new LinkedList<>();
        }
        evalWarnings.add(s);
    }
}
