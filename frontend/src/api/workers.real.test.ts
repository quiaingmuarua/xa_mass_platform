import {setRuntimeConfigOverrides} from '@/app/config'
import {listWorkersReal} from '@/api/workers.real'

function jsonResponse(body: unknown): Response {
    return new Response(JSON.stringify(body), {
        status: 200,
        headers: {
            'Content-Type': 'application/json',
        },
    })
}

describe('workers.real', () => {
    it('preserves backend worker field source labels', async () => {
        setRuntimeConfigOverrides({
            apiBaseUrl: '/backend',
        })
        const fetchMock = vi.fn().mockResolvedValue(
            jsonResponse({
                code: 0,
                msg: 'ok',
                data: {
                    items: [
                        {
                            workerId: 'worker-001',
                            runtimeStatus: 'ONLINE',
                            reachability: 'ONLINE',
                            reachable: true,
                            workerGroupId: 'group-a',
                            transportHint: 'realtime',
                            agentVersion: '1.2.3',
                            supportedProjects: ['demoApp'],
                            supportedEventCodes: ['demo.dispatch'],
                            attributes: {region: 'us'},
                            locked: true,
                            fieldSources: {
                                workerId: 'declaration',
                                runtimeStatus: 'runtimeStatusDisplay',
                                reachability: 'workerRuntimeReachability',
                                reachable: 'workerRuntimeReachability',
                            },
                        },
                    ],
                    total: 1,
                },
            }),
        )
        vi.stubGlobal('fetch', fetchMock)

        const response = await listWorkersReal()

        expect(fetchMock).toHaveBeenCalledWith(
            '/backend/api/v1/runtime/workers',
            expect.any(Object),
        )
        expect(response.items[0].fieldSources?.workerId).toBe('declaration')
        expect(response.items[0].fieldSources?.runtimeStatus).toBe('runtimeStatusDisplay')
    })
})
