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
import { Button } from '@openmetadata/ui-core-components';
import { Check, Download01 } from '@openmetadata/ui-core-components/icons';
import { AxiosError } from 'axios';
import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  DONE_LABEL_MS,
  PLUGIN_ZIP_NAME,
} from '../../../../constants/discovery/agentHarness.constants';
import { getAgentHarnessPlugin } from '../../../../rest/AgentHarnessAPI';
import { downloadBlob } from '../../../../utils/ContextCenterPureUtils';
import { showErrorToast } from '../../../../utils/ToastUtils';

const PluginDownloadButton = () => {
  const { t } = useTranslation();
  const [download, setDownload] = useState<'idle' | 'loading' | 'done'>('idle');
  const resetTimer = useRef<number>();

  useEffect(() => () => window.clearTimeout(resetTimer.current), []);

  const handleDownload = async () => {
    setDownload('loading');
    try {
      downloadBlob(await getAgentHarnessPlugin(), PLUGIN_ZIP_NAME);
      setDownload('done');
      resetTimer.current = window.setTimeout(
        () => setDownload('idle'),
        DONE_LABEL_MS
      );
    } catch (error) {
      setDownload('idle');
      showErrorToast(error as AxiosError);
    }
  };

  return (
    <Button
      color="primary"
      data-testid="download-plugin"
      iconLeading={download === 'done' ? Check : Download01}
      isLoading={download === 'loading'}
      size="sm"
      onPress={handleDownload}>
      {t(download === 'done' ? 'label.downloaded' : 'label.download-plugin')}
    </Button>
  );
};

export default PluginDownloadButton;
