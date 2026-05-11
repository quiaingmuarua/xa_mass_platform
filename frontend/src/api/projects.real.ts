import {requestApiData} from '@/api/http'
import type {SdkEventDefinition} from '@/types/metadata'
import type {
    ProjectMetadata,
    ProjectSubmitterMetadata,
} from '@/types/projects'

export async function listProjectsReal(): Promise<ProjectMetadata[]> {
    return requestApiData<ProjectMetadata[]>('/api/v1/projects')
}

export async function getProjectReal(
    projectCode: string,
): Promise<ProjectMetadata> {
    return requestApiData<ProjectMetadata>(
        `/api/v1/projects/${encodeURIComponent(projectCode)}`,
    )
}

export async function listProjectEventDefinitionsReal(
    projectCode: string,
): Promise<SdkEventDefinition[]> {
    return requestApiData<SdkEventDefinition[]>(
        `/api/v1/projects/${encodeURIComponent(projectCode)}/events`,
    )
}

export async function listProjectSubmittersReal(
    projectCode: string,
): Promise<ProjectSubmitterMetadata[]> {
    return requestApiData<ProjectSubmitterMetadata[]>(
        `/api/v1/projects/${encodeURIComponent(projectCode)}/submitters`,
    )
}
