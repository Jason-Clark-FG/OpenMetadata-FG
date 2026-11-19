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
  Toggle,
  Typography,
} from '@openmetadata/ui-core-components';
import { ArrowRight } from '@openmetadata/ui-core-components/icons';
import { useTranslation } from 'react-i18next';

export type InheritanceStripProps =
  | { kind: 'default' }
  | {
      kind: 'persona';
      defaultVersion?: number;
      personaName: string;
      showInherited: boolean;
      onShowInheritedChange: (show: boolean) => void;
    };

const InheritanceStrip = (props: InheritanceStripProps) => {
  const { t } = useTranslation();

  if (props.kind === 'default') {
    return (
      <Box
        className="tw:rounded-lg tw:border tw:border-secondary tw:bg-secondary_subtle tw:px-4 tw:py-3"
        data-testid="default-harness-banner">
        <Typography className="tw:text-tertiary" size="text-sm">
          {t('message.agent-harness-default-banner')}
        </Typography>
      </Box>
    );
  }

  return (
    <Box
      align="center"
      className="tw:rounded-lg tw:border tw:border-secondary tw:bg-secondary_subtle tw:px-4 tw:py-3"
      data-testid="inheritance-strip"
      gap={3}
      wrap="wrap">
      <Box align="center" gap={2}>
        <Badge color="gray" size="md">
          {t('label.company-default-version', {
            version: `v${props.defaultVersion?.toFixed(1) ?? '0.1'}`,
          })}
        </Badge>
        <ArrowRight className="tw:size-4 tw:text-fg-quaternary" />
        <Badge color="brand" data-testid="inheritance-persona" size="md">
          {props.personaName}
        </Badge>
      </Box>
      <Typography
        className="tw:min-w-50 tw:flex-1 tw:text-tertiary"
        size="text-sm">
        {t('message.agent-harness-inheritance-hint')}
      </Typography>
      <Box align="center" gap={2}>
        <Typography
          aria-hidden
          className="tw:text-secondary"
          size="text-sm"
          weight="medium">
          {t('label.show-inherited')}
        </Typography>
        <Toggle
          aria-label={t('label.show-inherited')}
          data-testid="show-inherited"
          isSelected={props.showInherited}
          size="sm"
          onChange={props.onShowInheritedChange}
        />
      </Box>
    </Box>
  );
};

export default InheritanceStrip;
