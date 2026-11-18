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
 * A built-in skill the server can offer to agents, as listed by `GET
 * /v1/agentHarness/skills`. Skills are defined in server code. A harness only turns them on
 * or off.
 */
export interface SkillDefinition {
    /**
     * Whether the company default is seeded with the skill turned on.
     */
    defaultEnabled: boolean;
    /**
     * What the skill teaches an agent to do.
     */
    description: string;
    /**
     * Stable name a harness uses to turn the skill on or off.
     */
    name: string;
}
