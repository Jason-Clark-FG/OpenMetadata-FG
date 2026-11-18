/*
 *  Copyright 2026 Collate.
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
/**
 * Request to compile the context the calling user's agent receives at one hook event, sent
 * to `POST /v1/agentHarness/compile`.
 */
export interface CompileAgentContext {
    /**
     * The hook event to compile for.
     */
    event: CompileEvent;
    /**
     * The user's prompt, for UserPromptSubmit.
     */
    prompt?: string;
    /**
     * Input of the tool call as the agent host reports it, for PreToolUse.
     */
    toolInput?: { [key: string]: any };
    /**
     * Name of the tool the agent is about to call or has called, for PreToolUse and PostToolUse.
     */
    toolName?: string;
    /**
     * Output of the tool call as the agent host reports it, for PostToolUse. Any JSON value.
     */
    toolOutput?: any;
}

/**
 * The hook event to compile for.
 *
 * Hook event context can be compiled for.
 */
export enum CompileEvent {
    PostToolUse = "PostToolUse",
    PreToolUse = "PreToolUse",
    SessionStart = "SessionStart",
    UserPromptSubmit = "UserPromptSubmit",
}
