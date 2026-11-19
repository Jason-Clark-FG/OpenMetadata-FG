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
  Badge,
  Box,
  Button,
  Card,
  Dropdown,
  TextArea,
  Tooltip,
  Typography,
} from '@openmetadata/ui-core-components';
import { Plus } from '@openmetadata/ui-core-components/icons';
import { useTranslation } from 'react-i18next';
import { PERSONA_CONTEXT_PROMPT_MAX_LENGTH } from '../../../../constants/discovery/contextRule.constants';
import {
  AgentHarnessSpec,
  HarnessKind,
  ResolvedAgentHarness,
} from '../../../../generated/api/context/agentHarnessState';
import { SkillDefinition } from '../../../../generated/api/context/skillDefinition';
import { SkillRow } from '../../../../interface/discovery/agentHarness.interface';
import {
  addableSkills,
  skillRows,
  withSkill,
} from '../../../../utils/discovery/context-center/agentHarness.utils';
import HarnessToggle from '../HarnessToggle/HarnessToggle';

export interface InstructionsTabProps {
  kind: HarnessKind;
  spec: AgentHarnessSpec;
  resolved: ResolvedAgentHarness;
  skills: SkillDefinition[];
  showInherited: boolean;
  canEdit: boolean;
  onChange: (change: (spec: AgentHarnessSpec) => AgentHarnessSpec) => void;
}

const InstructionsTab = ({
  kind,
  spec,
  resolved,
  skills,
  showInherited,
  canEdit,
  onChange,
}: InstructionsTabProps) => {
  const { t } = useTranslation();
  const isPersona = kind === HarnessKind.Persona;
  const rows = skillRows(spec, resolved, skills);
  const visibleRows = rows.filter(
    ({ inherited }) => showInherited || !inherited
  );
  const addable = addableSkills(rows, skills);

  const renderCompanyInstructions = () => {
    if (!isPersona) {
      return (
        <Card data-testid="company-instructions">
          <Card.Content>
            <TextArea
              hint={t('message.agent-harness-company-instructions-hint')}
              isDisabled={!canEdit}
              label={t('label.company-instructions')}
              placeholder={t(
                'message.agent-harness-company-instructions-placeholder'
              )}
              rows={6}
              textAreaClassName="tw:font-mono"
              value={spec.companyInstructions ?? ''}
              onChange={(companyInstructions) =>
                onChange((current) => ({ ...current, companyInstructions }))
              }
            />
          </Card.Content>
        </Card>
      );
    }
    if (!showInherited) {
      return null;
    }

    return (
      <Card color="secondary" data-testid="company-instructions">
        <Card.Header
          extra={
            <Badge color="gray" size="sm">
              {t('label.inherited-locked')}
            </Badge>
          }
          subtitle={t('message.agent-harness-company-instructions-hint')}
          title={t('label.company-instructions')}
        />
        <Card.Content>
          <Typography
            as="p"
            className="tw:m-0 tw:whitespace-pre-wrap tw:font-mono tw:text-secondary"
            data-testid="company-instructions-text"
            size="text-sm">
            {resolved.companyInstructions ||
              t('message.agent-harness-no-company-instructions')}
          </Typography>
        </Card.Content>
      </Card>
    );
  };

  const renderSkill = (row: SkillRow) => (
    <Box
      align="center"
      className="tw:border-t tw:border-secondary tw:px-4 tw:py-3 tw:first:border-t-0"
      data-testid={`skill-${row.name}`}
      gap={4}
      key={row.name}>
      <Box className="tw:min-w-0 tw:flex-1" direction="col" gap={1}>
        <Box align="center" gap={2} wrap="wrap">
          <Typography className="tw:font-mono" size="text-sm" weight="semibold">
            {row.name}
          </Typography>
          {row.inherited && (
            <Badge color="gray" size="sm" type="pill-color">
              {t('label.from-default')}
            </Badge>
          )}
        </Box>
        <Typography className="tw:text-tertiary" size="text-sm">
          {row.description}
        </Typography>
      </Box>
      <HarnessToggle
        aria-label={t('label.enable-entity', { entity: row.name })}
        data-testid={`skill-enabled-${row.name}`}
        inherited={row.inherited}
        isDisabled={!canEdit}
        isSelected={row.enabled}
        onChange={(enabled) =>
          onChange((current) => withSkill(current, row.name, enabled))
        }
      />
    </Box>
  );

  const renderAddSkill = () => {
    const button = (
      <Button
        color="secondary"
        data-testid="add-skill"
        iconLeading={Plus}
        isDisabled={addable.length === 0}
        size="sm">
        {t('label.add-skill')}
      </Button>
    );

    if (addable.length === 0) {
      return (
        <Tooltip title={t('message.agent-harness-no-skills-to-add')}>
          {button}
        </Tooltip>
      );
    }

    return (
      <Dropdown.Root>
        {button}
        <Dropdown.Popover className="tw:w-80">
          <Dropdown.Menu
            aria-label={t('label.add-skill')}
            selectionMode="none"
            onAction={(name) =>
              onChange((current) => withSkill(current, String(name), true))
            }>
            {addable.map((skill) => (
              <Dropdown.Item
                data-testid={`add-skill-${skill.name}`}
                id={skill.name}
                key={skill.name}
                label={skill.name}
              />
            ))}
          </Dropdown.Menu>
        </Dropdown.Popover>
      </Dropdown.Root>
    );
  };

  return (
    <Box data-testid="instructions-tab" direction="col" gap={4}>
      {renderCompanyInstructions()}
      {isPersona && (
        <Card data-testid="persona-prompt">
          <Card.Content>
            <TextArea
              hint={t('message.agent-harness-persona-prompt-hint')}
              isDisabled={!canEdit}
              label={t('label.persona-prompt')}
              maxLength={PERSONA_CONTEXT_PROMPT_MAX_LENGTH}
              placeholder={t('message.persona-context-prompt-placeholder')}
              rows={5}
              value={spec.context?.prompt ?? ''}
              onChange={(prompt) =>
                onChange((current) => ({
                  ...current,
                  context: { ...current.context, prompt },
                }))
              }
            />
          </Card.Content>
        </Card>
      )}
      <Box data-testid="skills" direction="col" gap={3}>
        <Box align="center" gap={2} justify="between">
          <h3 className="tw:m-0 tw:text-lg tw:font-semibold tw:text-primary">
            {t('label.skill-plural')}
          </h3>
          {canEdit && renderAddSkill()}
        </Box>
        <Card>
          {visibleRows.length === 0 ? (
            <Card.Content>
              <Typography className="tw:text-tertiary" size="text-sm">
                {t('message.agent-harness-no-skills')}
              </Typography>
            </Card.Content>
          ) : (
            visibleRows.map(renderSkill)
          )}
        </Card>
      </Box>
    </Box>
  );
};

export default InstructionsTab;
