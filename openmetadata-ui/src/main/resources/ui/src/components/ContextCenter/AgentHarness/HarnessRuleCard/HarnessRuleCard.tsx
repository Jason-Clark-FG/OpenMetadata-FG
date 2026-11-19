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
  Card,
  Dropdown,
  Typography,
} from '@openmetadata/ui-core-components';
import { Edit01, Trash01 } from '@openmetadata/ui-core-components/icons';
import { Fragment, Key, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  DEFAULT_PERSONA_CONTEXT_MAX_ASSETS,
  PERSONA_CONTEXT_ENTITY_LABEL_KEYS,
  PERSONA_CONTEXT_ENTITY_PLURAL_LABEL_KEYS,
  PERSONA_CONTEXT_SECTION_LABEL_KEYS,
} from '../../../../constants/discovery/contextRule.constants';
import { ContextRule } from '../../../../generated/type/personaContextDefinition';
import {
  getRuleQuery,
  isSearchScopedRule,
  ruleConditionText,
} from '../../../../utils/discovery/context-center/contextRule.utils';
import { DeleteModal } from '../../../common/DeleteModal/DeleteModal';

export interface HarnessRuleCardProps {
  rule: ContextRule;
  /** A rule the company default owns: shown on a persona harness, never edited there. */
  inherited: boolean;
  canEdit: boolean;
  onEdit: () => void;
  onDelete: () => void;
}

const MAX_VISIBLE_SECTIONS = 5;

const RULE_ACTIONS = { delete: 'delete', edit: 'edit' } as const;

const HarnessRuleCard = ({
  rule,
  inherited,
  canEdit,
  onEdit,
  onDelete,
}: HarnessRuleCardProps) => {
  const { t } = useTranslation();
  const [isConfirmOpen, setIsConfirmOpen] = useState(false);
  const query = getRuleQuery(rule);
  // Not rule.filteredInSearch alone: the server ignores that flag on knowledge rules.
  const isScoped = isSearchScopedRule(rule);
  const isEnabled = rule.enabled !== false;
  const sections = rule.sections ?? [];
  const extraSections = sections.length - MAX_VISIBLE_SECTIONS;

  const handleAction = (key: Key) =>
    key === RULE_ACTIONS.edit ? onEdit() : setIsConfirmOpen(true);

  const renderQuery = () => {
    switch (query.kind) {
      case 'all':
        return (
          <Badge
            className="tw:font-mono"
            color="gray"
            data-testid="rule-query-all"
            size="md"
            type="color">
            {t('label.all-entity', {
              entity: t(
                PERSONA_CONTEXT_ENTITY_PLURAL_LABEL_KEYS[rule.entityType] ??
                  'label.entity-plural'
              ),
            })}
          </Badge>
        );
      case 'count':
        return (
          <Badge className="tw:font-mono" color="gray" size="md" type="color">
            {t('message.persona-context-condition-count', {
              count: query.count,
            })}
          </Badge>
        );
      default:
        return query.conditions.map((condition, index) => (
          <Fragment key={ruleConditionText(condition)}>
            {index > 0 && (
              <Typography
                className="tw:font-mono tw:text-quaternary"
                size="text-xs">
                {query.conjunction}
              </Typography>
            )}
            <Badge
              className="tw:font-mono"
              color="gray"
              data-testid="rule-query-field"
              size="md"
              type="color">
              {condition.field}
            </Badge>
            <Typography
              className="tw:font-mono tw:text-tertiary"
              data-testid="rule-query-operator"
              size="text-sm">
              {condition.operator}
            </Typography>
            {condition.value && (
              <Badge
                className="tw:font-mono"
                color="brand"
                data-testid="rule-query-value"
                size="md"
                type="color">
                {condition.value}
              </Badge>
            )}
          </Fragment>
        ));
    }
  };

  const renderSections = () => {
    if (isScoped) {
      return null;
    }
    const chips = rule.fullyRendered
      ? [t('label.fully-rendered')]
      : [
          ...sections
            .slice(0, MAX_VISIBLE_SECTIONS)
            .map((section) => t(PERSONA_CONTEXT_SECTION_LABEL_KEYS[section])),
          ...(extraSections > 0
            ? [
                t('message.persona-context-more-sections', {
                  count: extraSections,
                }),
              ]
            : []),
        ];
    if (rule.alwaysInContext) {
      chips.push(t('label.always-in-context'));
    }

    return chips.length ? (
      <Box data-testid="rule-sections" gap={2} wrap="wrap">
        {chips.map((chip) => (
          <Badge color="gray" key={chip} size="sm" type="pill-color">
            {chip}
          </Badge>
        ))}
      </Box>
    ) : null;
  };

  const renderLimit = () => {
    if (!isEnabled) {
      return null;
    }
    if (isScoped) {
      return (
        <Box align="center" data-testid="rule-limit" gap={2} wrap="wrap">
          <Badge color="brand" size="sm" type="pill-color">
            {t('label.filtered-in-search')}
          </Badge>
          <Typography className="tw:text-tertiary" size="text-xs">
            {t('message.persona-context-scoped-rule-summary')}
          </Typography>
        </Box>
      );
    }

    return (
      <Typography
        className="tw:text-tertiary"
        data-testid="rule-limit"
        size="text-xs">
        {t('message.persona-context-max-assets', {
          count: rule.maxAssets ?? DEFAULT_PERSONA_CONTEXT_MAX_ASSETS,
        })}
      </Typography>
    );
  };

  return (
    <Card
      color={inherited ? 'secondary' : 'default'}
      data-testid={
        inherited ? 'inherited-context-rule-card' : 'context-rule-card'
      }>
      <Box className="tw:px-5 tw:py-4" direction="col" gap={3}>
        <Box align="center" gap={2} wrap="wrap">
          <Typography
            data-testid="context-rule-card-name"
            size="text-md"
            weight="semibold">
            {rule.name}
          </Typography>
          <Badge color="gray" size="sm" type="modern">
            {t(
              PERSONA_CONTEXT_ENTITY_LABEL_KEYS[rule.entityType] ??
                'label.entity'
            )}
          </Badge>
          {!isEnabled && (
            <Badge color="gray" size="sm" type="pill-color">
              {t('label.disabled')}
            </Badge>
          )}
          {inherited && (
            <Badge color="gray" size="sm" type="pill-color">
              {t('label.inherited-locked')}
            </Badge>
          )}
          {canEdit && !inherited && (
            <Dropdown.Root>
              <Dropdown.DotsButton
                aria-label={t('label.more-action-plural')}
                className="tw:ml-auto"
                data-testid="context-rule-actions"
                size="sm"
              />
              <Dropdown.Popover>
                <Dropdown.Menu
                  aria-label={t('label.action-plural')}
                  selectionMode="none"
                  onAction={handleAction}>
                  <Dropdown.Item
                    data-testid="edit-context-rule"
                    icon={Edit01}
                    id={RULE_ACTIONS.edit}
                    label={t('label.edit')}
                  />
                  <Dropdown.Item
                    data-testid="delete-context-rule"
                    icon={Trash01}
                    id={RULE_ACTIONS.delete}
                    label={t('label.delete')}
                  />
                </Dropdown.Menu>
              </Dropdown.Popover>
            </Dropdown.Root>
          )}
        </Box>
        <Box align="center" data-testid="rule-query" gap={2} wrap="wrap">
          {renderQuery()}
        </Box>
        {renderSections()}
        {renderLimit()}
      </Box>
      <DeleteModal
        entityTitle={rule.name}
        message={t('message.delete-persona-context-rule-confirmation')}
        open={isConfirmOpen}
        onCancel={() => setIsConfirmOpen(false)}
        onDelete={() => {
          setIsConfirmOpen(false);
          onDelete();
        }}
      />
    </Card>
  );
};

export default HarnessRuleCard;
