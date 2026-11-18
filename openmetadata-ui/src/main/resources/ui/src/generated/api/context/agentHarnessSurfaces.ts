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
 * The AI surfaces an agent harness reaches, and whether each has called OpenMetadata lately,
 * returned by `GET /v1/agentHarness/surfaces`.
 */
export interface AgentHarnessSurfaces {
    /**
     * One entry per surface.
     */
    surfaces: Surface[];
}

export interface Surface {
    /**
     * The surface connects to the OpenMetadata MCP server.
     */
    connector: boolean;
    /**
     * The surface runs the plugin's hooks, which deliver the harness at each event.
     */
    hooks: boolean;
    id:    SurfaceID;
    /**
     * Time of the surface's latest MCP tool call in the last 7 days (epoch millis, UTC). Absent
     * when there was none.
     */
    lastSeenAt?: number;
    /**
     * Plugin version the surface last reported. Absent when the surface does not report one.
     */
    lastPluginVersion?: string;
    /**
     * Display name of the surface.
     */
    name: string;
    /**
     * The surface loads the plugin's skill.
     */
    skills: boolean;
    status: SurfaceStatus;
}

/**
 * An AI surface that can connect to OpenMetadata.
 */
export enum SurfaceID {
    ChatGPT = "chatGpt",
    ClaudeChat = "claudeChat",
    ClaudeCode = "claudeCode",
    ClaudeDesktop = "claudeDesktop",
    Codex = "codex",
    Cowork = "cowork",
}

/**
 * Connected when the surface called an MCP tool in the last 7 days. The surface names itself
 * in its User-Agent, so this is a usage signal and not attestation.
 */
export enum SurfaceStatus {
    Connected = "Connected",
    NotConnected = "NotConnected",
}
