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
 * Agent host settings that enforce the default harness's MCP access before any hook runs,
 * returned by `GET /v1/agentHarness/managedSettings`. An administrator pastes them into the
 * host's managed configuration.
 */
export interface AgentHarnessManagedSettings {
    /**
     * JSON for Claude Code managed settings: `deniedMcpServers` for blocked servers and, when
     * the default denies unlisted servers, `allowedMcpServers` with
     * `allowManagedMcpServersOnly`.
     */
    claudeCode: { [key: string]: any };
    /**
     * The `mcp_servers` allowlist for Codex `requirements.toml`. Empty when the default allows
     * unlisted servers, because Codex has no denylist.
     */
    codexRequirementsToml: string;
    /**
     * What the settings cannot express, such as entries limited to some tools, which only the
     * hooks enforce.
     */
    notes: string[];
    /**
     * Name the OpenMetadata MCP server must have in the agent host's configuration for these
     * settings to keep it allowed.
     */
    serverName: string;
    /**
     * URL of the OpenMetadata MCP server.
     */
    serverUrl: string;
}
