import {requestApiData} from '@/api/http'
import type {EventDefinition} from '@/types/catalog'
import type {
    ProjectDefinition,
    ProjectSubmitterMetadata,
} from '@/types/projects'

export async function listProjectsReal(): Promise<ProjectDefinition[]> {
    return requestApiData<ProjectDefinition[]>('/api/v1/projects')
}

export async function getProjectReal(
    projectCode: string,
): Promise<ProjectDefinition> {
    return requestApiData<ProjectDefinition>(
        `/api/v1/projects/${encodeURIComponent(projectCode)}`,
    )
}

export async function listProjectEventDefinitionsReal(
    projectCode: string,
): Promise<EventDefinition[]> {
    return requestApiData<EventDefinition[]>(
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
