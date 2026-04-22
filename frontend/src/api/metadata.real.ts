import { requestApiData } from '@/api/http'
import type { EventMetadata, ProjectMetadata } from '@/types/metadata'

export async function listProjectMetadataReal(): Promise<ProjectMetadata[]> {
    return requestApiData<ProjectMetadata[]>('/sdk/meta/projects')
}

export async function getProjectMetadataReal(
    projectCode: string,
): Promise<ProjectMetadata> {
    return requestApiData<ProjectMetadata>(
        `/sdk/meta/projects/${encodeURIComponent(projectCode)}`,
    )
}

export async function listProjectEventMetadataReal(
    projectCode: string,
): Promise<EventMetadata[]> {
    return requestApiData<EventMetadata[]>(
        `/sdk/meta/projects/${encodeURIComponent(projectCode)}/events`,
    )
}

export async function listEventMetadataReal(): Promise<EventMetadata[]> {
    return requestApiData<EventMetadata[]>('/sdk/meta/events')
}

export async function getEventMetadataReal(
    eventCode: string,
): Promise<EventMetadata> {
    return requestApiData<EventMetadata>(
        `/sdk/meta/events/${encodeURIComponent(eventCode)}`,
    )
}
