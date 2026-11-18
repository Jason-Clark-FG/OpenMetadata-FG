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
import { Badge, Table, Typography } from '@openmetadata/ui-core-components';
import { useTranslation } from 'react-i18next';
import {
  Surface,
  SurfaceStatus,
} from '../../../../generated/api/context/agentHarnessSurfaces';
import { formatDateTime } from '../../../../utils/date-time/DateTimeUtils';

export interface SurfaceMatrixProps {
  surfaces: Surface[];
}

const SurfaceMatrix = ({ surfaces }: SurfaceMatrixProps) => {
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

  return (
    <div className="tw:overflow-x-auto">
      <Table
        aria-label={t('label.surface-plural')}
        className="tw:min-w-180"
        data-testid="surface-matrix">
        <Table.Header>
          <Table.Head isRowHeader id="surface" label={t('label.surface')} />
          <Table.Head id="skills" label={t('label.skill')} />
          <Table.Head id="mcp" label={t('label.mcp')} />
          <Table.Head id="hooks" label={t('label.hook-plural')} />
          <Table.Head id="status" label={t('label.status')} />
          <Table.Head id="lastSeen" label={t('label.last-seen')} />
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
                  <Typography size="text-sm" weight="medium">
                    {surface.name}
                  </Typography>
                </Table.Cell>
                <Table.Cell>{renderSupport(surface.skills)}</Table.Cell>
                <Table.Cell>{renderSupport(surface.connector)}</Table.Cell>
                <Table.Cell>{renderSupport(surface.hooks)}</Table.Cell>
                <Table.Cell>
                  <Badge
                    color={connected ? 'success' : 'warning'}
                    data-testid={`surface-status-${surface.id}`}
                    size="sm"
                    type="pill-color">
                    {t(connected ? 'label.connected' : 'label.not-connected')}
                  </Badge>
                </Table.Cell>
                <Table.Cell>
                  <Typography className="tw:text-tertiary" size="text-sm">
                    {surface.lastSeenAt
                      ? formatDateTime(surface.lastSeenAt)
                      : '—'}
                  </Typography>
                </Table.Cell>
              </Table.Row>
            );
          })}
        </Table.Body>
      </Table>
    </div>
  );
};

export default SurfaceMatrix;
