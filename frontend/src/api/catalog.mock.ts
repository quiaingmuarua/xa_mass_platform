import {deriveMockEventCapabilities, mockEvents} from '@/api/mockCatalog'
import type {EventCapability, EventDefinition} from '@/types/catalog'

function delay<T>(value: T): Promise<T> {
    return new Promise((resolve) => {
        window.setTimeout(() => resolve(value), 80)
    })
}

export async function listEventDefinitionsMock(): Promise<EventDefinition[]> {
    return delay(mockEvents)
}

export async function listEventCapabilitiesMock(): Promise<EventCapability[]> {
    return delay(deriveMockEventCapabilities())
}

export async function getEventDefinitionMock(
    eventCode: string,
): Promise<EventDefinition> {
    const event = mockEvents.find((item) => item.code === eventCode)
    if (!event) {
        throw new Error(`SDK event definition not found: ${eventCode}`)
    }

    return delay(event)
}
