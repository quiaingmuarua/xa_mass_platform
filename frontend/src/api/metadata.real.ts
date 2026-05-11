import {requestApiData} from '@/api/http'
import type {
    EventCapability,
    SdkEventDefinition,
} from '@/types/metadata'

export async function listEventDefinitionsReal(): Promise<SdkEventDefinition[]> {
    return requestApiData<SdkEventDefinition[]>('/api/v1/meta/events')
}

export async function listEventCapabilitiesReal(): Promise<EventCapability[]> {
    return requestApiData<EventCapability[]>('/api/v1/meta/event-capabilities')
}

export async function getEventDefinitionReal(
    eventCode: string,
): Promise<SdkEventDefinition> {
    return requestApiData<SdkEventDefinition>(
        `/api/v1/meta/events/${encodeURIComponent(eventCode)}`,
    )
}
