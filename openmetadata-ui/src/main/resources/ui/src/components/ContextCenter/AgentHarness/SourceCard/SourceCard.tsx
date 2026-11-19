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
  Typography,
} from '@openmetadata/ui-core-components';
import { useTranslation } from 'react-i18next';
import {
  SOURCE_CARD_CHIPS,
  SOURCE_LABEL_KEYS,
} from '../../../../constants/discovery/agentHarness.constants';
import { HarnessSource } from '../../../../interface/discovery/agentHarness.interface';

export interface SourceCardProps {
  source: HarnessSource;
  count: number;
  inheritedCount: number;
  chips: string[];
  canEdit: boolean;
  onManage: () => void;
}

const SourceCard = ({
  source,
  count,
  inheritedCount,
  chips,
  canEdit,
  onManage,
}: SourceCardProps) => {
  const { t } = useTranslation();
  const hidden = count - Math.min(chips.length, SOURCE_CARD_CHIPS);
  const ruleLabelKey =
    count === 1 ? 'label.rule-lowercase' : 'label.rule-lowercase-plural';
  // The Assets count is a count of rules; the other sources count picked items.
  const countLabelKey =
    source === HarnessSource.Assets ? ruleLabelKey : 'label.added-lowercase';

  return (
    <Card data-testid={`source-card-${source}`}>
      <Box className="tw:p-4" direction="col" gap={3}>
        <Box align="center" justify="between">
          <Typography size="text-sm" weight="semibold">
            {t(SOURCE_LABEL_KEYS[source])}
          </Typography>
          {canEdit && (
            <Button
              color="link-color"
              data-testid={`manage-${source}`}
              size="sm"
              onPress={onManage}>
              {t('label.manage')}
            </Button>
          )}
        </Box>
        <Box direction="col" gap={1}>
          <Box align="baseline" gap={2}>
            <Typography
              data-testid={`source-count-${source}`}
              size="display-xs"
              weight="semibold">
              {count}
            </Typography>
            <Typography className="tw:text-tertiary" size="text-sm">
              {t(countLabelKey)}
            </Typography>
          </Box>
          {inheritedCount > 0 && (
            <Typography
              className="tw:text-tertiary"
              data-testid={`source-inherited-${source}`}
              size="text-xs">
              {t('message.agent-harness-inherited-count', {
                count: inheritedCount,
              })}
            </Typography>
          )}
        </Box>
        {chips.length > 0 && (
          <Box gap={1} wrap="wrap">
            {chips.slice(0, SOURCE_CARD_CHIPS).map((chip) => (
              <Badge
                className="tw:font-mono"
                color="gray"
                key={chip}
                size="sm"
                type="color">
                {chip}
              </Badge>
            ))}
            {hidden > 0 && (
              <Badge color="gray" size="sm" type="color">
                {t('message.persona-context-more-sections', { count: hidden })}
              </Badge>
            )}
          </Box>
        )}
      </Box>
    </Card>
  );
};

export default SourceCard;
