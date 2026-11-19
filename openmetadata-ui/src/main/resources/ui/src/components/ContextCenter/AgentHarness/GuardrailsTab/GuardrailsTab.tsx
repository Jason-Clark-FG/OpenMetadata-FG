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
import { Badge, Box, Card, Typography } from '@openmetadata/ui-core-components';
import classNames from 'classnames';
import { useTranslation } from 'react-i18next';
import {
  GUARDRAIL_LEVEL_LABEL_KEYS,
  GUARDRAIL_LEVEL_ORDER,
} from '../../../../constants/discovery/agentHarness.constants';
import {
  AgentHarnessSpec,
  GuardrailLevel,
  HarnessKind,
  ResolvedAgentHarness,
} from '../../../../generated/api/context/agentHarnessState';
import { GuardrailDefinition } from '../../../../generated/api/context/guardrailDefinition';
import {
  GuardrailRow,
  McpConnection,
  ToolAccessErrors,
} from '../../../../interface/discovery/agentHarness.interface';
import {
  allowedLevels,
  guardrailRows,
  withGuardrailLevel,
} from '../../../../utils/discovery/context-center/agentHarness.utils';
import McpAccessCard from '../McpAccessCard/McpAccessCard';
import SegmentedControl from '../SegmentedControl/SegmentedControl';

export interface GuardrailsTabProps {
  kind: HarnessKind;
  spec: AgentHarnessSpec;
  resolved: ResolvedAgentHarness;
  guardrails: GuardrailDefinition[];
  /** Outside MCP servers registered here; empty when none or the request failed. */
  connections?: McpConnection[];
  /** Connections the knowledge graph tool may call under this harness; others get no coverage note. */
  enabledConnections?: string[];
  toolAccessErrors: ToolAccessErrors;
  showInherited: boolean;
  canEdit: boolean;
  onChange: (change: (spec: AgentHarnessSpec) => AgentHarnessSpec) => void;
}

const GuardrailsTab = ({
  kind,
  spec,
  resolved,
  guardrails,
  connections = [],
  enabledConnections,
  toolAccessErrors,
  showInherited,
  canEdit,
  onChange,
}: GuardrailsTabProps) => {
  const { t } = useTranslation();
  const isPersona = kind === HarnessKind.Persona;

  const renderRow = (row: GuardrailRow) => {
    const allowed = allowedLevels(row.minLevel);

    return (
      <Box
        align="center"
        className={classNames(
          'tw:border-t tw:border-secondary tw:px-4 tw:py-4 tw:first:border-t-0',
          { 'tw:opacity-55': !row.available }
        )}
        data-testid={`guardrail-${row.id}`}
        gap={4}
        key={row.id}
        wrap="wrap">
        <Box className="tw:min-w-60 tw:flex-1" direction="col" gap={1}>
          <Box align="center" gap={2} wrap="wrap">
            <Typography size="text-sm" weight="semibold">
              {row.name}
            </Typography>
            {row.minLevel && row.minLevel !== GuardrailLevel.Off && (
              <Badge color="gray" size="sm">
                {t('label.default-minimum-level', {
                  level: t(GUARDRAIL_LEVEL_LABEL_KEYS[row.minLevel]),
                })}
              </Badge>
            )}
            {!row.available && (
              <Badge color="warning" size="sm">
                {t('message.agent-harness-needs-answer-harness')}
              </Badge>
            )}
          </Box>
          <Typography className="tw:text-tertiary" size="text-sm">
            {row.description}
          </Typography>
        </Box>
        <SegmentedControl
          aria-label={t('label.level-of-entity', { entity: row.name })}
          data-testid={`guardrail-level-${row.id}`}
          isDisabled={!canEdit || !row.available}
          options={GUARDRAIL_LEVEL_ORDER.map((level) => ({
            id: level,
            isDisabled: !allowed.includes(level),
            label: t(GUARDRAIL_LEVEL_LABEL_KEYS[level]),
          }))}
          value={row.level}
          onChange={(level: GuardrailLevel) =>
            onChange((current) => withGuardrailLevel(current, row.id, level))
          }
        />
      </Box>
    );
  };

  return (
    <Box data-testid="guardrails-tab" direction="col" gap={4}>
      <Typography className="tw:text-tertiary" size="text-sm">
        {t('message.agent-harness-guardrails-intro')}
      </Typography>
      <Card>{guardrailRows(spec, resolved, guardrails).map(renderRow)}</Card>
      <McpAccessCard
        access={spec.toolAccess}
        canEdit={canEdit}
        connections={connections}
        enabledConnections={enabledConnections}
        errors={toolAccessErrors}
        inherited={resolved.inheritedToolAccess}
        isPersona={isPersona}
        showInherited={showInherited}
        onChange={onChange}
      />
    </Box>
  );
};

export default GuardrailsTab;
