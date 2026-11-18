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
import { PageLayout } from '@openmetadata/ui-core-components';
import { AxiosError } from 'axios';
import classNames from 'classnames';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import DocumentTitle from '../../../components/common/DocumentTitle/DocumentTitle';
import Loader from '../../../components/common/Loader/Loader';
import ContextCenterHeader from '../../../components/ContextCenter/ContextCenterHeader/ContextCenterHeader.component';
import { useContextCenterPageLayout } from '../../../components/ContextCenter/ContextCenterLayout/useContextCenterPageLayout';
import { AgentHarnessState } from '../../../generated/api/context/agentHarnessState';
import { useAuth } from '../../../hooks/authHooks';
import { useIsAiMode } from '../../../hooks/useAppMode';
import { useFqn } from '../../../hooks/useFqn';
import {
  getDefaultAgentHarness,
  getPersonaAgentHarness,
} from '../../../rest/AgentHarnessAPI';
import contextCenterClassBase from '../../../utils/ContextCenterClassBase';
import { getEntityName } from '../../../utils/EntityNameUtils';
import { showErrorToast } from '../../../utils/ToastUtils';

const AgentHarnessEditorPage = () => {
  const { t } = useTranslation();
  const isAiMode = useIsAiMode();
  const pageLayoutClassNames = useContextCenterPageLayout();
  const { isAdminUser } = useAuth();
  const { fqn } = useFqn();
  const [state, setState] = useState<AgentHarnessState>();
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    setIsLoading(true);
    (fqn ? getPersonaAgentHarness(fqn) : getDefaultAgentHarness())
      .then(setState)
      .catch((error) => showErrorToast(error as AxiosError))
      .finally(() => setIsLoading(false));
  }, [fqn]);

  const persona = state?.published.persona;
  const name = fqn
    ? (persona && getEntityName(persona)) || fqn
    : t('label.company-default-harness');

  return (
    <div
      className={`tw:flex tw:flex-col tw:w-full tw:h-full tw:overflow-hidden ${contextCenterClassBase.getPageContainerClassName(
        isAiMode
      )}`}
      data-testid="agent-harness-editor-page">
      <DocumentTitle title={name} />
      <PageLayout
        className={pageLayoutClassNames.root}
        data-testid="context-center-page-layout">
        <PageLayout.Header className={pageLayoutClassNames.header}>
          <ContextCenterHeader
            breadcrumbs={[
              {
                label: t('label.agent-harness'),
                href: contextCenterClassBase.getAgentHarnessListPath(),
              },
              { label: name },
            ]}
            hasPermission={isAdminUser}
            subtitle={t(
              fqn
                ? 'message.agent-harness-persona-subtitle'
                : 'message.agent-harness-default-subtitle'
            )}
            title={name}
          />
        </PageLayout.Header>
        <PageLayout.Content
          className={classNames(
            'tw:flex tw:flex-col tw:min-h-0 tw:overflow-y-auto',
            pageLayoutClassNames.content
          )}>
          {isLoading && <Loader />}
        </PageLayout.Content>
      </PageLayout>
    </div>
  );
};

export default AgentHarnessEditorPage;
