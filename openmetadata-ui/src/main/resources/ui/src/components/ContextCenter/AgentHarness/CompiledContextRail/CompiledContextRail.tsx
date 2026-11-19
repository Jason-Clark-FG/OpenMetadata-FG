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
import {
  Box,
  ButtonGroup,
  ButtonGroupItem,
  Skeleton,
  Typography,
} from '@openmetadata/ui-core-components';
import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  PREVIEW_DELAY_MS,
  PREVIEW_EVENTS,
  PREVIEW_SAMPLE_REQUESTS,
  SEGMENT_COLOR_CLASSES,
} from '../../../../constants/discovery/agentHarness.constants';
import { AgentHarnessSpec } from '../../../../generated/api/context/agentHarnessState';
import { CompileEvent } from '../../../../generated/api/context/compileAgentContext';
import { CompiledAgentContext } from '../../../../generated/api/context/compiledAgentContext';
import { previewAgentHarness } from '../../../../rest/AgentHarnessAPI';
import { segmentsByKind } from '../../../../utils/discovery/context-center/agentHarness.utils';

export interface CompiledContextRailProps {
  harnessId: string;
  spec: AgentHarnessSpec;
  perTurnTokenBudget?: number;
  /** False while the local spec breaks a budget rule the server would reject. */
  isValid: boolean;
  /** The preview compiles unpublished changes, so the header says "Draft" instead of a time. */
  isDraft: boolean;
}

type Preview =
  | { kind: 'loading' }
  | { kind: 'error' }
  | { kind: 'ready'; context: CompiledAgentContext; compiledAt: Date };

const percentOf = (part: number, whole: number) =>
  whole > 0 ? Math.min(100, (part / whole) * 100) : 0;

const colorOf = (index: number) =>
  SEGMENT_COLOR_CLASSES[index % SEGMENT_COLOR_CLASSES.length];

const CompiledContextRail = ({
  harnessId,
  spec,
  perTurnTokenBudget,
  isValid,
  isDraft,
}: CompiledContextRailProps) => {
  const { t, i18n } = useTranslation();
  const [event, setEvent] = useState(CompileEvent.SessionStart);
  const [preview, setPreview] = useState<Preview>({ kind: 'loading' });
  const specKey = JSON.stringify(spec);
  const compact = useMemo(
    () =>
      new Intl.NumberFormat(i18n.language, {
        maximumFractionDigits: 1,
        notation: 'compact',
      }),
    [i18n.language]
  );

  useEffect(() => {
    if (!isValid) {
      return;
    }
    let current = true;
    setPreview({ kind: 'loading' });
    const timeout = window.setTimeout(() => {
      previewAgentHarness(harnessId, event, {
        ...PREVIEW_SAMPLE_REQUESTS[event],
        spec,
      })
        .then(
          (context) =>
            current &&
            setPreview({ compiledAt: new Date(), context, kind: 'ready' })
        )
        .catch(() => current && setPreview({ kind: 'error' }));
    }, PREVIEW_DELAY_MS);

    return () => {
      current = false;
      window.clearTimeout(timeout);
    };
  }, [harnessId, event, specKey, isValid]);

  const budget = perTurnTokenBudget ?? 0;

  const renderStatus = () => {
    if (isDraft) {
      return t('label.draft');
    }

    return preview.kind === 'ready' && isValid
      ? preview.compiledAt.toLocaleTimeString(i18n.language)
      : null;
  };

  const renderUsage = (context: CompiledAgentContext) => {
    const parts = segmentsByKind(context.segments);

    return (
      <>
        <Box data-testid="compiled-context-budget" direction="col" gap={2}>
          <Box align="center" justify="between">
            <Typography className="tw:text-secondary" size="text-sm">
              {t('label.per-turn-budget')}
            </Typography>
            <Typography
              data-testid="compiled-context-tokens"
              size="text-sm"
              weight="semibold">
              {t('message.agent-harness-tokens-of-budget', {
                budget: compact.format(budget),
                tokens: compact.format(context.tokens),
              })}
            </Typography>
          </Box>
          <div
            aria-hidden
            className="tw:flex tw:h-2 tw:w-full tw:gap-0.5 tw:overflow-hidden tw:rounded-full tw:bg-quaternary">
            {parts.map((segment, index) => (
              <div
                className={colorOf(index)}
                data-testid="compiled-context-bar-segment"
                key={segment.kind}
                style={{ width: `${percentOf(segment.tokens, budget)}%` }} // style-guard-allow: the width is this part's share of the per-turn budget
              />
            ))}
          </div>
        </Box>
        <ul
          className="tw:m-0 tw:flex tw:list-none tw:flex-col tw:gap-1.5 tw:p-0"
          data-testid="compiled-context-legend">
          {parts.map((segment, index) => (
            <li
              className="tw:flex tw:items-center tw:gap-2"
              data-testid="compiled-context-segment"
              key={segment.kind}>
              <span
                className={`tw:size-2 tw:shrink-0 tw:rounded-xs ${colorOf(
                  index
                )}`}
              />
              <Typography
                className="tw:flex-1 tw:text-secondary"
                size="text-sm">
                {segment.kind}
              </Typography>
              <Typography
                className="tw:text-tertiary tw:tabular-nums"
                size="text-sm">
                {t('message.agent-harness-token-count', {
                  tokens: compact.format(segment.tokens),
                })}
              </Typography>
            </li>
          ))}
        </ul>
      </>
    );
  };

  const renderPreviewAs = () => (
    <Box data-testid="compiled-context-preview-as" direction="col" gap={2}>
      <Typography className="tw:text-quaternary" size="text-xs" weight="medium">
        {t('label.preview-as')}
      </Typography>
      <ButtonGroup
        disallowEmptySelection
        aria-label={t('label.preview-as')}
        selectedKeys={[event]}
        size="sm"
        variant="pill"
        onSelectionChange={(keys) => {
          const [next] = [...keys];
          if (next) {
            setEvent(next as CompileEvent);
          }
        }}>
        {PREVIEW_EVENTS.map(({ event: id, labelKey }) => (
          <ButtonGroupItem data-testid={`preview-as-${id}`} id={id} key={id}>
            {t(labelKey)}
          </ButtonGroupItem>
        ))}
      </ButtonGroup>
    </Box>
  );

  const renderBody = () => {
    if (!isValid) {
      return (
        <>
          {renderPreviewAs()}
          <Typography className="tw:text-tertiary" size="text-sm">
            {t('message.agent-harness-preview-needs-valid-budgets')}
          </Typography>
        </>
      );
    }
    if (preview.kind === 'loading') {
      return (
        <>
          <Skeleton className="tw:h-2 tw:w-full" />
          {renderPreviewAs()}
          <Box data-testid="compiled-context-loading" direction="col">
            <Skeleton className="tw:h-40 tw:w-full" />
          </Box>
        </>
      );
    }
    if (preview.kind === 'error') {
      return (
        <>
          {renderPreviewAs()}
          <Typography className="tw:text-error-primary" size="text-sm">
            {t('message.agent-harness-preview-failed')}
          </Typography>
        </>
      );
    }

    return (
      <>
        {renderUsage(preview.context)}
        {renderPreviewAs()}
        <pre
          className="tw:m-0 tw:max-h-80 tw:overflow-auto tw:whitespace-pre-wrap tw:rounded-lg tw:bg-primary-solid tw:p-3.5 tw:font-mono tw:text-xs tw:text-white"
          data-testid="compiled-context-text">
          {preview.context.text || t('message.agent-harness-preview-empty')}
        </pre>
      </>
    );
  };

  return (
    <aside
      aria-label={t('label.compiled-context')}
      className="tw:sticky tw:top-4 tw:flex tw:min-w-70 tw:flex-[0_1_340px] tw:flex-col tw:gap-3.5 tw:self-start tw:rounded-xl tw:border tw:border-secondary tw:p-4.5"
      data-testid="compiled-context-rail">
      <Box align="center" justify="between">
        <h3 className="tw:m-0 tw:text-md tw:font-semibold tw:text-primary">
          {t('label.compiled-context')}
        </h3>
        <Typography
          className="tw:text-tertiary"
          data-testid="compiled-context-status"
          size="text-xs">
          {renderStatus()}
        </Typography>
      </Box>
      {renderBody()}
    </aside>
  );
};

export default CompiledContextRail;
