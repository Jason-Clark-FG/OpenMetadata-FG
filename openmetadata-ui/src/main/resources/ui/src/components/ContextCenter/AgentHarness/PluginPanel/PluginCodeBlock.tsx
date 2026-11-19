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
import { Box } from '@openmetadata/ui-core-components';
import { useTranslation } from 'react-i18next';
import CopyButton from '../../../common/CopyButton/CopyButton';

export interface PluginCodeBlockProps {
  code: string;
  testId: string;
  copyLabel?: string;
}

const PluginCodeBlock = ({ code, testId, copyLabel }: PluginCodeBlockProps) => {
  const { t } = useTranslation();

  return (
    <Box className="tw:relative" direction="col">
      <pre
        className="tw:m-0 tw:max-h-80 tw:overflow-auto tw:rounded-lg tw:bg-primary-solid tw:p-4 tw:pr-14 tw:font-mono tw:text-xs tw:text-white tw:whitespace-pre-wrap tw:break-all"
        data-testid={testId}>
        {code}
      </pre>
      <div className="tw:absolute tw:right-2 tw:top-2">
        <CopyButton
          color="secondary"
          size="sm"
          testId={`${testId}-copy`}
          tooltip={copyLabel ?? t('label.copy')}
          value={code}
        />
      </div>
    </Box>
  );
};

export default PluginCodeBlock;
