import {requestApiData} from '@/api/http'
import type {
    EventCapability,
    EventDefinition,
} from '@/types/catalog'

export async function listEventDefinitionsReal(): Promise<EventDefinition[]> {
    return requestApiData<EventDefinition[]>('/api/v1/catalog/events')
}

export async function listEventCapabilitiesReal(): Promise<EventCapability[]> {
    return requestApiData<EventCapability[]>('/api/v1/catalog/event-capabilities')
}

export async function getEventDefinitionReal(
    eventCode: string,
): Promise<EventDefinition> {
    return requestApiData<EventDefinition>(
        `/api/v1/catalog/events/${encodeURIComponent(eventCode)}`,
    )
}
