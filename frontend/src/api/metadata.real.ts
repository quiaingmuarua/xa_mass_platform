import {requestApiData} from '@/api/http'
import type {EventCapability, ProjectMetadata, SdkEventDefinition} from '@/types/metadata'

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

export async function listProjectEventDefinitionsReal(
    projectCode: string,
): Promise<SdkEventDefinition[]> {
    return requestApiData<SdkEventDefinition[]>(
        `/sdk/meta/projects/${encodeURIComponent(projectCode)}/events`,
    )
}

export async function listEventDefinitionsReal(): Promise<SdkEventDefinition[]> {
    return requestApiData<SdkEventDefinition[]>('/sdk/meta/events')
}

export async function listEventCapabilitiesReal(): Promise<EventCapability[]> {
    return requestApiData<EventCapability[]>('/sdk/meta/event-capabilities')
}

export async function getEventDefinitionReal(
    eventCode: string,
): Promise<SdkEventDefinition> {
    return requestApiData<SdkEventDefinition>(
        `/sdk/meta/events/${encodeURIComponent(eventCode)}`,
    )
}
