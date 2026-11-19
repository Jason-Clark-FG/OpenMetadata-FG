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
  Box,
  Button,
  Card,
  Skeleton,
  Typography,
} from '@openmetadata/ui-core-components';
import { AxiosError } from 'axios';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { AgentHarnessManagedSettings } from '../../../../generated/api/context/agentHarnessManagedSettings';
import { Surface } from '../../../../generated/api/context/agentHarnessSurfaces';
import {
  getAgentHarnessManagedSettings,
  getAgentHarnessSurfaces,
} from '../../../../rest/AgentHarnessAPI';
import { showErrorToast } from '../../../../utils/ToastUtils';
import PluginPanel from '../PluginPanel/PluginPanel';
import SurfaceMatrix from '../SurfaceMatrix/SurfaceMatrix';

const SurfacesTab = () => {
  const { t } = useTranslation();
  const [isPanelOpen, setIsPanelOpen] = useState(false);
  const [surfaces, setSurfaces] = useState<Surface[]>();
  const [settings, setSettings] = useState<AgentHarnessManagedSettings>();

  useEffect(() => {
    getAgentHarnessSurfaces()
      .then(({ surfaces: list }) => setSurfaces(list))
      .catch((error) => {
        setSurfaces([]);
        showErrorToast(error as AxiosError);
      });
    getAgentHarnessManagedSettings()
      .then(setSettings)
      .catch((error) => showErrorToast(error as AxiosError));
  }, []);

  return (
    <Box data-testid="surfaces-tab" direction="col" gap={4}>
      <Card color="brand" data-testid="plugin-banner">
        <Box align="center" className="tw:px-5 tw:py-4" gap={4} wrap="wrap">
          <Box className="tw:min-w-60 tw:flex-1" direction="col" gap={1}>
            <Typography size="text-sm" weight="semibold">
              {t('label.one-plugin-every-surface')}
            </Typography>
            <Typography className="tw:text-tertiary" size="text-sm">
              {t('message.agent-harness-plugin-description')}
            </Typography>
          </Box>
          <Button
            color={isPanelOpen ? 'secondary' : 'primary'}
            data-testid="generate-plugin"
            onPress={() => setIsPanelOpen((open) => !open)}>
            {t(
              isPanelOpen
                ? 'label.hide-content-plural'
                : 'label.generate-plugin'
            )}
          </Button>
        </Box>
      </Card>
      {isPanelOpen && <PluginPanel settings={settings} />}
      {surfaces ? (
        <SurfaceMatrix
          settings={settings}
          surfaces={surfaces}
          onShowSetup={() => setIsPanelOpen(true)}
        />
      ) : (
        <Skeleton className="tw:h-48 tw:w-full" />
      )}
      <Typography
        className="tw:text-tertiary"
        data-testid="surfaces-footer"
        size="text-xs">
        {t('message.agent-harness-surfaces-footer')}
      </Typography>
    </Box>
  );
};

export default SurfacesTab;
