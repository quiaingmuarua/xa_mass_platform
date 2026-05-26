import {getAppConfig} from '@/app/config'
import {
    getEventDefinitionMock,
    listEventCapabilitiesMock,
    listEventDefinitionsMock,
    listWorkerGroupCapabilitiesMock,
} from '@/api/catalog.mock'
import {
    getEventDefinitionReal,
    listEventCapabilitiesReal,
    listEventDefinitionsReal,
    listWorkerGroupCapabilitiesReal,
} from '@/api/catalog.real'
import type {
    EventCapability,
    EventDefinition,
    WorkerGroupCapability,
} from '@/types/catalog'

export async function listEventDefinitions(): Promise<EventDefinition[]> {
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

export async function listWorkerGroupCapabilities(): Promise<
    WorkerGroupCapability[]
> {
    if (getAppConfig().useMockApi) {
        return listWorkerGroupCapabilitiesMock()
    }

    return listWorkerGroupCapabilitiesReal()
}

export async function getEventDefinition(
    eventCode: string,
): Promise<EventDefinition> {
    if (getAppConfig().useMockApi) {
        return getEventDefinitionMock(eventCode)
    }

    return getEventDefinitionReal(eventCode)
}
