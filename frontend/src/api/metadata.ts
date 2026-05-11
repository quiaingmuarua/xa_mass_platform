import {getAppConfig} from '@/app/config'
import {
    getEventDefinitionMock,
    getProjectMetadataMock,
    listProjectSubmittersMock,
    listEventCapabilitiesMock,
    listEventDefinitionsMock,
    listProjectEventDefinitionsMock,
    listProjectMetadataMock,
} from '@/api/metadata.mock'
import {
    getEventDefinitionReal,
    getProjectMetadataReal,
    listProjectSubmittersReal,
    listEventCapabilitiesReal,
    listEventDefinitionsReal,
    listProjectEventDefinitionsReal,
    listProjectMetadataReal,
} from '@/api/metadata.real'
import type {
    EventCapability,
    ProjectMetadata,
    ProjectSubmitterMetadata,
    SdkEventDefinition,
} from '@/types/metadata'

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

export async function listEventDefinitions(): Promise<SdkEventDefinition[]> {
    if (getAppConfig().useMockApi) {
        return listEventDefinitionsMock()
    }

    return listEventDefinitionsReal()
}

export async function listEventCapabilities(): Promise<EventCapability[]> {
    if (getAppConfig().useMockApi) {
        return listEventCapabilitiesMock()
    }

    return listEventCapabilitiesReal()
}

export async function getEventDefinition(
    eventCode: string,
): Promise<SdkEventDefinition> {
    if (getAppConfig().useMockApi) {
        return getEventDefinitionMock(eventCode)
    }

    return getEventDefinitionReal(eventCode)
}
