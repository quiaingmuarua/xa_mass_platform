import {mockEvents, mockProjects, mockProjectSubmitters} from '@/api/mockCatalog'
import type {SdkEventDefinition} from '@/types/metadata'
import type {
    ProjectMetadata,
    ProjectSubmitterMetadata,
} from '@/types/projects'

function delay<T>(value: T): Promise<T> {
    return new Promise((resolve) => {
        window.setTimeout(() => resolve(value), 80)
    })
}

export async function listProjectsMock(): Promise<ProjectMetadata[]> {
    return delay(mockProjects)
}

export async function getProjectMock(
    projectCode: string,
): Promise<ProjectMetadata> {
    const project = mockProjects.find((item) => item.code === projectCode)
    if (!project) {
        throw new Error(`Project metadata not found: ${projectCode}`)
    }

    return delay(project)
}

export async function listProjectEventDefinitionsMock(
    projectCode: string,
): Promise<SdkEventDefinition[]> {
    const project = mockProjects.find((item) => item.code === projectCode)
    if (!project) {
        throw new Error(`Project metadata not found: ${projectCode}`)
    }

    return delay(
        mockEvents.filter((event) => project.eventCodes.includes(event.code)),
    )
}

export async function listProjectSubmittersMock(
    projectCode: string,
): Promise<ProjectSubmitterMetadata[]> {
    const project = mockProjects.find((item) => item.code === projectCode)
    if (!project) {
        throw new Error(`Project metadata not found: ${projectCode}`)
    }

    return delay(mockProjectSubmitters[projectCode] ?? [])
}
