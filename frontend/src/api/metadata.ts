import {getAppConfig} from '@/app/config'
import {
    getEventMetadataMock,
    getProjectMetadataMock,
    listEventCapabilitiesMock,
    listEventMetadataMock,
    listProjectEventMetadataMock,
    listProjectMetadataMock,
} from '@/api/metadata.mock'
import {
    getEventMetadataReal,
    getProjectMetadataReal,
    listEventCapabilitiesReal,
    listEventMetadataReal,
    listProjectEventMetadataReal,
    listProjectMetadataReal,
} from '@/api/metadata.real'
import type {EventCapability, EventMetadata, ProjectMetadata} from '@/types/metadata'

export async function listProjectMetadata(): Promise<ProjectMetadata[]> {
    if (getAppConfig().useMockApi) {
        return listProjectMetadataMock()
    }

    return listProjectMetadataReal()
}

export async function getProjectMetadata(
    projectCode: string,
): Promise<ProjectMetadata> {
    if (getAppConfig().useMockApi) {
        return getProjectMetadataMock(projectCode)
    }

    return getProjectMetadataReal(projectCode)
}

export async function listProjectEventMetadata(
    projectCode: string,
): Promise<EventMetadata[]> {
    if (getAppConfig().useMockApi) {
        return listProjectEventMetadataMock(projectCode)
    }

    return listProjectEventMetadataReal(projectCode)
}

export async function listEventMetadata(): Promise<EventMetadata[]> {
    if (getAppConfig().useMockApi) {
        return listEventMetadataMock()
    }

    return listEventMetadataReal()
}

export async function listEventCapabilities(): Promise<EventCapability[]> {
    if (getAppConfig().useMockApi) {
        return listEventCapabilitiesMock()
    }

    return listEventCapabilitiesReal()
}

export async function getEventMetadata(
    eventCode: string,
): Promise<EventMetadata> {
    if (getAppConfig().useMockApi) {
        return getEventMetadataMock(eventCode)
    }

    return getEventMetadataReal(eventCode)
}
