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
import { PagingResponse } from 'Models';
import { AgentHarnessPersonaSummary } from '../generated/api/context/agentHarnessPersonaSummary';
import {
  AgentHarnessSpec,
  AgentHarnessState,
  ContextRule,
} from '../generated/api/context/agentHarnessState';
import { CompileEvent } from '../generated/api/context/compileAgentContext';
import { CompiledAgentContext } from '../generated/api/context/compiledAgentContext';
import { PreviewAgentHarness } from '../generated/api/context/previewAgentHarness';
import { getEncodedFqn } from '../utils/StringUtils';
import axiosClient from './axiosClient';

const BASE_URL = '/agentHarness';

export interface AgentHarnessRulePreview {
  matchedCount: number;
  sampleNames: string[];
}

export interface AgentHarnessPersonaParams {
  limit?: number;
  before?: string;
  after?: string;
}

const ifMatch = (revision: number) => ({
  headers: { 'If-Match': String(revision) },
});

export const getDefaultAgentHarness = async () => {
  const response = await axiosClient.get<AgentHarnessState>(
    `${BASE_URL}/default`
  );

  return response.data;
};

export const getPersonaAgentHarness = async (personaFqn: string) => {
  const response = await axiosClient.get<AgentHarnessState>(
    `${BASE_URL}/persona/${getEncodedFqn(personaFqn)}`
  );

  return response.data;
};

export const createPersonaAgentHarness = async (personaFqn: string) => {
  const response = await axiosClient.post<AgentHarnessState>(
    `${BASE_URL}/persona/${getEncodedFqn(personaFqn)}`
  );

  return response.data;
};

export const listAgentHarnessPersonas = async (
  params: AgentHarnessPersonaParams
) => {
  const response = await axiosClient.get<
    PagingResponse<AgentHarnessPersonaSummary[]>
  >(`${BASE_URL}/personas`, { params });

  return response.data;
};

export const putAgentHarnessDraft = async (
  harnessId: string,
  revision: number,
  spec: AgentHarnessSpec
) => {
  const response = await axiosClient.put<AgentHarnessState>(
    `${BASE_URL}/${harnessId}/draft`,
    spec,
    ifMatch(revision)
  );

  return response.data;
};

export const publishAgentHarness = async (
  harnessId: string,
  revision: number
) => {
  const response = await axiosClient.post<AgentHarnessState>(
    `${BASE_URL}/${harnessId}/publish`,
    undefined,
    ifMatch(revision)
  );

  return response.data;
};

export const setAgentHarnessEnabled = async (
  harnessId: string,
  enabled: boolean
) => {
  const response = await axiosClient.put<AgentHarnessState>(
    `${BASE_URL}/${harnessId}/enabled`,
    enabled,
    { headers: { 'Content-Type': 'application/json' } }
  );

  return response.data;
};

export const previewAgentHarness = async (
  harnessId: string,
  event: CompileEvent,
  request: PreviewAgentHarness
) => {
  const response = await axiosClient.post<CompiledAgentContext>(
    `${BASE_URL}/${harnessId}/preview`,
    request,
    { params: { event } }
  );

  return response.data;
};

export const previewAgentHarnessRule = async (
  harnessId: string,
  rule: ContextRule
) => {
  const response = await axiosClient.post<AgentHarnessRulePreview>(
    `${BASE_URL}/${harnessId}/rules/preview`,
    rule
  );

  return response.data;
};
