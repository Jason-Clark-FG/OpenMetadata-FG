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
  Table,
  TableCard,
  Typography,
} from '@openmetadata/ui-core-components';
import { useTranslation } from 'react-i18next';
import { SURFACE_ROWS } from '../../../../constants/discovery/agentHarness.constants';
import { AgentHarnessManagedSettings } from '../../../../generated/api/context/agentHarnessManagedSettings';
import {
  Surface,
  SurfaceStatus,
} from '../../../../generated/api/context/agentHarnessSurfaces';
import { SurfaceAction } from '../../../../interface/discovery/agentHarness.interface';
import { formatDateTime } from '../../../../utils/date-time/DateTimeUtils';
import { getInstallCommand } from '../../../../utils/discovery/context-center/agentHarness.utils';
import CopyButton from '../../../common/CopyButton/CopyButton';

export interface SurfaceMatrixProps {
  surfaces: Surface[];
  /** Server URL and name for the copy actions; undefined while they load. */
  settings?: AgentHarnessManagedSettings;
  onShowSetup: () => void;
}

const SurfaceMatrix = ({
  surfaces,
  settings,
  onShowSetup,
}: SurfaceMatrixProps) => {
  const { t } = useTranslation();

  const renderSupport = (supported: boolean) =>
    supported ? (
      <Typography className="tw:text-success-primary" size="text-sm">
        {t('label.yes')}
      </Typography>
    ) : (
      <Typography className="tw:text-quaternary" size="text-sm">
        —
      </Typography>
    );

  const renderAction = (surface: Surface) => {
    const testId = `surface-action-${surface.id}`;
    switch (SURFACE_ROWS[surface.id].action) {
      case SurfaceAction.ShowSetup:
        return (
          <Button
            color="secondary"
            data-testid={testId}
            size="sm"
            onPress={onShowSetup}>
            {t('label.show-setup-step-plural')}
          </Button>
        );
      case SurfaceAction.CopyConnectorUrl:
        return (
          <CopyButton
            isDisabled={!settings}
            label={t('label.copy-connector-url')}
            testId={testId}
            value={settings?.serverUrl ?? ''}
          />
        );
      default:
        return (
          <CopyButton
            isDisabled={!settings}
            label={t('label.copy-install-command')}
            testId={testId}
            value={(settings && getInstallCommand(surface.id, settings)) ?? ''}
          />
        );
    }
  };

  return (
    <TableCard.Root className="tw:overflow-x-auto" size="sm">
      <Table
        aria-label={t('label.surface-plural')}
        className="tw:min-w-200"
        data-testid="surface-matrix">
        <Table.Header>
          <Table.Head isRowHeader id="surface" label={t('label.surface')} />
          <Table.Head id="instructions" label={t('label.instruction-plural')} />
          <Table.Head id="hooks" label={t('label.hook-plural')} />
          <Table.Head id="skills" label={t('label.skill-plural')} />
          <Table.Head id="mcp" label={t('label.mcp')} />
          <Table.Head id="status" label={t('label.status')} />
          <Table.Head id="action" label={t('label.action')} />
        </Table.Header>
        <Table.Body>
          {surfaces.map((surface) => {
            const connected = surface.status === SurfaceStatus.Connected;

            return (
              <Table.Row
                data-testid={`surface-${surface.id}`}
                id={surface.id}
                key={surface.id}>
                <Table.Cell>
                  <Box direction="col">
                    <Typography size="text-sm" weight="semibold">
                      {surface.name}
                    </Typography>
                    <Typography className="tw:text-tertiary" size="text-xs">
                      {t(SURFACE_ROWS[surface.id].viaKey)}
                    </Typography>
                  </Box>
                </Table.Cell>
                <Table.Cell>{renderSupport(true)}</Table.Cell>
                <Table.Cell>{renderSupport(surface.hooks)}</Table.Cell>
                <Table.Cell>{renderSupport(surface.skills)}</Table.Cell>
                <Table.Cell>{renderSupport(surface.connector)}</Table.Cell>
                <Table.Cell>
                  <Box align="start" direction="col" gap={1}>
                    <Badge
                      color={connected ? 'success' : 'warning'}
                      data-testid={`surface-status-${surface.id}`}
                      size="sm"
                      type="pill-color">
                      {t(connected ? 'label.connected' : 'label.not-connected')}
                    </Badge>
                    {surface.lastSeenAt && (
                      <Typography
                        className="tw:text-tertiary"
                        data-testid={`surface-last-seen-${surface.id}`}
                        size="text-xs">
                        {formatDateTime(surface.lastSeenAt)}
                      </Typography>
                    )}
                  </Box>
                </Table.Cell>
                <Table.Cell>{renderAction(surface)}</Table.Cell>
              </Table.Row>
            );
          })}
        </Table.Body>
      </Table>
    </TableCard.Root>
  );
};

export default SurfaceMatrix;
