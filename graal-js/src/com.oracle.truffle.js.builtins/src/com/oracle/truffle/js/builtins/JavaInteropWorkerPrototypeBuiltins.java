/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.js.builtins;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.js.builtins.JavaInteropWorkerPrototypeBuiltinsFactory.JavaInteropWorkerSubmitNodeGen;
import com.oracle.truffle.js.builtins.JavaInteropWorkerPrototypeBuiltinsFactory.JavaInteropWorkerTerminateNodeGen;
import com.oracle.truffle.js.nodes.function.JSBuiltin;
import com.oracle.truffle.js.nodes.function.JSBuiltinNode;
import com.oracle.truffle.js.nodes.function.JSFunctionCallNode;
import com.oracle.truffle.js.nodes.interop.ExportValueNode;
import com.oracle.truffle.js.nodes.interop.ExportValueNodeGen;
import com.oracle.truffle.js.runtime.EcmaAgent;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.JSArguments;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.js.runtime.JavaScriptRootNode;
import com.oracle.truffle.js.runtime.builtins.BuiltinEnum;
import com.oracle.truffle.js.runtime.builtins.JSArray;
import com.oracle.truffle.js.runtime.builtins.JSFunction;
import com.oracle.truffle.js.runtime.builtins.JSFunctionData;
import com.oracle.truffle.js.runtime.builtins.JSJavaWorkerBuiltin;
import com.oracle.truffle.js.runtime.interop.JavaMethod;
import com.oracle.truffle.js.runtime.objects.Undefined;

/**
 * Contains builtins for {@linkplain JSJavaWorkerBuiltin}.prototype.
 */
public final class JavaInteropWorkerPrototypeBuiltins extends JSBuiltinsContainer.SwitchEnum<JavaInteropWorkerPrototypeBuiltins.JavaInteropWorkerPrototype> {

    protected JavaInteropWorkerPrototypeBuiltins() {
        super(JSJavaWorkerBuiltin.PROTOTYPE_NAME, JavaInteropWorkerPrototype.class);
    }

    public enum JavaInteropWorkerPrototype implements BuiltinEnum<JavaInteropWorkerPrototype> {
        submit(2),
        terminate(0);

        private final int length;

        JavaInteropWorkerPrototype(int length) {
            this.length = length;
        }

        @Override
        public int getLength() {
            return length;
        }
    }

    @Override
    protected Object createNode(JSContext context, JSBuiltin builtin, boolean construct, boolean newTarget, JavaInteropWorkerPrototype builtinEnum) {
        switch (builtinEnum) {
            case submit:
                return JavaInteropWorkerSubmitNodeGen.create(context, builtin, args().withThis().fixedArgs(2).createArgumentNodes(context));
            case terminate:
                return JavaInteropWorkerTerminateNodeGen.create(context, builtin,
                                args().withThis().varArgs().createArgumentNodes(context));
        }
        return null;
    }

    @ImportStatic(value = {JSJavaWorkerBuiltin.class})
    abstract static class JavaInteropWorkerTerminateNode extends JSBuiltinNode {

        JavaInteropWorkerTerminateNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization(guards = "isJSInteropWorker(worker)")
        protected Object doTimeout(DynamicObject worker, Object... timeout) {
            if (timeout.length == 1 && timeout[0] instanceof Integer) {
                JSJavaWorkerBuiltin.getAgent(worker).terminate((int) timeout[0]);
            } else {
                JSJavaWorkerBuiltin.getAgent(worker).terminate(0);
            }
            return true;
        }
    }

    @ImportStatic(value = JSJavaWorkerBuiltin.class)
    abstract static class JavaInteropWorkerSubmitNode extends JSBuiltinNode {

        @Child private JSFunctionCallNode callPromiseConstructor = JSFunctionCallNode.create(true);

        JavaInteropWorkerSubmitNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        protected static boolean isInteropMethod(Object what) {
            return JSRuntime.isForeignObject(what) || what instanceof JavaMethod;
        }

        protected static boolean validArguments(Object arguments) {
            return arguments == Undefined.instance || JSArray.isJSArray(arguments);
        }

        @Specialization(guards = {"isJSInteropWorker(worker)", "validArguments(arguments)"})
        protected Object doForeign(DynamicObject worker, Object method, DynamicObject arguments) {
            // will reject promise if method is not valid.
            return scheduleInWorkerThread(worker, method, arguments);
        }

        @SuppressWarnings("unused")
        @Specialization(guards = {"isJSInteropWorker(worker)", "!validArguments(arguments)"})
        protected Object doFailArguments(Object worker, Object method, Object arguments) {
            throw Errors.createTypeError("Wrong argument type");
        }

        @SuppressWarnings("unused")
        @Specialization(guards = "!isJSInteropWorker(worker)")
        protected Object doFailWorker(Object worker, Object method, Object arguments) {
            throw Errors.createTypeError("Must execute using a valid Java worker");
        }

        private Object scheduleInWorkerThread(DynamicObject worker, Object method, Object arguments) {
            EcmaAgent mainAgent = getContext().getMainWorker();
            EcmaAgent agent = JSJavaWorkerBuiltin.getAgent(worker);

            // Equivalent to: new Promise(function promiseFunctionBody(accept,reject){ ... });
            CallTarget promiseFunctionBody = createPromiseBody(method, arguments, mainAgent, agent);
            DynamicObject promiseBody = JSFunction.create(getContext().getRealm(),
                            JSFunctionData.create(getContext(), promiseFunctionBody, 0, "JavaWorkerPromiseTask"));
            DynamicObject promiseConstructor = getContext().getRealm().getPromiseConstructor();
            return callPromiseConstructor.executeCall(JSArguments.create(promiseConstructor, promiseConstructor, new Object[]{promiseBody}));
        }

        @TruffleBoundary
        private static CallTarget createPromiseBody(Object method, Object arguments, EcmaAgent mainAgent, EcmaAgent agent) {
            CallTarget promiseFunctionBody = Truffle.getRuntime().createCallTarget(new JavaScriptRootNode() {

                @Child private ExportValueNode export = ExportValueNodeGen.create();
                @Child private JSFunctionCallNode call = JSFunctionCallNode.create(false);

                private Object[] exportArguments(Object[] argz) {
                    Object[] exported = new Object[argz.length];
                    for (int i = 0; i < argz.length; i++) {
                        exported[i] = export.executeWithTarget(argz[i], Undefined.instance);
                    }
                    return exported;
                }

                @Override
                public Object execute(VirtualFrame frame) {
                    assert frame.getArguments().length == 4;
                    assert JSFunction.isJSFunction(frame.getArguments()[2]);
                    assert JSFunction.isJSFunction(frame.getArguments()[3]);
                    DynamicObject resolve = (DynamicObject) frame.getArguments()[2];
                    DynamicObject reject = (DynamicObject) frame.getArguments()[3];

                    if (!isInteropMethod(method)) {
                        JSFunction.call(JSArguments.create(method, reject, "Invalid method"));
                    } else if (agent.isTerminated()) {
                        JSFunction.call(JSArguments.create(method, reject, "Java Worker terminated"));
                    } else {
                        Object[] argz = new Object[]{};
                        if (JSArray.isJSArray(arguments)) {
                            argz = JSArray.arrayGetArrayType((DynamicObject) arguments, true).toArray((DynamicObject) arguments);
                        }
                        final Object[] exportedArgs = exportArguments(argz);

                        // will schedule on the Java worker thread
                        agent.execute(mainAgent, new Runnable() {
                            @Override
                            public void run() {
                                Object[] javaResult = null;
                                try {
                                    Object val = call.executeCall(JSArguments.create(method, method, exportedArgs));
                                    javaResult = JSArguments.create(Undefined.instance, resolve, val);
                                } catch (Throwable t) {
                                    javaResult = JSArguments.create(Undefined.instance, reject, t);
                                }
                                final Object[] args = javaResult;
                                mainAgent.execute(agent, new Runnable() {

                                    @Override
                                    public void run() {
                                        // will be scheduled in the main thread
                                        JSFunction.call(args);
                                    }
                                });
                            }
                        });
                    }
                    return Undefined.instance;
                }
            });
            return promiseFunctionBody;
        }

    }

}
