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
  Toggle,
  Typography,
} from '@openmetadata/ui-core-components';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import {
  MEMORY_REVIEW_LABEL_KEYS,
  MEMORY_VISIBILITY_LABEL_KEYS,
} from '../../../../constants/discovery/agentHarness.constants';
import {
  AgentHarnessSpec,
  HarnessKind,
  MemoryReview,
  MemorySettings,
  MemoryVisibility,
} from '../../../../generated/api/context/agentHarnessState';
import contextCenterClassBase from '../../../../utils/ContextCenterClassBase';
import { withMemory } from '../../../../utils/discovery/context-center/agentHarness.utils';
import { Transi18next } from '../../../../utils/i18next/LocalUtil';
import SegmentedControl from '../SegmentedControl/SegmentedControl';

export interface MemoryTabProps {
  kind: HarnessKind;
  spec: AgentHarnessSpec;
  /** The settings this harness falls back to: the default's on a persona harness. */
  inheritedMemory: MemorySettings;
  canEdit: boolean;
  onChange: (change: (spec: AgentHarnessSpec) => AgentHarnessSpec) => void;
}

type MemoryToggle = 'capture' | 'linkAssets' | 'dedup';

const TOGGLES: { key: MemoryToggle; labelKey: string; hintKey: string }[] = [
  {
    hintKey: 'message.agent-harness-memory-capture-hint',
    key: 'capture',
    labelKey: 'label.capture-agent-memory-plural',
  },
  {
    hintKey: 'message.agent-harness-memory-link-assets-hint',
    key: 'linkAssets',
    labelKey: 'label.link-to-assets-used',
  },
  {
    hintKey: 'message.agent-harness-memory-dedup-hint',
    key: 'dedup',
    labelKey: 'label.deduplicate',
  },
];

const REVIEW_ORDER = [MemoryReview.StewardReview, MemoryReview.AuthorOnly];
const VISIBILITY_ORDER = [
  MemoryVisibility.Private,
  MemoryVisibility.Persona,
  MemoryVisibility.Organization,
];

const MemoryTab = ({
  kind,
  spec,
  inheritedMemory,
  canEdit,
  onChange,
}: MemoryTabProps) => {
  const { t } = useTranslation();
  const memory = spec.memory ?? inheritedMemory;
  const isAuthorOnly = memory.review === MemoryReview.AuthorOnly;
  const usesDefault = kind === HarnessKind.Persona && !spec.memory;

  const set = (change: Partial<MemorySettings>) =>
    onChange((current) =>
      withMemory(current, {
        ...(current.memory ?? inheritedMemory),
        ...change,
      })
    );

  return (
    <Box data-testid="memory-tab" direction="col" gap={4}>
      {kind === HarnessKind.Persona && (
        <Box align="center" gap={3}>
          {usesDefault ? (
            <Badge color="gray" data-testid="memory-from-default" size="sm">
              {t('label.from-default')}
            </Badge>
          ) : (
            canEdit && (
              <Button
                color="link-gray"
                data-testid="memory-use-default"
                size="sm"
                onPress={() =>
                  onChange((current) => ({ ...current, memory: undefined }))
                }>
                {t('label.use-default-setting-plural')}
              </Button>
            )
          )}
          <Typography className="tw:text-tertiary" size="text-sm">
            {t('message.agent-harness-memory-persona-hint')}
          </Typography>
        </Box>
      )}
      <Card data-testid="memory-settings">
        {TOGGLES.map(({ key, labelKey, hintKey }) => (
          <Box
            align="center"
            className="tw:border-t tw:border-secondary tw:px-4 tw:py-4 tw:first:border-t-0"
            gap={4}
            key={key}>
            <Box className="tw:flex-1" direction="col" gap={1}>
              <Typography size="text-sm" weight="semibold">
                {t(labelKey)}
              </Typography>
              <Typography className="tw:text-tertiary" size="text-sm">
                {t(hintKey)}
              </Typography>
            </Box>
            <Toggle
              aria-label={t(labelKey)}
              data-testid={`memory-${key}`}
              isDisabled={!canEdit}
              isSelected={memory[key]}
              onChange={(value) => set({ [key]: value })}
            />
          </Box>
        ))}
        <Box
          align="center"
          className="tw:border-t tw:border-secondary tw:px-4 tw:py-4"
          gap={4}
          wrap="wrap">
          <Box className="tw:min-w-60 tw:flex-1" direction="col" gap={1}>
            <Typography size="text-sm" weight="semibold">
              {t('label.review')}
            </Typography>
            <Typography className="tw:text-tertiary" size="text-sm">
              {t('message.agent-harness-memory-review-hint')}
            </Typography>
          </Box>
          <SegmentedControl
            aria-label={t('label.review')}
            data-testid="memory-review"
            isDisabled={!canEdit}
            options={REVIEW_ORDER.map((review) => ({
              id: review,
              label: t(MEMORY_REVIEW_LABEL_KEYS[review]),
            }))}
            value={memory.review}
            onChange={(review: MemoryReview) => set({ review })}
          />
        </Box>
        <Box
          align="center"
          className="tw:border-t tw:border-secondary tw:px-4 tw:py-4"
          gap={4}
          wrap="wrap">
          <Box className="tw:min-w-60 tw:flex-1" direction="col" gap={1}>
            <Typography size="text-sm" weight="semibold">
              {t('label.visibility')}
            </Typography>
            <Typography className="tw:text-tertiary" size="text-sm">
              {t(
                isAuthorOnly
                  ? 'message.agent-harness-memory-author-only-private'
                  : 'message.agent-harness-memory-visibility-hint'
              )}
            </Typography>
          </Box>
          <SegmentedControl
            aria-label={t('label.visibility')}
            data-testid="memory-visibility"
            isDisabled={!canEdit}
            options={VISIBILITY_ORDER.map((visibility) => ({
              id: visibility,
              isDisabled:
                isAuthorOnly && visibility !== MemoryVisibility.Private,
              label: t(MEMORY_VISIBILITY_LABEL_KEYS[visibility]),
            }))}
            value={memory.visibility}
            onChange={(visibility: MemoryVisibility) => set({ visibility })}
          />
        </Box>
      </Card>
      <Typography
        className="tw:text-tertiary"
        data-testid="memory-footer"
        size="text-sm">
        <Transi18next
          i18nKey="message.agent-harness-memory-footer"
          renderElement={
            <Link
              className="tw:font-medium tw:text-brand-secondary"
              data-testid="memories-link"
              to={`${contextCenterClassBase.getMemoriesListPath()}?source=agent`}
            />
          }
        />
      </Typography>
      <Typography className="tw:text-tertiary" size="text-sm">
        {t('message.agent-harness-memory-capture-later')}
      </Typography>
    </Box>
  );
};

export default MemoryTab;
