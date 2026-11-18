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
  const label = t(SOURCE_LABEL_KEYS[source]);
  const hidden = count - Math.min(chips.length, SOURCE_CARD_CHIPS);

  return (
    <Card
      className="tw:flex tw:flex-col tw:gap-3 tw:px-4 tw:py-4"
      data-testid={`source-card-${source}`}>
      <Box align="center" justify="between">
        <Typography weight="semibold">{label}</Typography>
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
      <Box direction="col">
        <Typography
          data-testid={`source-count-${source}`}
          size="display-xs"
          weight="semibold">
          {count}
        </Typography>
        <Typography className="tw:text-tertiary" size="text-sm">
          {source === HarnessSource.Assets
            ? t('label.asset-rule-plural')
            : t('label.added-here')}
        </Typography>
        {inheritedCount > 0 && (
          <Typography
            className="tw:text-quaternary"
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
            <Badge color="gray" key={chip} size="sm">
              {chip}
            </Badge>
          ))}
          {hidden > 0 && (
            <Badge color="gray" size="sm">
              {`+${hidden}`}
            </Badge>
          )}
        </Box>
      )}
    </Card>
  );
};

export default SourceCard;
