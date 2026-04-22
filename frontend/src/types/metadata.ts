export type MetadataPayloadType = 'TEXT' | 'JSON'

export type MetadataTaskMode = 'SINGLE_RUN' | 'STREAMING'

export interface ProjectMetadata {
    code: string
    name: string
    description: string
    enabled: boolean
    eventCodes: string[]
}

export interface EventMetadata {
    code: string
    name: string
    description: string
    payloadTypes: MetadataPayloadType[]
    taskModes: MetadataTaskMode[]
    enabled: boolean
}
