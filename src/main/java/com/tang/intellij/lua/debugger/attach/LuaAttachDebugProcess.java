/*
 * Copyright (c) 2017. tangzx(love.tangzx@qq.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tang.intellij.lua.debugger.attach;

import com.intellij.execution.process.ProcessInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.breakpoints.XBreakpointHandler;
import com.intellij.xdebugger.breakpoints.XBreakpointProperties;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider;
import com.intellij.xdebugger.frame.XSuspendContext;
import com.tang.intellij.lua.debugger.LuaDebuggerEditorsProvider;
import com.tang.intellij.lua.debugger.LuaLineBreakpointType;
import com.tang.intellij.lua.debugger.LuaSuspendContext;
import com.tang.intellij.lua.debugger.attach.protos.LuaAttachBreakProto;
import com.tang.intellij.lua.debugger.attach.protos.LuaAttachLoadScriptProto;
import com.tang.intellij.lua.debugger.attach.protos.LuaAttachProto;
import com.tang.intellij.lua.psi.LuaFileUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 *
 * Created by tangzx on 2017/3/26.
 */
public class LuaAttachDebugProcess extends XDebugProcess implements LuaAttachBridge.ProtoHandler {
    private LuaDebuggerEditorsProvider editorsProvider;
    private ProcessInfo processInfo;
    private LuaAttachBridge bridge;
    private Map<XSourcePosition, XLineBreakpoint> registeredBreakpoints = new ConcurrentHashMap<>();

    LuaAttachDebugProcess(@NotNull XDebugSession session, ProcessInfo processInfo) {
        super(session);
        this.processInfo = processInfo;
        editorsProvider = new LuaDebuggerEditorsProvider();
        bridge = new LuaAttachBridge(processInfo);
        bridge.setProtoHandler(this);
        bridge.start();
    }

    @NotNull
    @Override
    public XDebuggerEditorsProvider getEditorsProvider() {
        return editorsProvider;
    }

    @Override
    public void startStepOver(@Nullable XSuspendContext context) {
        bridge.send("stepover");
    }

    @Override
    public void startStepInto(@Nullable XSuspendContext context) {
        bridge.send("stepinto");
    }

    @Override
    public void stop() {
        bridge.stop();
    }

    @Override
    public void handle(LuaAttachProto proto) {
        int type = proto.getType();
        switch (type) {
            case LuaAttachProto.Message:
                break;
            case LuaAttachProto.LoadScript:
                LuaAttachLoadScriptProto loadScriptProto = (LuaAttachLoadScriptProto) proto;
                onLoadScript(loadScriptProto);
                break;
            case LuaAttachProto.SessionEnd:
                bridge.stop();
                break;
            case LuaAttachProto.Break:
                onBreak((LuaAttachBreakProto) proto);
                break;
        }
    }

    private void onBreak(LuaAttachBreakProto proto) {
        VirtualFile file = LuaFileUtil.findFile(getSession().getProject(), proto.getName());
        if (file == null)
            return;
        for (XSourcePosition pos : registeredBreakpoints.keySet()) {
            if (file.equals(pos.getFile()) && proto.getLine() == pos.getLine()) {
                final XLineBreakpoint breakpoint = registeredBreakpoints.get(pos);
                ApplicationManager.getApplication().invokeLater(()-> {
                    getSession().breakpointReached(breakpoint, null, new LuaSuspendContext(null));
                    getSession().showExecutionPoint();
                });
                break;
            }
        }
    }

    private void onLoadScript(LuaAttachLoadScriptProto proto) {
        VirtualFile file = LuaFileUtil.findFile(getSession().getProject(), proto.getName());
        if (file != null) {
            for (XSourcePosition pos : registeredBreakpoints.keySet()) {
                if (file.equals(pos.getFile())) {
                    bridge.send(String.format("setb %d %d", proto.getIndex(), pos.getLine()));
                }
            }
        }
        bridge.send("done");
    }

    @NotNull
    @Override
    public XBreakpointHandler<?>[] getBreakpointHandlers() {
        return new XBreakpointHandler[] { new XBreakpointHandler<XLineBreakpoint<XBreakpointProperties>>(LuaLineBreakpointType.class) {
            @Override
            public void registerBreakpoint(@NotNull XLineBreakpoint<XBreakpointProperties> breakpoint) {
                registeredBreakpoints.put(breakpoint.getSourcePosition(), breakpoint);
            }

            @Override
            public void unregisterBreakpoint(@NotNull XLineBreakpoint<XBreakpointProperties> breakpoint, boolean temporary) {

            }
        } };
    }
}
