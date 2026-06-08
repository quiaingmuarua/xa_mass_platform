import {mockEvents, mockProjects} from '@/api/mockCatalog'
import type {EventDefinition} from '@/types/catalog'
import type {ProjectDefinition} from '@/types/projects'

function delay<T>(value: T): Promise<T> {
    return new Promise((resolve) => {
        window.setTimeout(() => resolve(value), 80)
    })
}

export async function listProjectsMock(): Promise<ProjectDefinition[]> {
    return delay(mockProjects)
}

export async function getProjectMock(
    projectCode: string,
): Promise<ProjectDefinition> {
    const project = mockProjects.find((item) => item.code === projectCode)
    if (!project) {
        throw new Error(`Project not found: ${projectCode}`)
    }

    return delay(project)
}

export async function listProjectEventDefinitionsMock(
    projectCode: string,
): Promise<EventDefinition[]> {
    const project = mockProjects.find((item) => item.code === projectCode)
    if (!project) {
        throw new Error(`Project not found: ${projectCode}`)
    }

    return delay(
        mockEvents.filter((event) => project.eventCodes.includes(event.code)),
    )
}
