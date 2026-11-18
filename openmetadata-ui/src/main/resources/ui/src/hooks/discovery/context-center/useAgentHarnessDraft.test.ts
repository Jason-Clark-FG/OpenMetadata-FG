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
import { act, renderHook, waitFor } from '@testing-library/react';
import { AxiosError } from 'axios';
import {
  AgentHarnessSpec,
  AgentHarnessState,
  HarnessKind,
  HarnessStatus,
  HookEvent,
  MemoryReview,
  MemoryVisibility,
  ToolAccessDefault,
  ToolAccessLevel,
} from '../../../generated/api/context/agentHarnessState';
import {
  getPersonaAgentHarness,
  publishAgentHarness,
  putAgentHarnessDraft,
} from '../../../rest/AgentHarnessAPI';
import { showInfoToast } from '../../../utils/ToastUtils';
import { useAgentHarnessDraft } from './useAgentHarnessDraft';

jest.mock('../../../rest/AgentHarnessAPI', () => ({
  getDefaultAgentHarness: jest.fn(),
  getPersonaAgentHarness: jest.fn(),
  publishAgentHarness: jest.fn(),
  putAgentHarnessDraft: jest.fn(),
  setAgentHarnessEnabled: jest.fn(),
}));

jest.mock('../../../utils/ToastUtils', () => ({
  showErrorToast: jest.fn(),
  showInfoToast: jest.fn(),
}));

const TARGET = { kind: 'persona', personaFqn: 'Analyst' } as const;

const SPEC: AgentHarnessSpec = {
  context: { cacheTtlMinutes: 30, characterBudget: 400000, rules: [] },
  perTurnTokenBudget: 20000,
};

const stateWith = (
  spec: AgentHarnessSpec,
  draftRevision: number,
  hasDraft = true
): AgentHarnessState => ({
  draft: hasDraft ? spec : undefined,
  draftRevision,
  published: {
    context: { cacheTtlMinutes: 30, characterBudget: 400000, rules: [] },
    id: 'harness-1',
    kind: HarnessKind.Persona,
    name: 'Analyst',
    perTurnTokenBudget: 20000,
  },
  resolved: {
    cacheTtlMinutes: 30,
    characterBudget: 400000,
    events: [
      {
        enabled: true,
        id: HookEvent.SessionStart,
        inherited: true,
        maxTokenBudget: 6000,
        tokenBudget: 6000,
      },
    ],
    guardrails: [],
    kind: HarnessKind.Persona,
    memory: {
      capture: true,
      dedup: true,
      linkAssets: true,
      review: MemoryReview.StewardReview,
      visibility: MemoryVisibility.Private,
    },
    memoryInherited: true,
    perTurnTokenBudget: 20000,
    rules: [],
    skills: [],
  },
  status: hasDraft ? HarnessStatus.Draft : HarnessStatus.Published,
});

const httpError = (status: number) =>
  Object.assign(new AxiosError('failed'), {
    response: { status, data: {} },
  });

const withBudget =
  (perTurnTokenBudget: number) => (spec: AgentHarnessSpec) => ({
    ...spec,
    perTurnTokenBudget,
  });

const renderDraft = async () => {
  const hook = renderHook(() => useAgentHarnessDraft(TARGET));
  await waitFor(() => expect(hook.result.current.load.kind).toBe('ready'));

  return hook;
};

const specOf = (hook: Awaited<ReturnType<typeof renderDraft>>) => {
  const { load } = hook.result.current;

  return load.kind === 'ready' ? load.spec : undefined;
};

describe('useAgentHarnessDraft', () => {
  beforeEach(() => {
    jest.useFakeTimers();
    (getPersonaAgentHarness as jest.Mock).mockResolvedValue(stateWith(SPEC, 3));
    (putAgentHarnessDraft as jest.Mock).mockImplementation(
      async (_id, revision, spec) => stateWith(spec, revision + 1)
    );
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it('writes a burst of edits once, after the pause, against the draft revision', async () => {
    const hook = await renderDraft();

    act(() => hook.result.current.update(withBudget(18000)));
    act(() => hook.result.current.update(withBudget(16000)));

    expect(hook.result.current.saveState).toBe('pending');
    expect(putAgentHarnessDraft).not.toHaveBeenCalled();

    await act(async () => {
      jest.advanceTimersByTime(800);
    });

    expect(putAgentHarnessDraft).toHaveBeenCalledTimes(1);
    expect(putAgentHarnessDraft).toHaveBeenCalledWith('harness-1', 3, {
      ...SPEC,
      perTurnTokenBudget: 16000,
    });
    expect(hook.result.current.saveState).toBe('saved');

    act(() => hook.result.current.update(withBudget(15000)));
    await act(async () => {
      jest.advanceTimersByTime(800);
    });

    expect(putAgentHarnessDraft).toHaveBeenLastCalledWith('harness-1', 4, {
      ...SPEC,
      perTurnTokenBudget: 15000,
    });
  });

  it('holds back an edit the server would reject', async () => {
    const hook = await renderDraft();

    act(() => hook.result.current.update(withBudget(5000)));
    await act(async () => {
      jest.advanceTimersByTime(800);
    });

    expect(putAgentHarnessDraft).not.toHaveBeenCalled();
    expect(hook.result.current.saveState).toBe('invalid');
    expect(hook.result.current.errors.perTurnTokenBudget).toBe('belowEvents');
  });

  it('holds back MCP access with an unnamed server', async () => {
    const hook = await renderDraft();

    act(() =>
      hook.result.current.update((spec) => ({
        ...spec,
        toolAccess: {
          default: ToolAccessDefault.Allow,
          servers: [{ level: ToolAccessLevel.Block, match: ' ' }],
        },
      }))
    );
    await act(async () => {
      jest.advanceTimersByTime(800);
    });

    expect(putAgentHarnessDraft).not.toHaveBeenCalled();
    expect(hook.result.current.saveState).toBe('invalid');
    expect(hook.result.current.toolErrors).toEqual({
      servers: { 0: 'matchRequired' },
    });
  });

  it('reloads the server draft when someone else changed it', async () => {
    const hook = await renderDraft();
    const theirs = { ...SPEC, perTurnTokenBudget: 12000 };
    (putAgentHarnessDraft as jest.Mock).mockRejectedValueOnce(httpError(412));
    (getPersonaAgentHarness as jest.Mock).mockResolvedValue(
      stateWith(theirs, 7)
    );

    act(() => hook.result.current.update(withBudget(18000)));
    await act(async () => {
      jest.advanceTimersByTime(800);
    });

    expect(showInfoToast).toHaveBeenCalledWith(
      'message.agent-harness-changed-elsewhere'
    );
    expect(specOf(hook)).toEqual(theirs);
    expect(hook.result.current.saveState).toBe('saved');
  });

  it('saves a pending edit before publishing it', async () => {
    const hook = await renderDraft();
    (publishAgentHarness as jest.Mock).mockImplementation(async () =>
      stateWith({ ...SPEC, perTurnTokenBudget: 18000 }, 5, false)
    );

    act(() => hook.result.current.update(withBudget(18000)));
    await act(async () => {
      await hook.result.current.publish();
    });

    expect(putAgentHarnessDraft).toHaveBeenCalledWith('harness-1', 3, {
      ...SPEC,
      perTurnTokenBudget: 18000,
    });
    expect(publishAgentHarness).toHaveBeenCalledWith('harness-1', 4);

    const { load } = hook.result.current;

    expect(load.kind === 'ready' && load.state.status).toBe(
      HarnessStatus.Published
    );
  });

  it('reports a persona without a harness as missing', async () => {
    (getPersonaAgentHarness as jest.Mock).mockRejectedValue(httpError(404));
    const hook = renderHook(() => useAgentHarnessDraft(TARGET));

    await waitFor(() => expect(hook.result.current.load.kind).toBe('missing'));
  });
});
