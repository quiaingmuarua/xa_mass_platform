import {getAppConfig} from '@/app/config'
import {
    getProjectMock,
    listProjectEventDefinitionsMock,
    listProjectsMock,
    listProjectSubmittersMock,
} from '@/api/projects.mock'
import {
    getProjectReal,
    listProjectEventDefinitionsReal,
    listProjectsReal,
    listProjectSubmittersReal,
} from '@/api/projects.real'
import type {SdkEventDefinition} from '@/types/metadata'
import type {
    ProjectMetadata,
    ProjectSubmitterMetadata,
} from '@/types/projects'

export async function listProjects(): Promise<ProjectMetadata[]> {
    if (getAppConfig().useMockApi) {
        return listProjectsMock()
    }

    return listProjectsReal()
}

export async function getProject(projectCode: string): Promise<ProjectMetadata> {
    if (getAppConfig().useMockApi) {
        return getProjectMock(projectCode)
    }

    return getProjectReal(projectCode)
}

export async function listProjectEventDefinitions(
    projectCode: string,
): Promise<SdkEventDefinition[]> {
    if (getAppConfig().useMockApi) {
        return listProjectEventDefinitionsMock(projectCode)
    }

    return listProjectEventDefinitionsReal(projectCode)
}

export async function listProjectSubmitters(
    projectCode: string,
): Promise<ProjectSubmitterMetadata[]> {
    if (getAppConfig().useMockApi) {
        return listProjectSubmittersMock(projectCode)
    }

    return listProjectSubmittersReal(projectCode)
}
