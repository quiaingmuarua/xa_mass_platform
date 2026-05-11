import {getAppConfig} from '@/app/config'
import {
    getEventDefinitionMock,
    listEventCapabilitiesMock,
    listEventDefinitionsMock,
} from '@/api/metadata.mock'
import {
    getEventDefinitionReal,
    listEventCapabilitiesReal,
    listEventDefinitionsReal,
} from '@/api/metadata.real'
import type {
    EventCapability,
    SdkEventDefinition,
} from '@/types/metadata'

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
