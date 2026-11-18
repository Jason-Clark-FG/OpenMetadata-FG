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
  Typography,
} from '@openmetadata/ui-core-components';
import { Plus } from '@openmetadata/ui-core-components/icons';
import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { v4 as uuidv4 } from 'uuid';
import {
  MAX_HARNESS_RULES,
  SOURCE_CARD_CHIPS,
  SOURCE_ORDER,
} from '../../../../constants/discovery/agentHarness.constants';
import {
  AgentHarnessSpec,
  ContextRule,
  ResolvedAgentHarness,
} from '../../../../generated/api/context/agentHarnessState';
import {
  HarnessSource,
  PickableSource,
} from '../../../../interface/discovery/agentHarness.interface';
import { searchHarnessSource } from '../../../../rest/AgentHarnessAPI';
import {
  inheritedRules,
  ownRules,
  sourceCounts,
  visibleRules,
  withoutRule,
  withPickedIds,
  withRule,
} from '../../../../utils/discovery/context-center/agentHarness.utils';
import { getEntityName } from '../../../../utils/EntityNameUtils';
import { ContextRuleCard } from '../ContextRuleCard/ContextRuleCard.component';
import { ContextRuleEditor } from '../ContextRuleEditor/ContextRuleEditor.component';
import SourceCard from '../SourceCard/SourceCard';
import SourcePickerDrawer from '../SourcePickerDrawer/SourcePickerDrawer';

export interface ContextTabProps {
  harnessId: string;
  spec: AgentHarnessSpec;
  resolved: ResolvedAgentHarness;
  showInherited: boolean;
  canEdit: boolean;
  onChange: (change: (spec: AgentHarnessSpec) => AgentHarnessSpec) => void;
}

type PickedNames = Partial<Record<PickableSource, string[]>>;

type RuleEditorState = { open: false } | { open: true; rule?: ContextRule };

const ContextTab = ({
  harnessId,
  spec,
  resolved,
  showInherited,
  canEdit,
  onChange,
}: ContextTabProps) => {
  const { t } = useTranslation();
  const [picking, setPicking] = useState<PickableSource>();
  const [editor, setEditor] = useState<RuleEditorState>({ open: false });
  const [pickedNames, setPickedNames] = useState<PickedNames>({});

  const own = ownRules(spec);
  const counts = sourceCounts(own);
  const inheritedCounts = useMemo(
    () => sourceCounts(inheritedRules(resolved)),
    [resolved]
  );
  const assetRules = visibleRules(resolved, spec, showInherited).filter(
    ({ rule }) => !rule.picked
  );
  const ownAssetRules = own.filter(({ picked }) => !picked);
  const chipIds = SOURCE_ORDER.filter(
    (source): source is PickableSource => source !== HarnessSource.Assets
  ).map(
    (source) =>
      [source, counts[source].pickedIds.slice(0, SOURCE_CARD_CHIPS)] as const
  );
  const chipKey = JSON.stringify(chipIds);

  useEffect(() => {
    let current = true;
    Promise.all(
      chipIds.map(async ([source, ids]) => [
        source,
        ids.length
          ? (await searchHarnessSource(source, { ids })).map(getEntityName)
          : [],
      ])
    )
      .then((entries) => current && setPickedNames(Object.fromEntries(entries)))
      .catch(() => current && setPickedNames({}));

    return () => {
      current = false;
    };
  }, [chipKey]);

  const sourceCount = (source: HarnessSource) =>
    source === HarnessSource.Assets
      ? counts[source].ruleCount
      : counts[source].pickedIds.length;

  const inheritedCount = (source: HarnessSource) =>
    source === HarnessSource.Assets
      ? inheritedCounts[source].ruleCount
      : inheritedCounts[source].pickedIds.length;

  const chipsOf = (source: HarnessSource) =>
    source === HarnessSource.Assets
      ? ownAssetRules.map(({ name }) => name)
      : pickedNames[source] ?? [];

  const editingId = editor.open ? editor.rule?.id : undefined;
  const existingRuleNames = [
    ...inheritedRules(resolved),
    ...own.filter(({ id }) => id !== editingId),
  ].map(({ name }) => name);

  const handleRuleSubmit = async (rule: ContextRule) => {
    onChange((current) =>
      withRule(current, { ...rule, id: rule.id ?? uuidv4() })
    );
    setEditor({ open: false });
  };

  return (
    <Box data-testid="context-tab" direction="col" gap={6}>
      <Typography className="tw:text-tertiary" size="text-sm">
        {t('message.agent-harness-context-intro')}
      </Typography>
      <div className="tw:grid tw:grid-cols-[repeat(auto-fit,minmax(200px,1fr))] tw:gap-4">
        {SOURCE_ORDER.map((source) => (
          <SourceCard
            canEdit={canEdit}
            chips={chipsOf(source)}
            count={sourceCount(source)}
            inheritedCount={inheritedCount(source)}
            key={source}
            source={source}
            onManage={() =>
              source === HarnessSource.Assets
                ? setEditor({ open: true })
                : setPicking(source)
            }
          />
        ))}
      </div>
      <Box direction="col" gap={3}>
        <Box align="center" gap={2}>
          <h3 className="tw:m-0 tw:text-md tw:font-semibold tw:text-primary">
            {t('label.asset-rule-plural')}
          </h3>
          <Badge color="gray" data-testid="asset-rule-count" size="sm">
            {assetRules.length}
          </Badge>
          {canEdit && (
            <Button
              className="tw:ml-auto"
              color="secondary"
              data-testid="add-asset-rule"
              iconLeading={Plus}
              isDisabled={own.length >= MAX_HARNESS_RULES}
              size="sm"
              onPress={() => setEditor({ open: true })}>
              {t('label.add-rule')}
            </Button>
          )}
        </Box>
        {assetRules.length === 0 ? (
          <Typography className="tw:text-tertiary" size="text-sm">
            {t('message.agent-harness-no-asset-rules')}
          </Typography>
        ) : (
          assetRules.map(({ inherited, rule }) => (
            <ContextRuleCard
              canEdit={canEdit}
              inherited={inherited}
              key={`${inherited ? 'inherited' : 'own'}-${rule.id ?? rule.name}`}
              rule={rule}
              onDelete={() =>
                onChange((current) => withoutRule(current, rule.id))
              }
              onEdit={() => setEditor({ open: true, rule })}
            />
          ))
        )}
      </Box>
      <ContextRuleEditor
        existingRuleNames={existingRuleNames}
        harnessId={harnessId}
        open={editor.open}
        rule={editor.open ? editor.rule : undefined}
        onClose={() => setEditor({ open: false })}
        onSubmit={handleRuleSubmit}
      />
      <SourcePickerDrawer
        pickedIds={picking ? counts[picking].pickedIds : []}
        source={picking}
        onClose={() => setPicking(undefined)}
        onSave={(source, ids) => {
          onChange((current) => withPickedIds(current, source, ids, uuidv4));
          setPicking(undefined);
        }}
      />
    </Box>
  );
};

export default ContextTab;
