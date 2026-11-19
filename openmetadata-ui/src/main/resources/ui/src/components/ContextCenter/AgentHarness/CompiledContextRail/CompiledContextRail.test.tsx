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
import { act, fireEvent, render, screen } from '@testing-library/react';
import { AgentHarnessSpec } from '../../../../generated/api/context/agentHarnessState';
import { CompileEvent } from '../../../../generated/api/context/compileAgentContext';
import { previewAgentHarness } from '../../../../rest/AgentHarnessAPI';
import CompiledContextRail from './CompiledContextRail';

jest.mock('../../../../rest/AgentHarnessAPI', () => ({
  previewAgentHarness: jest.fn(),
}));

const SPEC: AgentHarnessSpec = {
  context: { cacheTtlMinutes: 30, characterBudget: 400000 },
  perTurnTokenBudget: 20000,
};

const COMPILED = {
  harness: { default: { id: 'default-id', name: 'default' } },
  segments: [
    { kind: 'Instructions', tokens: 1200 },
    { kind: 'Glossary', tokens: 3800 },
  ],
  text: '# Company instructions',
  tokens: 5000,
};

const settle = () =>
  act(async () => {
    jest.advanceTimersByTime(600);
  });

describe('CompiledContextRail', () => {
  beforeEach(() => {
    jest.useFakeTimers();
    (previewAgentHarness as jest.Mock).mockResolvedValue(COMPILED);
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it('compiles the local settings and shows the parts against the per-turn budget', async () => {
    render(
      <CompiledContextRail
        isValid
        harnessId="harness-1"
        isDraft={false}
        perTurnTokenBudget={20000}
        spec={SPEC}
      />
    );
    await settle();

    expect(previewAgentHarness).toHaveBeenCalledWith(
      'harness-1',
      CompileEvent.SessionStart,
      { spec: SPEC }
    );
    expect(
      screen
        .getAllByTestId('compiled-context-segment')
        .map((row) => row.firstElementChild?.nextElementSibling?.textContent)
    ).toEqual(['Instructions', 'Glossary']);
    expect(screen.getAllByTestId('compiled-context-bar-segment')).toHaveLength(
      2
    );
    expect(screen.getByTestId('compiled-context-text')).toHaveTextContent(
      '# Company instructions'
    );
  });

  it('reads top to bottom as budget, legend, preview events, then the text', async () => {
    render(
      <CompiledContextRail
        isValid
        harnessId="harness-1"
        isDraft={false}
        perTurnTokenBudget={20000}
        spec={SPEC}
      />
    );
    await settle();

    const order = [
      'compiled-context-status',
      'compiled-context-budget',
      'compiled-context-legend',
      'compiled-context-preview-as',
      'compiled-context-text',
    ].map((id) => screen.getByTestId(id));

    order
      .slice(1)
      .forEach((node, index) =>
        expect(
          order[index].compareDocumentPosition(node) &
            Node.DOCUMENT_POSITION_FOLLOWING
        ).toBeTruthy()
      );
  });

  it('labels the preview of unpublished changes as a draft', async () => {
    render(
      <CompiledContextRail
        isDraft
        isValid
        harnessId="harness-1"
        perTurnTokenBudget={20000}
        spec={SPEC}
      />
    );
    await settle();

    expect(screen.getByTestId('compiled-context-status')).toHaveTextContent(
      'label.draft'
    );
  });

  it('previews a prompt with a sample prompt', async () => {
    render(
      <CompiledContextRail
        isValid
        harnessId="harness-1"
        isDraft={false}
        perTurnTokenBudget={20000}
        spec={SPEC}
      />
    );
    await settle();

    fireEvent.click(screen.getByTestId('preview-as-UserPromptSubmit'));
    await settle();

    expect(previewAgentHarness).toHaveBeenLastCalledWith(
      'harness-1',
      CompileEvent.UserPromptSubmit,
      expect.objectContaining({ prompt: expect.any(String), spec: SPEC })
    );
  });

  it('compiles once after a burst of edits', async () => {
    const { rerender } = render(
      <CompiledContextRail
        isValid
        harnessId="harness-1"
        isDraft={false}
        perTurnTokenBudget={20000}
        spec={SPEC}
      />
    );
    [19000, 18000, 17000].forEach((perTurnTokenBudget) =>
      rerender(
        <CompiledContextRail
          isValid
          harnessId="harness-1"
          isDraft={false}
          perTurnTokenBudget={perTurnTokenBudget}
          spec={{ ...SPEC, perTurnTokenBudget }}
        />
      )
    );
    await settle();

    expect(previewAgentHarness).toHaveBeenCalledTimes(1);
    expect(previewAgentHarness).toHaveBeenCalledWith(
      'harness-1',
      CompileEvent.SessionStart,
      { spec: { ...SPEC, perTurnTokenBudget: 17000 } }
    );
  });

  it('does not compile settings the server would reject', async () => {
    render(
      <CompiledContextRail
        harnessId="harness-1"
        isDraft={false}
        isValid={false}
        perTurnTokenBudget={20000}
        spec={SPEC}
      />
    );
    await settle();

    expect(previewAgentHarness).not.toHaveBeenCalled();
    expect(
      screen.getByText('message.agent-harness-preview-needs-valid-budgets')
    ).toBeInTheDocument();
  });
});
